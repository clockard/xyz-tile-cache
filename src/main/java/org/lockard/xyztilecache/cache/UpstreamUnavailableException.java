package org.lockard.xyztilecache.cache;

import java.io.IOException;

/** Signals that the upstream tile source is temporarily unavailable (circuit-broken). */
public class UpstreamUnavailableException extends IOException {
  public UpstreamUnavailableException(String message) {
    super(message);
  }
}
