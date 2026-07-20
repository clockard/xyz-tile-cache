package org.lockard.xyztilecache.cache;

/**
 * A disk-cached tile exists but is older than the layer's {@code tileExpirationMinutes}. Carries
 * the stale bytes so the online loader can fall back to them when the source is unavailable.
 */
public class TileExpiredException extends Exception {
  private final transient byte[] staleData;

  public TileExpiredException(byte[] staleData) {
    super("Tile expired");
    this.staleData = staleData;
  }

  public byte[] staleData() {
    return staleData;
  }
}
