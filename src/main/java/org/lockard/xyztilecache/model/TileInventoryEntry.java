package org.lockard.xyztilecache.model;

/**
 * One layer's persisted disk totals, as stored in {@code tile-inventory.json}.
 *
 * <p>{@code updatedAt} is an ISO-8601 string rather than an {@code Instant} so the file stays
 * readable without requiring a Jackson time module on the mapper.
 */
public record TileInventoryEntry(String layerId, long tiles, long bytes, String updatedAt) {}
