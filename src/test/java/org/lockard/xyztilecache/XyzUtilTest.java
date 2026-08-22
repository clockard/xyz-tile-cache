package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.awt.Point;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.model.BoundingBox;

class XyzUtilTest {

  @Test
  void getTileNumber_equatorAndMeridian() {
    assertThat(XyzUtil.getTileNumber(0.0, 0.0, 1)).isEqualTo(new Point(1, 1));
  }

  @Test
  void getTileNumber_zoomZeroAlwaysOrigin() {
    assertThat(XyzUtil.getTileNumber(45.0, 90.0, 0)).isEqualTo(new Point(0, 0));
  }

  @Test
  void getTileNumber_clampsNegativeXtile() {
    // lon < -180 produces a negative xtile; must clamp to 0
    Point tile = XyzUtil.getTileNumber(0.0, -200.0, 1);
    assertThat(tile.x).isEqualTo(0);
  }

  @Test
  void getTileNumber_clampsOverflowXtile() {
    // lon > 180 at z=1 produces xtile=2 which equals 2^1; clamp to max index 1
    Point tile = XyzUtil.getTileNumber(0.0, 200.0, 1);
    assertThat(tile.x).isEqualTo((1 << 1) - 1);
  }

  @Test
  void getTileNumber_clampsNegativeYtile() {
    // Extreme north latitude drives ytile negative; must clamp to 0
    Point tile = XyzUtil.getTileNumber(86.0, 0.0, 1);
    assertThat(tile.y).isEqualTo(0);
  }

  @Test
  void getTileNumber_clampsOverflowYtile() {
    // Extreme south latitude drives ytile >= 2^zoom; clamp to max index
    Point tile = XyzUtil.getTileNumber(-86.0, 0.0, 1);
    assertThat(tile.y).isEqualTo((1 << 1) - 1);
  }

  @Test
  void tile2lon_leftEdgeIsMinusOneEighty() {
    assertThat(XyzUtil.tile2lon(0, 1)).isEqualTo(-180.0);
  }

  @Test
  void tile2lon_centerTileIsZeroDegrees() {
    // x=1 at z=1 is the antimeridian (0 degrees longitude)
    assertThat(XyzUtil.tile2lon(1, 1)).isEqualTo(0.0);
  }

  @Test
  void tile2lat_topEdgeIsHighLatitude() {
    // y=0 at z=1 is near +85 degrees (Web Mercator north cap)
    assertThat(XyzUtil.tile2lat(0, 1)).isGreaterThan(80.0);
  }

  @Test
  void tile2lat_centerIsNearEquator() {
    // y=1 at z=1 should be very close to 0 degrees
    assertThat(XyzUtil.tile2lat(1, 1)).isCloseTo(0.0, offset(0.1));
  }

  @Test
  void calculateXyTilesForBBox_containsCenterTile() {
    BoundingBox bbox = new BoundingBox();
    bbox.setNorth(1.0);
    bbox.setSouth(-1.0);
    bbox.setEast(1.0);
    bbox.setWest(-1.0);

    Set<Point> tiles = XyzUtil.calculateXyTilesForBBox(bbox, 1);

    // Small box around origin at z=1 — the centre tile (1,1) must be present
    assertThat(tiles).contains(new Point(1, 1)).isNotEmpty();
  }

  @Test
  void calculateBboxRanges_oneRangePerZoomLevel() {
    BoundingBox bbox = new BoundingBox();
    bbox.setNorth(1.0);
    bbox.setSouth(-1.0);
    bbox.setEast(1.0);
    bbox.setWest(-1.0);
    bbox.setMaxZoom(3);

    List<XyzUtil.TileRange> result = XyzUtil.calculateBboxRanges(bbox);

    // maxZoom=3 → zoom levels 0,1,2,3 → 4 ranges
    assertThat(result).hasSize(4);
    assertThat(result).allSatisfy(range -> assertThat(range.count()).isPositive());
  }

  @Test
  void calculateTileRange_matchesMaterializedTileSet() {
    BoundingBox bbox = new BoundingBox();
    bbox.setNorth(41.0);
    bbox.setSouth(40.0);
    bbox.setEast(-73.0);
    bbox.setWest(-74.5);

    for (int zoom = 0; zoom <= 8; zoom++) {
      XyzUtil.TileRange range = XyzUtil.calculateTileRange(bbox, zoom);
      Set<Point> expected = XyzUtil.calculateXyTilesForBBox(bbox, zoom);
      Set<Point> fromRange = new java.util.HashSet<>();
      for (int x = range.xMin(); x <= range.xMax(); x++) {
        for (int y = range.yMin(); y <= range.yMax(); y++) {
          fromRange.add(new Point(x, y));
        }
      }
      assertThat(fromRange).isEqualTo(expected);
      assertThat(range.count()).isEqualTo(expected.size());
    }
  }

  // ── tileBounds3857 ─────────────────────────────────────────────────────────

  private static final double ORIGIN = 20037508.342789244;
  private static final double TOLERANCE = 1e-6;

  @Test
  void tileBounds3857_zeroZoom_coversTheWholeWorld() {
    XyzUtil.Bounds3857 b = XyzUtil.tileBounds3857(0, 0, 0);

    assertThat(b.minX()).isCloseTo(-ORIGIN, offset(TOLERANCE));
    assertThat(b.minY()).isCloseTo(-ORIGIN, offset(TOLERANCE));
    assertThat(b.maxX()).isCloseTo(ORIGIN, offset(TOLERANCE));
    assertThat(b.maxY()).isCloseTo(ORIGIN, offset(TOLERANCE));
  }

  @Test
  void tileBounds3857_zoomOne_splitsTheWorldIntoQuadrants() {
    // y grows southward from the north-west origin, so tile (0,0) is the north-west quadrant.
    XyzUtil.Bounds3857 northWest = XyzUtil.tileBounds3857(0, 0, 1);
    assertThat(northWest.minX()).isCloseTo(-ORIGIN, offset(TOLERANCE));
    assertThat(northWest.maxX()).isCloseTo(0.0, offset(TOLERANCE));
    assertThat(northWest.minY()).isCloseTo(0.0, offset(TOLERANCE));
    assertThat(northWest.maxY()).isCloseTo(ORIGIN, offset(TOLERANCE));

    XyzUtil.Bounds3857 southEast = XyzUtil.tileBounds3857(1, 1, 1);
    assertThat(southEast.minX()).isCloseTo(0.0, offset(TOLERANCE));
    assertThat(southEast.maxX()).isCloseTo(ORIGIN, offset(TOLERANCE));
    assertThat(southEast.minY()).isCloseTo(-ORIGIN, offset(TOLERANCE));
    assertThat(southEast.maxY()).isCloseTo(0.0, offset(TOLERANCE));
  }

  @Test
  void tileBounds3857_tilesAreSquareAndAbutTheirNeighbours() {
    XyzUtil.Bounds3857 left = XyzUtil.tileBounds3857(4, 6, 4);
    XyzUtil.Bounds3857 right = XyzUtil.tileBounds3857(5, 6, 4);
    XyzUtil.Bounds3857 below = XyzUtil.tileBounds3857(4, 7, 4);

    double span = left.maxX() - left.minX();
    assertThat(left.maxY() - left.minY()).isCloseTo(span, offset(TOLERANCE));
    assertThat(right.minX()).isCloseTo(left.maxX(), offset(TOLERANCE));
    assertThat(below.maxY()).isCloseTo(left.minY(), offset(TOLERANCE));
  }

  @Test
  void tileBounds3857_agreesWithTheDegreeBasedTileMath() {
    // Cross-check against the lat/lon helpers by projecting their output: the two derivations of a
    // tile's extent must land in the same place.
    int x = 37;
    int y = 22;
    int z = 6;
    XyzUtil.Bounds3857 b = XyzUtil.tileBounds3857(x, y, z);

    assertThat(b.minX()).isCloseTo(lonToMetres(XyzUtil.tile2lon(x, z)), offset(1e-3));
    assertThat(b.maxX()).isCloseTo(lonToMetres(XyzUtil.tile2lon(x + 1, z)), offset(1e-3));
    assertThat(b.maxY()).isCloseTo(latToMetres(XyzUtil.tile2lat(y, z)), offset(1e-3));
    assertThat(b.minY()).isCloseTo(latToMetres(XyzUtil.tile2lat(y + 1, z)), offset(1e-3));
  }

  private static double lonToMetres(double lon) {
    return lon * ORIGIN / 180.0;
  }

  private static double latToMetres(double lat) {
    double y = Math.log(Math.tan((90 + lat) * Math.PI / 360)) / (Math.PI / 180);
    return y * ORIGIN / 180.0;
  }
}
