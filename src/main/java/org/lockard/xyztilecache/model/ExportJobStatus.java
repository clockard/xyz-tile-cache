package org.lockard.xyztilecache.model;

import java.time.Instant;

public record ExportJobStatus(
    String id, ExportStatus status, String filename, String error, Instant expiresAt) {

  public ExportJobStatus(ExportJob job) {
    this(job.getId(), job.getStatus(), job.getFilename(), job.getError(), null);
  }

  public ExportJobStatus(ExportJob job, Instant expiresAt) {
    this(job.getId(), job.getStatus(), job.getFilename(), job.getError(), expiresAt);
  }
}
