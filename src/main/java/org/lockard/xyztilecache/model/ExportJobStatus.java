package org.lockard.xyztilecache.model;

public record ExportJobStatus(String id, ExportStatus status, String filename, String error) {

  public ExportJobStatus(ExportJob job) {
    this(job.getId(), job.getStatus(), job.getFilename(), job.getError());
  }
}
