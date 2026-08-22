package org.lockard.xyztilecache.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WmsLayerTest {

  private static WmsLayer layer(String url) {
    return layer(url, "1.3.0", Map.of());
  }

  private static WmsLayer layer(String url, String version, Map<String, String> extraParams) {
    return new WmsLayer(
        "wms",
        "WMS",
        url,
        null,
        18,
        0,
        0,
        List.of(),
        List.of(),
        Map.of(),
        "topp:states",
        "",
        "image/png",
        version,
        true,
        256,
        false,
        extraParams,
        null);
  }

  /** Pulls one query parameter out of a built URL, decoded. */
  private static String param(String url, String key) {
    String query = url.substring(url.indexOf('?') + 1);
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(key)) {
        return java.net.URLDecoder.decode(
            pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  // ── Request shape ─────────────────────────────────────────────────────────

  @Test
  void buildUrl_issuesAGetMapRequest() {
    String url = layer("https://example.com/wms").buildUrl(3, 1, 2, null);

    assertThat(param(url, "SERVICE")).isEqualTo("WMS");
    assertThat(param(url, "REQUEST")).isEqualTo("GetMap");
    assertThat(param(url, "VERSION")).isEqualTo("1.3.0");
    assertThat(param(url, "LAYERS")).isEqualTo("topp:states");
    assertThat(param(url, "FORMAT")).isEqualTo("image/png");
    assertThat(param(url, "WIDTH")).isEqualTo("256");
    assertThat(param(url, "HEIGHT")).isEqualTo("256");
    assertThat(param(url, "TRANSPARENT")).isEqualTo("TRUE");
  }

  @Test
  void buildUrl_sendsAnEmptyStylesParameterRatherThanOmittingIt() {
    // STYLES is mandatory in a GetMap request; empty means "server default", missing is an error.
    String url = layer("https://example.com/wms").buildUrl(3, 1, 2, null);

    assertThat(url).contains("STYLES=&");
  }

  // ── Axis order / CRS ──────────────────────────────────────────────────────

  @Test
  void buildUrl_version130_namesTheProjectionParameterCrs() {
    String url = layer("https://example.com/wms").buildUrl(3, 1, 2, null);

    assertThat(param(url, "CRS")).isEqualTo("EPSG:3857");
    assertThat(param(url, "SRS")).isNull();
  }

  @Test
  void buildUrl_version111_namesTheProjectionParameterSrs() {
    String url = layer("https://example.com/wms", "1.1.1", Map.of()).buildUrl(3, 1, 2, null);

    assertThat(param(url, "SRS")).isEqualTo("EPSG:3857");
    assertThat(param(url, "CRS")).isNull();
  }

  @Test
  void buildUrl_bboxMatchesTheTileExtentInMetres() {
    // Tile 0/0/0 is the whole world: the full EPSG:3857 extent, easting first in both versions.
    String url = layer("https://example.com/wms").buildUrl(0, 0, 0, null);

    String[] bbox = param(url, "BBOX").split(",");
    assertThat(Double.parseDouble(bbox[0])).isCloseTo(-20037508.34, within(0.01));
    assertThat(Double.parseDouble(bbox[1])).isCloseTo(-20037508.34, within(0.01));
    assertThat(Double.parseDouble(bbox[2])).isCloseTo(20037508.34, within(0.01));
    assertThat(Double.parseDouble(bbox[3])).isCloseTo(20037508.34, within(0.01));
  }

  @Test
  void buildUrl_bboxIsPlainDecimalNotScientificNotation() {
    // A tile at high zoom has small coordinates; Double.toString would render them as 1.0E-4,
    // which a WMS server will reject.
    String url = layer("https://example.com/wms").buildUrl(20, 524288, 524288, null);

    assertThat(param(url, "BBOX")).doesNotContainIgnoringCase("e");
  }

  // ── Base URL handling ─────────────────────────────────────────────────────

  @Test
  void buildUrl_appendsToABaseUrlWithNoQueryString() {
    String url = layer("https://example.com/wms").buildUrl(3, 1, 2, null);

    assertThat(url).startsWith("https://example.com/wms?SERVICE=WMS");
  }

  @Test
  void buildUrl_keepsAnExistingQueryStringOnTheBaseUrl() {
    // GeoServer endpoints are commonly published as ".../ows?service=WMS".
    String url = layer("https://example.com/geoserver/ows?authkey=abc").buildUrl(3, 1, 2, null);

    assertThat(url).startsWith("https://example.com/geoserver/ows?authkey=abc&SERVICE=WMS");
    assertThat(param(url, "authkey")).isEqualTo("abc");
  }

  @Test
  void buildUrl_doesNotLeaveATrailingSeparator() {
    assertThat(layer("https://example.com/wms").buildUrl(3, 1, 2, null)).doesNotEndWith("&");
  }

  // ── Encoding ──────────────────────────────────────────────────────────────

  @Test
  void buildUrl_encodesParameterValues() {
    WmsLayer withFilter =
        layer("https://example.com/wms", "1.3.0", Map.of("CQL_FILTER", "name = 'Rocky Mountain'"));

    String url = withFilter.buildUrl(3, 1, 2, null);

    assertThat(url).doesNotContain("Rocky Mountain");
    assertThat(url).contains("%20");
    assertThat(param(url, "CQL_FILTER")).isEqualTo("name = 'Rocky Mountain'");
  }

  @Test
  void buildUrl_encodesSpacesAsPercent20NotPlus() {
    WmsLayer withFilter = layer("https://example.com/wms", "1.3.0", Map.of("CQL_FILTER", "a b"));

    assertThat(withFilter.buildUrl(3, 1, 2, null)).contains("CQL_FILTER=a%20b");
  }

  // ── Time dimension ────────────────────────────────────────────────────────

  @Test
  void buildUrl_appendsTimeOnlyWhenTheLayerDeclaresIt() {
    WmsLayer noTime = layer("https://example.com/wms");
    assertThat(param(noTime.buildUrl(3, 1, 2, "2026-08-22T00:00:00Z"), "TIME")).isNull();

    WmsLayer withTime =
        new WmsLayer(
            "wms",
            "WMS",
            "https://example.com/wms",
            null,
            18,
            0,
            0,
            List.of(),
            List.of(),
            Map.of(),
            "radar",
            "",
            "image/png",
            "1.3.0",
            true,
            256,
            true,
            Map.of(),
            null);
    assertThat(param(withTime.buildUrl(3, 1, 2, "2026-08-22T00:00:00Z"), "TIME"))
        .isEqualTo("2026-08-22T00:00:00Z");
    assertThat(withTime.doesUrlHaveTime()).isTrue();
  }

  // ── Defaults ──────────────────────────────────────────────────────────────

  @Test
  void compactConstructor_appliesDefaultsForOmittedFields() {
    WmsLayer sparse =
        new WmsLayer(
            "wms",
            "WMS",
            "https://example.com/wms",
            null,
            0,
            0,
            0,
            null,
            null,
            null,
            "roads",
            null,
            null,
            null,
            true,
            0,
            false,
            null,
            null);

    assertThat(sparse.maxZoom()).isEqualTo(22);
    assertThat(sparse.wmsStyles()).isEmpty();
    assertThat(sparse.wmsFormat()).isEqualTo("image/png");
    assertThat(sparse.wmsVersion()).isEqualTo("1.3.0");
    assertThat(sparse.wmsTileSize()).isEqualTo(256);
    assertThat(sparse.wmsExtraParams()).isEmpty();
    assertThat(sparse.headers()).isEmpty();
  }

  @Test
  void tileFileExtension_followsTheRequestedFormat() {
    assertThat(layer("https://example.com/wms").tileFileExtension()).isEqualTo("png");

    WmsLayer jpeg =
        new WmsLayer(
            "wms",
            "WMS",
            "https://example.com/wms",
            null,
            18,
            0,
            0,
            List.of(),
            List.of(),
            Map.of(),
            "aerial",
            "",
            "image/jpeg",
            "1.3.0",
            false,
            256,
            false,
            Map.of(),
            null);
    assertThat(jpeg.tileFileExtension()).isEqualTo("jpg");
  }

  @Test
  void withId_replacesTheIdAndKeepsEverythingElse() {
    WmsLayer renamed = layer("https://example.com/wms").withId("other");

    assertThat(renamed.id()).isEqualTo("other");
    assertThat(renamed.wmsLayers()).isEqualTo("topp:states");
    assertThat(renamed.sourceType()).isEqualTo(Layer.SourceType.WMS);
  }

  private static org.assertj.core.data.Offset<Double> within(double tolerance) {
    return org.assertj.core.data.Offset.offset(tolerance);
  }
}
