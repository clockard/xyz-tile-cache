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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.lockard.xyztilecache.XyzUtil;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.PreloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    if (!rasterLayers.isEmpty()) {
      boundingBox.setMaxZoom(maxZoom);
      submitXyz(preload, rasterLayers, boundingBox, vectorLayer == null);
    }
    if (vectorLayer != null) {
      try {
        pmtilesDownloader.startDownload(preload, vectorLayer);
      } catch (RuntimeException e) {
        LOGGER.error("Failed to start vector download for preload {}", preload.getId(), e);
        throw e;
      }
    }
    return preload;
  }

  /** Synchronous xyz preload used by startup bounding-box initialization. */
  public void preloadXyzTiles(Set<String> layerNames, BoundingBox bbox) {
    Set<String> validLayers =
        layerNames.stream().filter(layerStore.getLayers()::containsKey).collect(Collectors.toSet());
    if (validLayers.isEmpty()) return;
    runXyzPreload(validLayers, bbox);
  }

  private void submitXyz(
      Preload preload, Set<String> layers, BoundingBox bbox, boolean ownsLifecycle) {
    xyzExecutor.submit(() -> runXyzPreload(preload, layers, bbox, ownsLifecycle));
  }

  /**
   * When a PMTiles download runs in parallel it owns the preload's status lifecycle; the raster
   * pass then only records failures.
   */
  private void runXyzPreload(
      Preload preload, Set<String> layers, BoundingBox bbox, boolean ownsLifecycle) {
    if (ownsLifecycle) {
      updateRasterStatus(preload, Preload.Status.RUNNING, null);
    }
    try {
      runXyzPreload(layers, bbox);
      if (ownsLifecycle) {
        updateRasterStatus(preload, Preload.Status.DONE, null);
      }
    } catch (RuntimeException e) {
      updateRasterStatus(preload, Preload.Status.FAILED, e.getMessage());
      throw e;
    }
  }

  private void updateRasterStatus(Preload preload, Preload.Status status, String errorMessage) {
    if (preload == null) return;
    preload.setStatus(status);
    preload.setErrorMessage(errorMessage);
    try {
      preloadStore.update(preload);
    } catch (IOException e) {
      LOGGER.warn(
          "Failed to persist preload status {} for '{}': {}",
          status,
          preload.getId(),
          e.getMessage());
    } catch (java.util.NoSuchElementException e) {
      LOGGER.debug("Preload '{}' no longer present; skipping status update", preload.getId());
    }
  }

  private void runXyzPreload(Set<String> layers, BoundingBox bbox) {
    inflightXyzPreloads.incrementAndGet();
    int concurrency = Math.max(1, configuration.getPreloadConcurrency());
    Semaphore permits = new Semaphore(concurrency);
    try {
      // Iterate ranges lazily: materializing per-tile collections grows ~4^maxZoom and can
      // exhaust the heap for large bboxes. The semaphore bounds in-flight fetches so a preload
      // is parallel without hammering the source.
      List<XyzUtil.TileRange> ranges = XyzUtil.calculateBboxRanges(bbox);
      for (String layerName : layers) {
        Layer layer = layerStore.getLayers().get(layerName);
        if (layer == null || layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) continue;
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
                    } catch (RuntimeException e) {
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
