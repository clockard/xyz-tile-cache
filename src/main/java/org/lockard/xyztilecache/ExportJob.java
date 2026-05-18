package org.lockard.xyztilecache;

import java.nio.file.Path;
import java.time.Instant;

class ExportJob {

  private final String id;
  private volatile ExportStatus status;
  private final String filename;
  private final Path tempFile;
  private final String ownerName;
  private volatile String error;
  private final Instant createdAt;

  ExportJob(String id, String filename, Path tempFile, String ownerName) {
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

  ExportStatus getStatus() {
    return status;
  }

  void setStatus(ExportStatus status) {
    this.status = status;
  }

  String getFilename() {
    return filename;
  }

  Path getTempFile() {
    return tempFile;
  }

  String getOwnerName() {
    return ownerName;
  }

  String getError() {
    return error;
  }

  void setError(String error) {
    this.error = error;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}
