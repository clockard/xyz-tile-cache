package org.lockard.xyztilecache.store;

/**
 * A layer with the same id already exists. Extends {@link IllegalArgumentException} so existing
 * catch sites keep working; controllers catch this subtype first to answer 409 instead of 400.
 */
public class LayerAlreadyExistsException extends IllegalArgumentException {
  public LayerAlreadyExistsException(String message) {
    super(message);
  }
}
