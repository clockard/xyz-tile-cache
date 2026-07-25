package org.lockard.xyztilecache.model;

/**
 * Cache key for the in-memory tile cache. Keyed by the layer's effective id — not the {@link Layer}
 * record itself — so layer updates (ACLs, attribution, …) don't orphan existing cache entries.
 * Consumers resolve the current {@link Layer} from the {@code LayerStore} at load time.
 */
public record Tile(String layerId, int x, int y, int z) {}
