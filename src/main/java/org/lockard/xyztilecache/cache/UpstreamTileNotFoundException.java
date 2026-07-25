package org.lockard.xyztilecache.cache;

import java.io.IOException;

/**
 * The upstream source answered but has no tile at the requested coordinates (HTTP 404 or an empty
 * body). This is a normal condition for tiles outside a source's coverage and must not count
 * against the layer's circuit breaker.
 */
public class UpstreamTileNotFoundException extends IOException {
  public UpstreamTileNotFoundException(String message) {
    super(message);
  }
}
