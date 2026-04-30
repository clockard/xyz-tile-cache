package org.lockard.xyztilecache;

import com.google.common.cache.LoadingCache;
import java.awt.Point;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PreloadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PreloadService.class);

  private final XyzConfiguration xyzConfiguration;
  private final VectorConfiguration vectorConfiguration;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final PreloadStore preloadStore;
  private final PmtilesDownloader pmtilesDownloader;

  private final ExecutorService xyzExecutor = Executors.newSingleThreadExecutor();
  private volatile Future<?> xyzFuture;

  public PreloadService(
      XyzConfiguration xyzConfiguration,
      VectorConfiguration vectorConfiguration,
      LoadingCache<Tile, byte[]> tileCache,
      PreloadStore preloadStore,
      PmtilesDownloader pmtilesDownloader) {
    this.xyzConfiguration = xyzConfiguration;
    this.vectorConfiguration = vectorConfiguration;
    this.tileCache = tileCache;
    this.preloadStore = preloadStore;
    this.pmtilesDownloader = pmtilesDownloader;
  }

  /**
   * Records a preload entry and dispatches the work. Throws {@link IllegalStateException} if a
   * vector download is requested but one is already in progress, or {@link
   * IllegalArgumentException} if vector is requested without a configured download directory.
   */
  public Preload submit(
      String name, BoundingBox boundingBox, int maxZoom, Set<String> layers, boolean includeVector)
      throws IOException {
    if (includeVector) {
      String dir = vectorConfiguration.getDownloadDirectory();
      if (dir == null || dir.isBlank()) {
        throw new IllegalArgumentException("xyz.vector.downloadDirectory is not configured");
      }
      if (pmtilesDownloader.isDownloadInProgress()) {
        throw new IllegalStateException("A vector download is already in progress");
      }
    }

    Set<String> validLayers =
        layers == null
            ? Set.of()
            : layers.stream()
                .filter(xyzConfiguration.getLayers()::containsKey)
                .collect(Collectors.toSet());

    if (validLayers.isEmpty() && !includeVector) {
      return null;
    }

    Preload preload = new Preload();
    preload.setId(UUID.randomUUID().toString());
    preload.setName(displayName(name, boundingBox, maxZoom));
    preload.setBoundingBox(boundingBox);
    preload.setMaxZoom(maxZoom);
    preload.setLayers(List.copyOf(validLayers));
    preload.setIncludesVector(includeVector);
    preload.setCreatedAt(Instant.now());
    if (includeVector) {
      preload.setPmtilesFilename(safeName(preload.getName()) + ".pmtiles");
    }

    preloadStore.addPreload(preload);

    if (!validLayers.isEmpty()) {
      submitXyz(validLayers, boundingBox);
    }
    if (includeVector) {
      try {
        pmtilesDownloader.startDownload(preload);
      } catch (RuntimeException e) {
        // best-effort: leave the preload entry in place for visibility, but log
        LOGGER.error("Failed to start vector download for preload {}", preload.getId(), e);
        throw e;
      }
    }
    return preload;
  }

  /** Synchronous xyz preload used by startup bounding-box initialization. */
  public void preloadXyzTiles(Set<String> layerNames, BoundingBox bbox) {
    Set<String> validLayers =
        layerNames.stream()
            .filter(xyzConfiguration.getLayers()::containsKey)
            .collect(Collectors.toSet());
    if (validLayers.isEmpty()) return;
    runXyzPreload(validLayers, bbox);
  }

  private void submitXyz(Set<String> layers, BoundingBox bbox) {
    if (xyzFuture != null && !xyzFuture.isDone()) {
      LOGGER.info("Skipping xyz preload dispatch — a previous preload is still running.");
      return;
    }
    xyzFuture = xyzExecutor.submit(() -> runXyzPreload(layers, bbox));
  }

  private void runXyzPreload(Set<String> layers, BoundingBox bbox) {
    List<Set<Point>> allPoints = XyzUtil.calculateAllBboxTiles(bbox);
    for (String layerName : layers) {
      Layer layer = xyzConfiguration.getLayers().get(layerName);
      if (layer == null) continue;
      for (int z = 0; z < allPoints.size(); z++) {
        for (Point p : allPoints.get(z)) {
          Tile tile = new Tile(layer, p.x, p.y, z);
          try {
            tileCache.get(tile);
          } catch (ExecutionException e) {
            LOGGER.error("Error pre-loading bounding box tile: {}.", tile, e.getCause());
          }
        }
      }
    }
    LOGGER.info("Finished xyz preload for layers {}.", layers);
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

  private static String safeName(String displayName) {
    return displayName.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
