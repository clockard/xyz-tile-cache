package org.lockard.xyztilecache;

import java.util.Collection;

public record StatsResponse(
    String instanceId,
    long tilesServedByInstance,
    long diskFreeBytes,
    Collection<LayerStats> layers) {

  public record LayerStats(String name, long tilesServedByInstance) {}
}
