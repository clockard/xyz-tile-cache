package org.lockard.xyztilecache.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.store.PreloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PmtilesDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesDownloader.class);

  private final XyzConfiguration xyzConfig;
  private final VectorPmtilesManager vectorPmtilesManager;
  private final PreloadStore preloadStore;

  private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

  public PmtilesDownloader(
      XyzConfiguration xyzConfig,
      VectorPmtilesManager vectorPmtilesManager,
      PreloadStore preloadStore) {
    this.xyzConfig = xyzConfig;
    this.vectorPmtilesManager = vectorPmtilesManager;
    this.preloadStore = preloadStore;
  }

  public boolean isDownloadInProgress() {
    return downloadInProgress.get();
  }

  public static String outputFilename(String preloadName) {
    return preloadName.replaceAll("[^a-zA-Z0-9_\\-.]", "_") + ".pmtiles";
  }

  /** Starts a download that owns the preload's status from RUNNING through to DONE / FAILED. */
  public CompletableFuture<Void> startDownload(Preload preload, Layer layer) {
    return startDownload(preload, layer, true);
  }

  /**
   * @param ownsTerminalStatus whether this download decides the preload's final status. False when
   *     the preload also has a raster pass running: that job is only finished once both halves are,
   *     so the outcome is reported through the returned future — which completes exceptionally on
   *     failure — and the caller marks the preload DONE or FAILED.
   */
  public CompletableFuture<Void> startDownload(
      Preload preload, Layer layer, boolean ownsTerminalStatus) {
    if (!downloadInProgress.compareAndSet(false, true)) {
      throw new IllegalStateException("A PMTiles download is already in progress");
    }
    return CompletableFuture.runAsync(
        () -> {
          try {
            doDownload(preload, layer);
            if (ownsTerminalStatus) {
              markDone(preload);
            }
          } catch (RuntimeException e) {
            // Safety net: an unexpected error must never leave the preload stuck RUNNING.
            LOGGER.error("PMTiles download failed for preload '{}'", preload.getId(), e);
            if (!ownsTerminalStatus) {
              throw e;
            }
            markFailed(preload, e.getMessage());
          } finally {
            downloadInProgress.set(false);
          }
        });
  }

  /** Runs the extract, throwing {@link DownloadFailedException} if it does not produce a file. */
  private void doDownload(Preload preload, Layer layer) {
    String layerId = layer.effectiveId();
    Path downloadDir =
        Path.of(xyzConfig.getBaseTileDirectory(), layerId).toAbsolutePath().normalize();
    Path outputPath = downloadDir.resolve(outputFilename(preload.getName())).normalize();
    if (!outputPath.startsWith(downloadDir)) {
      throw new DownloadFailedException(
          "PMTiles filename escapes download directory: " + outputPath);
    }

    try {
      Files.createDirectories(outputPath.getParent());
    } catch (IOException e) {
      throw new DownloadFailedException("Could not create download directory: " + e.getMessage());
    }

    markRunning(preload);

    String sourceUrl = resolveSourceUrl(layer.urlTemplate());
    LOGGER.info("Starting pmtiles extract: source={} output={}", sourceUrl, outputPath);

    Process process;
    try {
      process = buildProcess(preload, layer, outputPath).start();
    } catch (IOException e) {
      throw new DownloadFailedException(
          "Could not start pmtiles extract process: " + e.getMessage());
    }

    String output;
    try {
      output = new String(process.getInputStream().readAllBytes());
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        Files.deleteIfExists(outputPath);
        throw new DownloadFailedException(
            "pmtiles extract failed (exit "
                + exitCode
                + "): "
                + (output.isBlank() ? "(no output)" : output.trim()));
      }
    } catch (IOException | InterruptedException e) {
      try {
        Files.deleteIfExists(outputPath);
      } catch (IOException ignored) {
      }
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new DownloadFailedException("Error waiting for pmtiles extract: " + e.getMessage());
    }

    LOGGER.info("PMTiles download completed: {}", outputPath);
    vectorPmtilesManager.closeLayer(layerId);
    vectorPmtilesManager.initLayer(layer);
  }

  /** A download that failed for a reported reason, as opposed to an unexpected bug. */
  static class DownloadFailedException extends RuntimeException {
    DownloadFailedException(String message) {
      super(message);
    }
  }

  private void markRunning(Preload preload) {
    updateStatus(preload, Preload.Status.RUNNING, null);
  }

  private void markDone(Preload preload) {
    updateStatus(preload, Preload.Status.DONE, null);
  }

  private void markFailed(Preload preload, String message) {
    updateStatus(preload, Preload.Status.FAILED, message);
  }

  private void updateStatus(Preload preload, Preload.Status status, String errorMessage) {
    preload.markStatus(status, errorMessage);
    try {
      preloadStore.update(preload);
    } catch (IOException e) {
      LOGGER.warn(
          "Failed to persist preload status {} for '{}': {}",
          status,
          preload.getId(),
          e.getMessage());
    } catch (NoSuchElementException e) {
      LOGGER.debug("Preload '{}' no longer present; skipping status update", preload.getId());
    }
  }

  protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path outputPath) {
    BoundingBox bbox = requireValidBoundingBox(preload.getBoundingBox());
    // The preload's maxZoom reflects the whole (possibly mixed) job; the raster pass honors it
    // as-is. The vector extract must not be asked for zoom levels beyond the vector layer's own
    // maxZoom, so cap independently here rather than clamping the shared job zoom on the client.
    int maxZoom = Math.min(requireValidMaxZoom(preload.getMaxZoom()), layer.maxZoom());
    String bboxArg =
        String.format(
            Locale.US,
            "%f,%f,%f,%f",
            bbox.getWest(),
            bbox.getSouth(),
            bbox.getEast(),
            bbox.getNorth());
    String sourceUrl = resolveSourceUrl(layer.urlTemplate());
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
