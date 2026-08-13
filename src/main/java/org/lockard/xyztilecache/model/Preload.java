package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Preload {

  public enum Status {
    PENDING,
    RUNNING,
    DONE,
    FAILED
  }

  private String id;
  private String name;
  private BoundingBox boundingBox;
  private int maxZoom;
  private List<String> layers = new ArrayList<>();
  private Instant createdAt;
  private List<String> allowedUsers = new ArrayList<>();
  private List<String> allowedGroups = new ArrayList<>();
  private Status status = Status.PENDING;
  private String errorMessage;
  private Instant startedAt;
  private Instant finishedAt;
  private long totalTiles;
  private long completedTiles;
  private long failedTiles;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public BoundingBox getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(BoundingBox boundingBox) {
    this.boundingBox = boundingBox;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public List<String> getLayers() {
    return layers;
  }

  public void setLayers(List<String> layers) {
    this.layers = layers == null ? new ArrayList<>() : layers;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public List<String> getAllowedUsers() {
    return allowedUsers;
  }

  public void setAllowedUsers(List<String> allowedUsers) {
    this.allowedUsers = allowedUsers == null ? new ArrayList<>() : allowedUsers;
  }

  public List<String> getAllowedGroups() {
    return allowedGroups;
  }

  public void setAllowedGroups(List<String> allowedGroups) {
    this.allowedGroups = allowedGroups == null ? new ArrayList<>() : allowedGroups;
  }

  @JsonIgnore
  public boolean isPublic() {
    return allowedUsers.isEmpty() && allowedGroups.isEmpty();
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status == null ? Status.PENDING : status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public long getTotalTiles() {
    return totalTiles;
  }

  public void setTotalTiles(long totalTiles) {
    this.totalTiles = totalTiles;
  }

  public long getCompletedTiles() {
    return completedTiles;
  }

  public void setCompletedTiles(long completedTiles) {
    this.completedTiles = completedTiles;
  }

  public long getFailedTiles() {
    return failedTiles;
  }

  public void setFailedTiles(long failedTiles) {
    this.failedTiles = failedTiles;
  }

  /**
   * Applies a status transition along with its timestamp. Kept off {@link #setStatus} so Jackson
   * deserialization of a persisted preload does not rewrite the timestamps it just read.
   */
  @JsonIgnore
  public void markStatus(Status newStatus, String error) {
    Instant now = Instant.now();
    if (newStatus == Status.RUNNING && startedAt == null) {
      startedAt = now;
    }
    if (newStatus == Status.DONE || newStatus == Status.FAILED) {
      if (startedAt == null) {
        startedAt = now;
      }
      finishedAt = now;
    }
    setStatus(newStatus);
    this.errorMessage = error;
  }
}
