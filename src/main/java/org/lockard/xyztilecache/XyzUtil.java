package org.lockard.xyztilecache;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lockard.xyztilecache.model.BoundingBox;

public class XyzUtil {
  private XyzUtil() {}

  /**
   * Inclusive tile-coordinate bounds of a bounding box at one zoom level. Tile counts grow ~4^zoom,
   * so callers iterate the ranges lazily instead of materializing per-tile collections.
   */
  public record TileRange(int zoom, int xMin, int xMax, int yMin, int yMax) {
    public long count() {
      return (long) (xMax - xMin + 1) * (yMax - yMin + 1);
    }
  }

  /** One {@link TileRange} per zoom level from 0 through {@code bbox.getMaxZoom()} inclusive. */
  public static List<TileRange> calculateBboxRanges(BoundingBox bbox) {
    List<TileRange> ranges = new ArrayList<>();
    for (int i = 0; i <= bbox.getMaxZoom(); i++) {
      ranges.add(calculateTileRange(bbox, i));
    }
    return ranges;
  }

  public static TileRange calculateTileRange(BoundingBox bbox, int zoom) {
    Point upperLeft = getTileNumber(bbox.getNorth(), bbox.getWest(), zoom);
    Point lowerLeft = getTileNumber(bbox.getSouth(), bbox.getWest(), zoom);
    Point upperRight = getTileNumber(bbox.getNorth(), bbox.getEast(), zoom);
    return new TileRange(zoom, upperLeft.x, upperRight.x, upperLeft.y, lowerLeft.y);
  }

  public static Set<Point> calculateXyTilesForBBox(BoundingBox bbox, int zoom) {
    TileRange range = calculateTileRange(bbox, zoom);
    Set<Point> points = new HashSet<>();
    for (int i = range.xMin(); i <= range.xMax(); i++) {
      for (int n = range.yMin(); n <= range.yMax(); n++) {
        points.add(new Point(i, n));
      }
    }
    return points;
  }

  // The following conversion methods were taken from
  // https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Java
  public static Point getTileNumber(final double lat, final double lon, final int zoom) {
    int xtile = (int) Math.floor((lon + 180) / 360 * (1 << zoom));
    int ytile =
        (int)
            Math.floor(
                (1
                        - Math.log(
                                Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat)))
                            / Math.PI)
                    / 2
                    * (1 << zoom));
    if (xtile < 0) {
      xtile = 0;
    }
    if (xtile >= (1 << zoom)) {
      xtile = ((1 << zoom) - 1);
    }
    if (ytile < 0) {
      ytile = 0;
    }
    if (ytile >= (1 << zoom)) {
      ytile = ((1 << zoom) - 1);
    }
    return new Point(xtile, ytile);
  }

  /**
   * Half the width of the Web Mercator world in metres: the projected x of longitude 180. The
   * EPSG:3857 extent is this value negated through positive on both axes.
   */
  public static final double ORIGIN_SHIFT = Math.PI * 6378137.0;

  /** A tile's extent in EPSG:3857 metres, ordered as a WMS {@code BBOX}. */
  public record Bounds3857(double minX, double minY, double maxX, double maxY) {}

  /**
   * Projected extent of an XYZ tile in EPSG:3857 metres.
   *
   * <p>Computed directly from the tile grid rather than by projecting {@link #tile2lat}/{@link
   * #tile2lon} degrees, so it is exact at every zoom: the Web Mercator tile grid is a plain
   * quadtree over the projected extent, with y increasing downward from the north-west origin.
   */
  public static Bounds3857 tileBounds3857(int x, int y, int z) {
    double span = 2 * ORIGIN_SHIFT / (1 << z);
    double minX = -ORIGIN_SHIFT + x * span;
    double maxY = ORIGIN_SHIFT - y * span;
    return new Bounds3857(minX, maxY - span, minX + span, maxY);
  }

  public static double tile2lon(int x, int z) {
    return x / Math.pow(2.0, z) * 360.0 - 180;
  }

  public static double tile2lat(int y, int z) {
    double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }
}
