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
import java.util.concurrent.atomic.AtomicReference;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.store.PreloadStore;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PmtilesDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesDownloader.class);

  private final XyzConfiguration xyzConfig;
  private final VectorPmtilesManager vectorPmtilesManager;
  private final PreloadStore preloadStore;
  private final TileInventoryStore inventory;

  private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

  /** The download currently running, so a delete can kill its external process. */
  private final AtomicReference<RunningDownload> running = new AtomicReference<>();

  public PmtilesDownloader(
      XyzConfiguration xyzConfig,
      VectorPmtilesManager vectorPmtilesManager,
      PreloadStore preloadStore,
      TileInventoryStore inventory) {
    this.xyzConfig = xyzConfig;
    this.vectorPmtilesManager = vectorPmtilesManager;
    this.preloadStore = preloadStore;
    this.inventory = inventory;
  }

  public boolean isDownloadInProgress() {
    return downloadInProgress.get();
  }

  /**
   * Kills the extract for {@code preloadId} if it is the one currently running. The process is
   * destroyed, its partial output deleted by the normal failure path, and the download reported as
   * cancelled rather than as a process failure.
   *
   * @return true if a download for that preload was running
   */
  public boolean cancelDownload(String preloadId) {
    RunningDownload current = running.get();
    if (current == null || !current.matches(preloadId)) {
      return false;
    }
    LOGGER.info("Cancelling pmtiles extract for preload '{}'", preloadId);
    current.cancel();
    return true;
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
    // Registered before the async body starts so a cancel arriving in that window is not lost:
    // the process is killed as soon as it is attached.
    RunningDownload current = new RunningDownload(preload.getId());
    running.set(current);
    return CompletableFuture.runAsync(
        () -> {
          try {
            doDownload(preload, layer, current);
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
            running.compareAndSet(current, null);
            downloadInProgress.set(false);
          }
        });
  }

  /** Runs the extract, throwing {@link DownloadFailedException} if it does not produce a file. */
  private void doDownload(Preload preload, Layer layer, RunningDownload current) {
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

    // A re-run under the same preload name replaces an archive already counted, and every failure
    // path below deletes whatever is at outputPath. Both are byte deltas against this size.
    long previousSize = TileInventoryStore.sizeOrMissing(outputPath);

    String sourceUrl = resolveSourceUrl(layer.urlTemplate());
    LOGGER.info("Starting pmtiles extract: source={} output={}", sourceUrl, outputPath);

    Process process;
    try {
      process = buildProcess(preload, layer, outputPath).start();
    } catch (IOException e) {
      throw new DownloadFailedException(
          "Could not start pmtiles extract process: " + e.getMessage());
    }
    current.attach(process);

    String output;
    try {
      output = new String(process.getInputStream().readAllBytes());
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        Files.deleteIfExists(outputPath);
        discardArchive(layerId, previousSize);
        // A killed process exits non-zero; report why it really stopped.
        if (current.isCancelled()) {
          throw new DownloadFailedException("PMTiles download cancelled.");
        }
        throw new DownloadFailedException(
            "pmtiles extract failed (exit "
                + exitCode
                + "): "
                + (output.isBlank() ? "(no output)" : output.trim()));
      }
    } catch (IOException | InterruptedException e) {
      try {
        Files.deleteIfExists(outputPath);
        discardArchive(layerId, previousSize);
      } catch (IOException ignored) {
      }
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Killing the process closes its output stream, so a cancel surfaces here as an IOException.
      if (current.isCancelled()) {
        throw new DownloadFailedException("PMTiles download cancelled.");
      }
      throw new DownloadFailedException("Error waiting for pmtiles extract: " + e.getMessage());
    }

    LOGGER.info("PMTiles download completed: {}", outputPath);
    // The archive holds many tiles in one file: it counts toward the layer's bytes, not its tile
    // count. A zero-exit extract that produced no file leaves nothing to count.
    long archiveSize = TileInventoryStore.sizeOrMissing(outputPath);
    if (archiveSize >= 0) {
      inventory.recordWrite(layerId, 0, archiveSize - Math.max(previousSize, 0));
    } else {
      discardArchive(layerId, previousSize);
    }
    vectorPmtilesManager.closeLayer(layerId);
    vectorPmtilesManager.initLayer(layer);
  }

  /** Removes a deleted archive's bytes from the layer total, if it had been counted. */
  private void discardArchive(String layerId, long previousSize) {
    if (previousSize > 0) {
      inventory.recordWrite(layerId, 0, -previousSize);
    }
  }

  /**
   * A download in flight, with the handle needed to stop it. {@code cancelled} and {@code process}
   * are written in opposite orders by {@link #cancel()} and {@link #attach(Process)}, so whichever
   * happens second sees the other's write and the process is never left running after a cancel.
   */
  private static final class RunningDownload {
    private final String preloadId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Process process;

    RunningDownload(String preloadId) {
      this.preloadId = preloadId;
    }

    boolean matches(String id) {
      return preloadId != null && preloadId.equals(id);
    }

    /** Publishes the started process, killing it immediately if a cancel already arrived. */
    void attach(Process started) {
      process = started;
      if (cancelled.get()) {
        started.destroyForcibly();
      }
    }

    void cancel() {
      cancelled.set(true);
      Process current = process;
      if (current != null) {
        current.destroyForcibly();
      }
    }

    boolean isCancelled() {
      return cancelled.get();
    }
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
