package org.lockard.xyztilecache.model;

import java.util.Collection;

/**
 * Instance statistics. Serve counters are per-instance and reset on restart; the cached-tile totals
 * describe the shared tile directory and survive restarts. Totals cover only the layers the caller
 * can read, so they match the {@code layers} array rather than the whole cache.
 */
public record StatsResponse(
    String instanceId,
    long tilesServedByInstance,
    long cachedTiles,
    long cachedBytes,
    long diskFreeBytes,
    Collection<LayerStats> layers) {

  public record LayerStats(
      String name, long tilesServedByInstance, long cachedTiles, long cachedBytes) {}
}
