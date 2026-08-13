package org.lockard.xyztilecache.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.lockard.xyztilecache.XyzUtil;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.PreloadProgress;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.PreloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class PreloadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PreloadService.class);

  static final String INFLIGHT_GAUGE = "xyz_preload_inflight";

  private final LayerStore layerStore;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final PreloadStore preloadStore;
  private final PmtilesDownloader pmtilesDownloader;
  private final MeterRegistry meterRegistry;
  private final XyzConfiguration configuration;

  private final ExecutorService xyzExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger inflightXyzPreloads = new AtomicInteger();

  /**
   * Live tile counters for in-flight raster preloads, keyed by preload id. Counters stay in memory
   * while a job runs — persisting every tile would rewrite {@code preloads.json} thousands of times
   * — and are copied onto the {@link Preload} when it reaches a terminal status.
   */
  private final ConcurrentHashMap<String, ProgressCounter> progressById = new ConcurrentHashMap<>();

  public PreloadService(
      LayerStore layerStore,
      LoadingCache<Tile, byte[]> tileCache,
      PreloadStore preloadStore,
      PmtilesDownloader pmtilesDownloader,
      MeterRegistry meterRegistry,
      XyzConfiguration configuration) {
    this.layerStore = layerStore;
    this.tileCache = tileCache;
    this.preloadStore = preloadStore;
    this.pmtilesDownloader = pmtilesDownloader;
    this.meterRegistry = meterRegistry;
    this.configuration = configuration;
  }

  @PostConstruct
  void registerMetrics() {
    Gauge.builder(INFLIGHT_GAUGE, inflightXyzPreloads, AtomicInteger::get)
        .description("Number of in-flight xyz preload jobs.")
        .register(meterRegistry);
  }

  @PreDestroy
  void shutdown() {
    xyzExecutor.shutdown();
  }

  public Preload submit(
      String name,
      BoundingBox boundingBox,
      int maxZoom,
      Set<String> layers,
      List<String> allowedUsers,
      List<String> allowedGroups)
      throws IOException {
    Set<String> validLayers =
        layers == null
            ? Set.of()
            : layers.stream()
                .filter(layerStore.getLayers()::containsKey)
                .collect(Collectors.toSet());

    if (validLayers.isEmpty()) {
      return null;
    }

    Layer vectorLayer =
        validLayers.stream()
            .map(layerStore.getLayers()::get)
            .filter(l -> l.sourceType() == Layer.SourceType.VECTOR_PMTILES)
            .findFirst()
            .orElse(null);

    Set<String> rasterLayers =
        validLayers.stream()
            .filter(
                l -> layerStore.getLayers().get(l).sourceType() != Layer.SourceType.VECTOR_PMTILES)
            .collect(Collectors.toSet());

    if (vectorLayer != null) {
      if (vectorLayer.urlTemplate() == null || vectorLayer.urlTemplate().isBlank()) {
        throw new IllegalArgumentException(
            "VECTOR_PMTILES layer '"
                + vectorLayer.effectiveId()
                + "' has no urlTemplate configured");
      }
      if (pmtilesDownloader.isDownloadInProgress()) {
        throw new IllegalStateException("A vector download is already in progress");
      }
      // Fail fast on input the pmtiles extract would reject; validating here (before the preload
      // is persisted) keeps a doomed download from ever showing up as RUNNING.
      PmtilesDownloader.requireValidBoundingBox(boundingBox);
      PmtilesDownloader.requireValidMaxZoom(maxZoom);
    }

    Preload preload = new Preload();
    preload.setId(UUID.randomUUID().toString());
    preload.setName(displayName(name, boundingBox, maxZoom));
    preload.setBoundingBox(boundingBox);
    preload.setMaxZoom(maxZoom);
    preload.setLayers(List.copyOf(validLayers));
    preload.setCreatedAt(Instant.now());
    preload.setAllowedUsers(
        allowedUsers == null ? new ArrayList<>() : new ArrayList<>(allowedUsers));
    preload.setAllowedGroups(
        allowedGroups == null ? new ArrayList<>() : new ArrayList<>(allowedGroups));

    preloadStore.addPreload(preload);

    // A mixed job has two independent halves; it is only finished when both of them are.
    JobTracker tracker =
        new JobTracker(preload, (rasterLayers.isEmpty() ? 0 : 1) + (vectorLayer == null ? 0 : 1));

    if (!rasterLayers.isEmpty()) {
      boundingBox.setMaxZoom(maxZoom);
      // Register the counter before submitting so a status poll between submit and the first tile
      // fetch already reports a total rather than "unknown".
      progressById.put(preload.getId(), new ProgressCounter());
      submitXyz(preload, rasterLayers, boundingBox, tracker);
    }
    if (vectorLayer != null) {
      startVectorDownload(preload, vectorLayer, tracker);
    }
    return preload;
  }

  private void startVectorDownload(Preload preload, Layer vectorLayer, JobTracker tracker) {
    try {
      pmtilesDownloader
          .startDownload(preload, vectorLayer, false)
          .whenComplete((ignored, error) -> tracker.partFinished(errorMessage(error)));
    } catch (RuntimeException e) {
      LOGGER.error("Failed to start vector download for preload {}", preload.getId(), e);
      // The raster half may already be running, so the job still has to be closed out.
      tracker.partFinished(errorMessage(e));
      throw e;
    }
  }

  /** Synchronous xyz preload used by startup bounding-box initialization. */
  public void preloadXyzTiles(Set<String> layerNames, BoundingBox bbox) {
    Set<String> validLayers =
        layerNames.stream().filter(layerStore.getLayers()::containsKey).collect(Collectors.toSet());
    if (validLayers.isEmpty()) return;
    runXyzPreload(validLayers, bbox, null);
  }

  /**
   * Tile progress for a preload: live counters while it runs, otherwise the snapshot persisted when
   * it finished. Null when there is nothing to count — a vector-only preload, or one created before
   * counters existed.
   */
  public PreloadProgress progressFor(Preload preload) {
    ProgressCounter counter = progressById.get(preload.getId());
    if (counter != null) {
      return counter.snapshot();
    }
    if (preload.getTotalTiles() <= 0) {
      return null;
    }
    return PreloadProgress.of(
        preload.getTotalTiles(), preload.getCompletedTiles(), preload.getFailedTiles());
  }

  /**
   * Nothing resumes a preload across a restart, so any job still marked PENDING/RUNNING in {@code
   * preloads.json} is dead and would otherwise poll as RUNNING forever.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void failInterruptedPreloads() {
    for (Preload preload : preloadStore.listPreloads()) {
      if (preload.getStatus() == Preload.Status.PENDING
          || preload.getStatus() == Preload.Status.RUNNING) {
        LOGGER.info("Marking preload '{}' FAILED: interrupted by a restart", preload.getId());
        updateStatus(preload, Preload.Status.FAILED, "Interrupted by a server restart.");
      }
    }
  }

  private void submitXyz(
      Preload preload, Set<String> layers, BoundingBox bbox, JobTracker tracker) {
    xyzExecutor.submit(() -> runXyzPreload(preload, layers, bbox, tracker));
  }

  /**
   * Runs the raster half of a preload and reports its outcome to {@code tracker}, which owns the
   * status: a job that also has a PMTiles download is DONE only once that half has finished too.
   */
  private void runXyzPreload(
      Preload preload, Set<String> layers, BoundingBox bbox, JobTracker tracker) {
    ProgressCounter counter = progressById.get(preload.getId());
    updateStatus(preload, Preload.Status.RUNNING, null);
    String error = null;
    try {
      runXyzPreload(layers, bbox, counter);
    } catch (RuntimeException e) {
      LOGGER.error("Raster preload pass failed for preload {}", preload.getId(), e);
      error = errorMessage(e);
    } finally {
      copyProgress(preload, counter);
      progressById.remove(preload.getId());
      tracker.partFinished(error);
    }
  }

  private void updateStatus(Preload preload, Preload.Status status, String errorMessage) {
    if (preload == null) return;
    preload.markStatus(status, errorMessage);
    persist(preload);
  }

  private void copyProgress(Preload preload, ProgressCounter counter) {
    if (counter == null) return;
    PreloadProgress snapshot = counter.snapshot();
    preload.setTotalTiles(snapshot.totalTiles());
    preload.setCompletedTiles(snapshot.completedTiles());
    preload.setFailedTiles(snapshot.failedTiles());
  }

  private void persist(Preload preload) {
    try {
      preloadStore.update(preload);
    } catch (IOException e) {
      LOGGER.warn(
          "Failed to persist preload '{}' status {}: {}",
          preload.getId(),
          preload.getStatus(),
          e.getMessage());
    } catch (java.util.NoSuchElementException e) {
      LOGGER.debug("Preload '{}' no longer present; skipping status update", preload.getId());
    }
  }

  private void runXyzPreload(Set<String> layers, BoundingBox bbox, ProgressCounter counter) {
    inflightXyzPreloads.incrementAndGet();
    int concurrency = Math.max(1, configuration.getPreloadConcurrency());
    Semaphore permits = new Semaphore(concurrency);
    try {
      // Iterate ranges lazily: materializing per-tile collections grows ~4^maxZoom and can
      // exhaust the heap for large bboxes. The semaphore bounds in-flight fetches so a preload
      // is parallel without hammering the source.
      List<XyzUtil.TileRange> ranges = XyzUtil.calculateBboxRanges(bbox);
      List<Layer> targets =
          layers.stream()
              .map(layerStore.getLayers()::get)
              .filter(l -> l != null && l.sourceType() != Layer.SourceType.VECTOR_PMTILES)
              .toList();
      if (counter != null) {
        long perLayer = ranges.stream().mapToLong(XyzUtil.TileRange::count).sum();
        counter.setTotal(perLayer * targets.size());
      }
      for (Layer layer : targets) {
        String layerId = layer.effectiveId();
        for (XyzUtil.TileRange range : ranges) {
          for (int x = range.xMin(); x <= range.xMax(); x++) {
            for (int y = range.yMin(); y <= range.yMax(); y++) {
              Tile tile = new Tile(layerId, x, y, range.zoom());
              permits.acquire();
              xyzExecutor.submit(
                  () -> {
                    try {
                      tileCache.get(tile);
                      if (counter != null) counter.recordCompleted();
                    } catch (RuntimeException e) {
                      if (counter != null) counter.recordFailed();
                      LOGGER.error(
                          "Error pre-loading bounding box tile: {}.",
                          tile,
                          e.getCause() != null ? e.getCause() : e);
                    } finally {
                      permits.release();
                    }
                  });
            }
          }
        }
      }
      // Drain: once all permits are reacquirable, every submitted fetch has finished.
      permits.acquire(concurrency);
      permits.release(concurrency);
      LOGGER.info("Finished xyz preload for layers {}.", layers);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Preload interrupted", e);
    } finally {
      inflightXyzPreloads.decrementAndGet();
    }
  }

  private static String errorMessage(Throwable throwable) {
    if (throwable == null) {
      return null;
    }
    Throwable cause =
        throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
    String message = cause.getMessage();
    return message == null || message.isBlank() ? cause.toString() : message;
  }

  /**
   * Counts down the halves of one preload — a raster pass, a PMTiles download, or both — so that a
   * mixed job only reaches a terminal status once every half has reported. The first error wins;
   * the whole job fails if any half does.
   */
  private final class JobTracker {
    private final Preload preload;
    private final AtomicInteger remaining;
    private final AtomicReference<String> firstError = new AtomicReference<>();

    JobTracker(Preload preload, int parts) {
      this.preload = preload;
      this.remaining = new AtomicInteger(parts);
    }

    void partFinished(String error) {
      if (error != null) {
        firstError.compareAndSet(null, error);
      }
      if (remaining.decrementAndGet() > 0) {
        // The other half is still working: keep whatever this one recorded (tile counts) on disk,
        // but leave the job RUNNING.
        persist(preload);
        return;
      }
      String failure = firstError.get();
      updateStatus(preload, failure == null ? Preload.Status.DONE : Preload.Status.FAILED, failure);
    }
  }

  /** Mutable tile tally for one in-flight raster preload. */
  private static final class ProgressCounter {
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    void setTotal(long value) {
      total.set(value);
    }

    void recordCompleted() {
      completed.incrementAndGet();
    }

    void recordFailed() {
      failed.incrementAndGet();
    }

    PreloadProgress snapshot() {
      return PreloadProgress.of(total.get(), completed.get(), failed.get());
    }
  }

  private static String displayName(String requested, BoundingBox bbox, int maxZoom) {
    if (requested != null && !requested.isBlank()) {
      return requested.trim();
    }
    return String.format(
        Locale.US,
        "bbox_%.4f_%.4f_%.4f_%.4f_z%d",
        bbox.getWest(),
        bbox.getSouth(),
        bbox.getEast(),
        bbox.getNorth(),
        maxZoom);
  }
}
