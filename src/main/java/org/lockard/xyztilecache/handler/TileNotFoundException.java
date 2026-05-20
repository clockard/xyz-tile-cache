package org.lockard.xyztilecache.handler;

public class TileNotFoundException extends RuntimeException {
  public TileNotFoundException() {}

  public TileNotFoundException(Throwable cause) {
    super(cause);
  }
}
