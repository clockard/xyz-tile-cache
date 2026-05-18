package org.lockard.xyztilecache;

public record ExportJobStatus(String id, ExportStatus status, String filename, String error) {

  ExportJobStatus(ExportJob job) {
    this(job.getId(), job.getStatus(), job.getFilename(), job.getError());
  }
}
