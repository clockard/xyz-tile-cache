package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.awt.Point;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
  void calculateAllBboxTiles_oneSetPerZoomLevel() {
    BoundingBox bbox = new BoundingBox();
    bbox.setNorth(1.0);
    bbox.setSouth(-1.0);
    bbox.setEast(1.0);
    bbox.setWest(-1.0);
    bbox.setMaxZoom(3);

    List<Set<Point>> result = XyzUtil.calculateAllBboxTiles(bbox);

    // maxZoom=3 → zoom levels 0,1,2,3 → 4 sets
    assertThat(result).hasSize(4);
    assertThat(result).allSatisfy(set -> assertThat(set).isNotEmpty());
  }
}
