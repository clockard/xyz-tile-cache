package org.lockard.xyztilecache.model;

public record LayerChangedEvent(String layerName, Kind kind) {

  public enum Kind {
    ADDED,
    UPDATED_SOURCE,
    UPDATED_ACL,
    REMOVED
  }

  public LayerChangedEvent(String layerName) {
    this(layerName, Kind.UPDATED_SOURCE);
  }
}
