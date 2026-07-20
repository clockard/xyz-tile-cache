package org.lockard.xyztilecache.service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.ExportJob;
import org.lockard.xyztilecache.model.ExportJobStatus;
import org.lockard.xyztilecache.model.ExportStatus;
import org.lockard.xyztilecache.model.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExportService.class);
  private static final DateTimeFormatter FILENAME_TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Duration FAILED_RETENTION = Duration.ofHours(24);

  private final ImportExportService importExportService;
  private final XyzConfiguration configuration;
  private final Clock clock;

  private final ConcurrentHashMap<String, ExportJob> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @Autowired
  public ExportService(ImportExportService importExportService, XyzConfiguration configuration) {
    this(importExportService, configuration, Clock.systemUTC());
  }

  ExportService(
      ImportExportService importExportService, XyzConfiguration configuration, Clock clock) {
    this.importExportService = importExportService;
    this.configuration = configuration;
    this.clock = clock;
  }

  /**
   * Enqueues an export job and returns its initial status. The job runs asynchronously; callers
   * poll {@link #getJob(String)} until the status transitions to DONE or FAILED.
   */
  public ExportJobStatus submit(
      List<Layer> layers, BoundingBox bbox, Integer minZoom, Integer maxZoom, String ownerName)
      throws IOException {
    String jobId = UUID.randomUUID().toString();
    Instant createdAt = clock.instant();
    String filename = "tile-export-" + FILENAME_TS.format(createdAt) + ".zip";
    Path tempFile = Files.createTempFile("tile-export-", ".zip");
    ExportJob job = new ExportJob(jobId, filename, tempFile, ownerName, createdAt);
    jobs.put(jobId, job);

    List<Layer> resolvedLayers = List.copyOf(layers);
    executor.submit(
        () -> {
          job.setStatus(ExportStatus.RUNNING);
          try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
            importExportService.streamExport(resolvedLayers, bbox, minZoom, maxZoom, out);
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

    return statusFor(job);
  }

  public List<ExportJobStatus> listForOwner(String ownerName) {
    return jobs.values().stream()
        .filter(j -> j.getOwnerName().equals(ownerName))
        .map(this::statusFor)
        .toList();
  }

  public Optional<ExportJob> getJob(String id) {
    return Optional.ofNullable(jobs.get(id));
  }

  public ExportJobStatus statusFor(ExportJob job) {
    return new ExportJobStatus(job, expiresAt(job));
  }

  /** Removes the job from the map and returns the temp file for streaming. */
  public Path claimDownload(String id) {
    ExportJob job = jobs.remove(id);
    if (job == null) {
      return null;
    }
    return job.getTempFile();
  }

  /**
   * Removes finished jobs whose retention window has passed and deletes their temp files. Runs on a
   * fixed delay so abandoned downloads don't accumulate.
   */
  @Scheduled(fixedDelayString = "${xyz.exportSweepSeconds:300}000")
  public void sweepExpired() {
    Instant now = clock.instant();
    Iterator<Map.Entry<String, ExportJob>> it = jobs.entrySet().iterator();
    while (it.hasNext()) {
      ExportJob job = it.next().getValue();
      Duration retention = retentionFor(job.getStatus());
      if (retention == null) {
        continue;
      }
      if (Duration.between(job.getCreatedAt(), now).compareTo(retention) >= 0) {
        it.remove();
        try {
          Files.deleteIfExists(job.getTempFile());
        } catch (IOException e) {
          LOGGER.warn("Failed to delete expired export temp file {}", job.getTempFile(), e);
        }
        LOGGER.info("Swept expired export job {} ({})", job.getId(), job.getStatus());
      }
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
    for (ExportJob job : jobs.values()) {
      try {
        Files.deleteIfExists(job.getTempFile());
      } catch (IOException e) {
        LOGGER.warn("Failed to delete export temp file on shutdown {}", job.getTempFile(), e);
      }
    }
    jobs.clear();
  }

  private Instant expiresAt(ExportJob job) {
    Duration retention = retentionFor(job.getStatus());
    return retention == null ? null : job.getCreatedAt().plus(retention);
  }

  private Duration retentionFor(ExportStatus status) {
    return switch (status) {
      case DONE -> Duration.ofMinutes(configuration.getExportRetentionMinutes());
      case FAILED -> FAILED_RETENTION;
      default -> null;
    };
  }
}
