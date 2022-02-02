package org.lockard.xyztilecache;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XyzUtil {
  public static List<Set<Point>> calculateAllBboxTiles(BoundingBox bbox) {
    List<Set<Point>> allBboxTiles = new ArrayList<>();
    for (int i = 0; i <= bbox.getMaxZoom(); i++) {
      allBboxTiles.add(XyzUtil.calculateXyTilesForBBox(bbox, i));
    }
    return allBboxTiles;
  }

  public static Set<Point> calculateXyTilesForBBox(BoundingBox bbox, int zoom) {
    Set<Point> points = new HashSet<>();
    Point upperLeft = getTileNumber(bbox.getNorth(), bbox.getWest(), zoom);
    Point lowerLeft = getTileNumber(bbox.getSouth(), bbox.getWest(), zoom);
    Point upperRight = getTileNumber(bbox.getNorth(), bbox.getEast(), zoom);
    Point lowerRight = getTileNumber(bbox.getSouth(), bbox.getEast(), zoom);
    for (int i = upperLeft.x; i <= upperRight.x; i++) {
      for (int n = upperLeft.y; n <= lowerLeft.y; n++) {
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

  public static double tile2lon(int x, int z) {
    return x / Math.pow(2.0, z) * 360.0 - 180;
  }

  public static double tile2lat(int y, int z) {
    double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }
}
