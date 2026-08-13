package org.lockard.xyztilecache.model;

/**
 * Tile counts for a raster preload. {@code completedTiles} counts tiles fetched successfully and
 * {@code failedTiles} those whose fetch errored; both count toward {@code percentComplete} since a
 * failed tile is not retried.
 */
public record PreloadProgress(
    long totalTiles, long completedTiles, long failedTiles, int percentComplete) {

  public static PreloadProgress of(long total, long completed, long failed) {
    long processed = completed + failed;
    int percent = total <= 0 ? 0 : (int) Math.min(100, processed * 100 / total);
    return new PreloadProgress(total, completed, failed, percent);
  }
}
