package org.lockard.xyztilecache.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.awt.Point;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.lockard.xyztilecache.XyzUtil;
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

  private final ExecutorService xyzExecutor = Executors.newSingleThreadExecutor();
  private final AtomicInteger inflightXyzPreloads = new AtomicInteger();

  public PreloadService(
      LayerStore layerStore,
      LoadingCache<Tile, byte[]> tileCache,
      PreloadStore preloadStore,
      PmtilesDownloader pmtilesDownloader,
      MeterRegistry meterRegistry) {
    this.layerStore = layerStore;
    this.tileCache = tileCache;
    this.preloadStore = preloadStore;
    this.pmtilesDownloader = pmtilesDownloader;
    this.meterRegistry = meterRegistry;
  }

  @PostConstruct
  void registerMetrics() {
    Gauge.builder(INFLIGHT_GAUGE, inflightXyzPreloads, AtomicInteger::get)
        .description("Number of in-flight xyz preload jobs.")
        .register(meterRegistry);
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

    if (!validLayers.isEmpty()) {
      boundingBox.setMaxZoom(maxZoom);
      submitXyz(preload, validLayers, boundingBox);
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

  private void submitXyz(Set<String> layers, BoundingBox bbox) {
    xyzExecutor.submit(() -> runXyzPreload(layers, bbox));
  }

  private void submitXyz(Preload preload, Set<String> layers, BoundingBox bbox) {
    xyzExecutor.submit(() -> runXyzPreload(preload, layers, bbox));
  }

  private void runXyzPreload(Preload preload, Set<String> layers, BoundingBox bbox) {
    updateRasterStatus(preload, Preload.Status.RUNNING, null);
    try {
      runXyzPreload(layers, bbox);
      // Leave PENDING/RUNNING if a parallel PMTiles download owns the lifecycle.
      if (preload != null && hasOnlyRasterLayers(layers)) {
        updateRasterStatus(preload, Preload.Status.DONE, null);
      }
    } catch (RuntimeException e) {
      updateRasterStatus(preload, Preload.Status.FAILED, e.getMessage());
      throw e;
    }
  }

  private boolean hasOnlyRasterLayers(Set<String> layers) {
    return layers.stream()
        .map(layerStore.getLayers()::get)
        .filter(java.util.Objects::nonNull)
        .noneMatch(l -> l.sourceType() == Layer.SourceType.VECTOR_PMTILES);
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
    try {
      List<Set<Point>> allPoints = XyzUtil.calculateAllBboxTiles(bbox);
      for (String layerName : layers) {
        Layer layer = layerStore.getLayers().get(layerName);
        if (layer == null || layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) continue;
        for (int z = 0; z < allPoints.size(); z++) {
          for (Point p : allPoints.get(z)) {
            Tile tile = new Tile(layer, p.x, p.y, z);
            try {
              tileCache.get(tile);
            } catch (CompletionException e) {
              LOGGER.error("Error pre-loading bounding box tile: {}.", tile, e.getCause());
            }
          }
        }
      }
      LOGGER.info("Finished xyz preload for layers {}.", layers);
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
