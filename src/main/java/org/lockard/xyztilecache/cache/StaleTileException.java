package org.lockard.xyztilecache.cache;

import java.io.IOException;

/**
 * The upstream source is unavailable but an expired disk-cached tile exists. Thrown (rather than
 * returned) so the stale bytes are served to the client without being promoted into the in-memory
 * cache — the next request retries the source instead of pinning stale data.
 */
public class StaleTileException extends IOException {
  private final transient byte[] staleData;

  public StaleTileException(byte[] staleData, Throwable cause) {
    super("Serving stale tile: upstream unavailable", cause);
    this.staleData = staleData;
  }

  public byte[] staleData() {
    return staleData;
  }
}
