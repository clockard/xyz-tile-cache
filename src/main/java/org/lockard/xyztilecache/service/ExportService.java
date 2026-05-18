package org.lockard.xyztilecache.service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.ExportJob;
import org.lockard.xyztilecache.model.ExportJobStatus;
import org.lockard.xyztilecache.model.ExportStatus;
import org.lockard.xyztilecache.model.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExportService.class);
  private static final DateTimeFormatter FILENAME_TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final ImportExportService importExportService;

  private final ConcurrentHashMap<String, ExportJob> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "export-worker");
            t.setDaemon(true);
            return t;
          });

  public ExportService(ImportExportService importExportService) {
    this.importExportService = importExportService;
  }

  /**
   * Enqueues an export job and returns its initial status. The job runs asynchronously; callers
   * poll {@link #getJob(String)} until the status transitions to DONE or FAILED.
   */
  public ExportJobStatus submit(
      List<Layer> layers,
      BoundingBox bbox,
      Integer minZoom,
      Integer maxZoom,
      boolean includeVector,
      String ownerName)
      throws IOException {
    String jobId = UUID.randomUUID().toString();
    String filename = "tile-export-" + FILENAME_TS.format(Instant.now()) + ".zip";
    Path tempFile = Files.createTempFile("tile-export-", ".zip");
    ExportJob job = new ExportJob(jobId, filename, tempFile, ownerName);
    jobs.put(jobId, job);

    List<Layer> resolvedLayers = List.copyOf(layers);
    executor.submit(
        () -> {
          job.setStatus(ExportStatus.RUNNING);
          try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
            importExportService.streamExport(
                resolvedLayers, bbox, minZoom, maxZoom, includeVector, out);
            job.setStatus(ExportStatus.DONE);
          } catch (Exception e) {
            LOGGER.error("Export job {} failed", jobId, e);
            job.setError(e.getMessage());
            job.setStatus(ExportStatus.FAILED);
            try {
              Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
              LOGGER.warn("Failed to delete temp file for failed export job {}", jobId);
            }
          }
        });

    return new ExportJobStatus(job);
  }

  public List<ExportJobStatus> listForOwner(String ownerName) {
    return jobs.values().stream()
        .filter(j -> j.getOwnerName().equals(ownerName))
        .map(ExportJobStatus::new)
        .toList();
  }

  public Optional<ExportJob> getJob(String id) {
    return Optional.ofNullable(jobs.get(id));
  }

  /** Removes the job from the map and deletes the temp file. Returns the path for streaming. */
  public Path claimDownload(String id) {
    ExportJob job = jobs.remove(id);
    if (job == null) {
      return null;
    }
    return job.getTempFile();
  }
}
