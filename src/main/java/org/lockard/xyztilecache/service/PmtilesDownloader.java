package org.lockard.xyztilecache.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PmtilesDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesDownloader.class);

  private final XyzConfiguration xyzConfig;
  private final VectorPmtilesManager vectorPmtilesManager;

  private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

  public PmtilesDownloader(XyzConfiguration xyzConfig, VectorPmtilesManager vectorPmtilesManager) {
    this.xyzConfig = xyzConfig;
    this.vectorPmtilesManager = vectorPmtilesManager;
  }

  public boolean isDownloadInProgress() {
    return downloadInProgress.get();
  }

  public CompletableFuture<Void> startDownload(Preload preload, Layer layer) {
    if (preload.getPmtilesFilename() == null || preload.getPmtilesFilename().isBlank()) {
      throw new IllegalArgumentException("Preload is missing pmtilesFilename");
    }
    if (!downloadInProgress.compareAndSet(false, true)) {
      throw new IllegalStateException("A PMTiles download is already in progress");
    }
    return CompletableFuture.runAsync(
        () -> {
          try {
            doDownload(preload, layer);
          } finally {
            downloadInProgress.set(false);
          }
        });
  }

  private void doDownload(Preload preload, Layer layer) {
    String layerId = layer.getEffectiveId();
    Path downloadDir =
        Path.of(xyzConfig.getBaseTileDirectory(), layerId).toAbsolutePath().normalize();
    Path outputPath = downloadDir.resolve(preload.getPmtilesFilename()).normalize();
    if (!outputPath.startsWith(downloadDir)) {
      LOGGER.error("PMTiles filename escapes download directory: {}", preload.getPmtilesFilename());
      return;
    }

    try {
      Files.createDirectories(outputPath.getParent());
    } catch (IOException e) {
      LOGGER.error("Could not create download directory: {}", e.getMessage());
      return;
    }

    String sourceUrl = resolveSourceUrl(layer.getUrlTemplate());
    LOGGER.info("Starting pmtiles extract: source={} output={}", sourceUrl, outputPath);

    Process process;
    try {
      process = buildProcess(preload, layer, outputPath).start();
    } catch (IOException e) {
      LOGGER.error("Could not start pmtiles extract process: {}", e.getMessage());
      return;
    }

    String output;
    try {
      output = new String(process.getInputStream().readAllBytes());
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        LOGGER.error(
            "pmtiles extract failed (exit {}): {}",
            exitCode,
            output.isBlank() ? "(no output)" : output);
        Files.deleteIfExists(outputPath);
        return;
      }
    } catch (IOException | InterruptedException e) {
      LOGGER.error("Error waiting for pmtiles extract: {}", e.getMessage());
      try {
        Files.deleteIfExists(outputPath);
      } catch (IOException ignored) {
      }
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return;
    }

    LOGGER.info("PMTiles download completed: {}", outputPath);
    vectorPmtilesManager.closeLayer(layerId);
    vectorPmtilesManager.initLayer(layer);
  }

  protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path outputPath) {
    BoundingBox bbox = requireValidBoundingBox(preload.getBoundingBox());
    int maxZoom = requireValidMaxZoom(preload.getMaxZoom());
    String bboxArg =
        String.format(
            Locale.US,
            "%f,%f,%f,%f",
            bbox.getWest(),
            bbox.getSouth(),
            bbox.getEast(),
            bbox.getNorth());
    String sourceUrl = resolveSourceUrl(layer.getUrlTemplate());
    ProcessBuilder pb =
        new ProcessBuilder(
            "pmtiles",
            "extract",
            sourceUrl,
            safePathArg(outputPath.toAbsolutePath().normalize()),
            "--bbox=" + bboxArg,
            "--maxzoom=" + maxZoom);
    pb.redirectErrorStream(true);
    return pb;
  }

  static String safePathArg(Path path) {
    String s = path.toString();
    if (!s.matches("[a-zA-Z0-9/._-]+")) {
      throw new IllegalArgumentException("Path contains unsafe characters: " + s);
    }
    return s;
  }

  static BoundingBox requireValidBoundingBox(BoundingBox bbox) {
    if (bbox == null) {
      throw new IllegalArgumentException("boundingBox is required");
    }
    double west = bbox.getWest();
    double south = bbox.getSouth();
    double east = bbox.getEast();
    double north = bbox.getNorth();
    if (!Double.isFinite(west)
        || !Double.isFinite(south)
        || !Double.isFinite(east)
        || !Double.isFinite(north)) {
      throw new IllegalArgumentException("boundingBox contains non-finite values");
    }
    if (west < -180 || east > 180 || south < -90 || north > 90 || west >= east || south >= north) {
      throw new IllegalArgumentException("boundingBox is out of range");
    }
    return bbox;
  }

  static int requireValidMaxZoom(int maxZoom) {
    if (maxZoom < 0 || maxZoom > 22) {
      throw new IllegalArgumentException("maxZoom out of supported range");
    }
    return maxZoom;
  }

  static String resolveSourceUrl(String sourceUrl) {
    if (sourceUrl == null || !sourceUrl.contains("{date}")) {
      return sourceUrl;
    }
    String date = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
    return sourceUrl.replace("{date}", date);
  }
}
