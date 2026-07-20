package org.lockard.xyztilecache.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayerRecordsTest {

  // ── XyzLayer ───────────────────────────────────────────────────────────────

  @Test
  void xyzLayer_buildsUrlWithZYXSubstitution() {
    XyzLayer layer = newXyz("osm", "https://example.com/{z}/{x}/{y}.png", null);
    assertThat(layer.buildUrl(3, 1, 2, null)).isEqualTo("https://example.com/3/1/2.png");
  }

  @Test
  void xyzLayer_buildsUrlWithTimeSubstitution() {
    XyzLayer layer = newXyz("rad", "https://r/{time}/{z}/{x}/{y}.png", "yyyy-MM-dd");
    assertThat(layer.buildUrl(0, 0, 0, "2026-01-15")).isEqualTo("https://r/2026-01-15/0/0/0.png");
  }

  @Test
  void xyzLayer_extensionFromUrl() {
    assertThat(newXyz("a", "https://x/{z}/{x}/{y}.jpg", null).tileFileExtension()).isEqualTo("jpg");
    assertThat(newXyz("a", "https://x/{z}/{x}/{y}.webp", null).tileFileExtension())
        .isEqualTo("webp");
    assertThat(newXyz("a", "https://x/{z}/{x}/{y}.gif", null).tileFileExtension()).isEqualTo("gif");
    assertThat(newXyz("a", "https://x/{z}/{x}/{y}", null).tileFileExtension()).isEqualTo("png");
  }

  @Test
  void xyzLayer_withId_replacesId() {
    XyzLayer layer = newXyz("old", "u", null);
    XyzLayer renamed = layer.withId("new");
    assertThat(renamed.id()).isEqualTo("new");
    assertThat(renamed.name()).isEqualTo(layer.name());
  }

  @Test
  void xyzLayer_sourceTypeIsXyz() {
    assertThat(newXyz("a", "u", null).sourceType()).isEqualTo(Layer.SourceType.XYZ);
  }

  // ── WmtsRestLayer ──────────────────────────────────────────────────────────

  @Test
  void wmtsRestLayer_buildsUrlWithTileMatrixSubstitution() {
    WmtsRestLayer layer =
        new WmtsRestLayer(
            "r",
            "r",
            "https://s/{TileMatrix}/{TileRow}/{TileCol}",
            null,
            22,
            0,
            0,
            List.of(),
            List.of(),
            Map.of(),
            null);
    assertThat(layer.buildUrl(5, 2, 3, null)).isEqualTo("https://s/5/3/2");
  }

  @Test
  void wmtsRestLayer_sourceTypeIsWmtsRest() {
    WmtsRestLayer layer =
        new WmtsRestLayer("r", "r", "u", null, 1, 0, 0, List.of(), List.of(), Map.of(), null);
    assertThat(layer.sourceType()).isEqualTo(Layer.SourceType.WMTS_REST);
    assertThat(layer.withId("x").id()).isEqualTo("x");
  }

  // ── WmtsKvpLayer ───────────────────────────────────────────────────────────

  @Test
  void wmtsKvpLayer_buildsKvpUrl() {
    WmtsKvpLayer layer =
        new WmtsKvpLayer(
            "k",
            "k",
            "https://s/wmts",
            null,
            22,
            0,
            0,
            List.of(),
            List.of(),
            Map.of(),
            "L",
            "EPSG:3857",
            "default",
            "image/jpeg",
            false,
            null);
    String url = layer.buildUrl(5, 1, 2, null);
    assertThat(url).contains("SERVICE=WMTS");
    assertThat(url).contains("LAYER=L");
    assertThat(url).contains("FORMAT=image/jpeg");
    assertThat(url).contains("TILEMATRIX=5");
    assertThat(url).contains("TILEROW=2");
    assertThat(url).contains("TILECOL=1");
  }

  @Test
  void wmtsKvpLayer_appendsTimeWhenEnabled() {
    WmtsKvpLayer layer =
        new WmtsKvpLayer(
            "k",
            "k",
            "https://s/wmts",
            null,
            22,
            0,
            0,
            List.of(),
            List.of(),
            Map.of(),
            "L",
            "EPSG:3857",
            "default",
            "image/png",
            true,
            null);
    assertThat(layer.buildUrl(1, 0, 0, "2026-01-15")).contains("&TIME=2026-01-15");
    assertThat(layer.doesUrlHaveTime()).isTrue();
  }

  @Test
  void wmtsKvpLayer_extensionMatchesFormat() {
    assertThat(wmtsKvpFor("image/jpeg").tileFileExtension()).isEqualTo("jpg");
    assertThat(wmtsKvpFor("image/png").tileFileExtension()).isEqualTo("png");
    assertThat(wmtsKvpFor("image/webp").tileFileExtension()).isEqualTo("webp");
    assertThat(wmtsKvpFor("image/gif").tileFileExtension()).isEqualTo("gif");
  }

  @Test
  void wmtsKvpLayer_withId_replacesId() {
    assertThat(wmtsKvpFor("image/png").withId("new").id()).isEqualTo("new");
  }

  // ── LocalLayer ─────────────────────────────────────────────────────────────

  @Test
  void localLayer_propertiesAndWithId() {
    LocalLayer layer = new LocalLayer("l", "L", "att", 5, 0, 60, List.of("u"), List.of("g"));
    assertThat(layer.sourceType()).isEqualTo(Layer.SourceType.LOCAL);
    assertThat(layer.urlTemplate()).isNull();
    assertThat(layer.tileFileExtension()).isEqualTo("png");
    assertThat(layer.isPublic()).isFalse();
    assertThat(layer.withId("renamed").id()).isEqualTo("renamed");
  }

  // ── VectorPmtilesLayer ────────────────────────────────────────────────────

  @Test
  void vectorPmtilesLayer_propertiesAndWithId() {
    VectorPmtilesLayer layer =
        new VectorPmtilesLayer("v", "V", "f.pmtiles", null, 10, 0, 0, List.of(), List.of());
    assertThat(layer.sourceType()).isEqualTo(Layer.SourceType.VECTOR_PMTILES);
    assertThat(layer.tileFileExtension()).isEqualTo("pbf");
    assertThat(layer.isPublic()).isTrue();
    assertThat(layer.withId("renamed").id()).isEqualTo("renamed");
  }

  // ── Common defaults ────────────────────────────────────────────────────────

  @Test
  void effectiveId_fallsBackToNameWhenIdIsBlank() {
    XyzLayer layer = newXyz(null, "u", null);
    assertThat(layer.effectiveId()).isEqualTo("a-name");
  }

  @Test
  void doesUrlHaveTime_falseWhenNeither() {
    XyzLayer layer = newXyz("a", "https://x/{z}/{x}/{y}.png", null);
    assertThat(layer.doesUrlHaveTime()).isFalse();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static XyzLayer newXyz(String id, String url, String timeFormat) {
    return new XyzLayer(
        id, "a-name", url, null, 22, 0, 0, List.of(), List.of(), Map.of(), timeFormat);
  }

  private static WmtsKvpLayer wmtsKvpFor(String wmtsFormat) {
    return new WmtsKvpLayer(
        "k",
        "k",
        "https://s/wmts",
        null,
        22,
        0,
        0,
        List.of(),
        List.of(),
        Map.of(),
        "L",
        "EPSG:3857",
        "default",
        wmtsFormat,
        false,
        null);
  }
}
