package org.lockard.xyztilecache.model;

import java.nio.file.Path;
import java.time.Instant;

public class ExportJob {

  private final String id;
  private volatile ExportStatus status;
  private final String filename;
  private final Path tempFile;
  private final String ownerName;
  private volatile String error;
  private final Instant createdAt;

  public ExportJob(String id, String filename, Path tempFile, String ownerName) {
    this.id = id;
    this.filename = filename;
    this.tempFile = tempFile;
    this.ownerName = ownerName;
    this.status = ExportStatus.PENDING;
    this.createdAt = Instant.now();
  }

  String getId() {
    return id;
  }

  public ExportStatus getStatus() {
    return status;
  }

  public void setStatus(ExportStatus status) {
    this.status = status;
  }

  public String getFilename() {
    return filename;
  }

  public Path getTempFile() {
    return tempFile;
  }

  public String getOwnerName() {
    return ownerName;
  }

  String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}
