package org.lockard.xyztilecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PmtilesDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesDownloader.class);

  private final VectorConfiguration config;
  private final VectorTileService vectorTileService;

  private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

  public PmtilesDownloader(VectorConfiguration config, VectorTileService vectorTileService) {
    this.config = config;
    this.vectorTileService = vectorTileService;
  }

  public boolean isDownloadInProgress() {
    return downloadInProgress.get();
  }

  public CompletableFuture<Void> startDownload(Preload preload) {
    if (preload.getPmtilesFilename() == null || preload.getPmtilesFilename().isBlank()) {
      throw new IllegalArgumentException("Preload is missing pmtilesFilename");
    }
    if (!downloadInProgress.compareAndSet(false, true)) {
      throw new IllegalStateException("A PMTiles download is already in progress");
    }
    return CompletableFuture.runAsync(
        () -> {
          try {
            doDownload(preload);
          } finally {
            downloadInProgress.set(false);
          }
        });
  }

  private void doDownload(Preload preload) {
    Path downloadDir = Path.of(config.getDownloadDirectory()).toAbsolutePath().normalize();
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

    LOGGER.info(
        "Starting pmtiles extract: source={} output={}",
        resolveSourceUrl(config.getSourceUrl()),
        outputPath);

    Process process;
    try {
      process = buildProcess(preload, outputPath).start();
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
    try {
      vectorTileService.registerDownload(outputPath);
    } catch (IOException e) {
      LOGGER.error("Downloaded PMTiles file could not be opened: {}", e.getMessage());
    }
  }

  protected ProcessBuilder buildProcess(Preload preload, Path outputPath) {
    BoundingBox bbox = preload.getBoundingBox();
    String bboxArg =
        String.format(
            Locale.US,
            "%f,%f,%f,%f",
            bbox.getWest(),
            bbox.getSouth(),
            bbox.getEast(),
            bbox.getNorth());
    String sourceUrl = resolveSourceUrl(config.getSourceUrl());
    ProcessBuilder pb =
        new ProcessBuilder(
            "pmtiles",
            "extract",
            sourceUrl,
            safePathArg(outputPath.toAbsolutePath().normalize()),
            "--bbox=" + bboxArg,
            "--maxzoom=" + preload.getMaxZoom());
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

  static String resolveSourceUrl(String sourceUrl) {
    if (sourceUrl == null || !sourceUrl.contains("{date}")) {
      return sourceUrl;
    }
    String date = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
    return sourceUrl.replace("{date}", date);
  }
}
