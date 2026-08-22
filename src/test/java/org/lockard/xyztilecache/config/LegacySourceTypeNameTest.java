package org.lockard.xyztilecache.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PmtilesLayer;

/**
 * {@code SourceType.PMTILES} was called {@code VECTOR_PMTILES} before raster archives were
 * supported. That name is written into every layers.json and typed into every application.yml
 * produced before the rename, so both spellings have to keep working.
 */
class LegacySourceTypeNameTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // ── Configuration binding ─────────────────────────────────────────────────

  @Test
  void converter_acceptsTheLegacyName() {
    assertThat(new SourceTypeConverter().convert("VECTOR_PMTILES"))
        .isEqualTo(Layer.SourceType.PMTILES);
  }

  @Test
  void converter_acceptsTheCurrentName() {
    assertThat(new SourceTypeConverter().convert("PMTILES")).isEqualTo(Layer.SourceType.PMTILES);
  }

  @Test
  void converter_acceptsRelaxedSpellingsFromEnvironmentVariables() {
    SourceTypeConverter converter = new SourceTypeConverter();
    assertThat(converter.convert("vector-pmtiles")).isEqualTo(Layer.SourceType.PMTILES);
    assertThat(converter.convert("vector_pmtiles")).isEqualTo(Layer.SourceType.PMTILES);
    assertThat(converter.convert(" pmtiles ")).isEqualTo(Layer.SourceType.PMTILES);
  }

  @Test
  void converter_stillHandlesTheOtherSourceTypes() {
    SourceTypeConverter converter = new SourceTypeConverter();
    assertThat(converter.convert("XYZ")).isEqualTo(Layer.SourceType.XYZ);
    assertThat(converter.convert("wmts-kvp")).isEqualTo(Layer.SourceType.WMTS_KVP);
    assertThat(converter.convert("WMS")).isEqualTo(Layer.SourceType.WMS);
  }

  @Test
  void converter_rejectsAnUnknownName() {
    assertThatThrownBy(() -> new SourceTypeConverter().convert("NOT_A_SOURCE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void layerProperties_boundWithTheLegacyNameProduceAPmtilesLayer() {
    LayerProperties props = new LayerProperties();
    props.setId("legacy");
    props.setName("Legacy");
    props.setSourceType(new SourceTypeConverter().convert("VECTOR_PMTILES"));
    props.setUrlTemplate("/tiles/basemap.pmtiles");

    Layer layer = props.toLayer();

    assertThat(layer).isInstanceOf(PmtilesLayer.class);
    assertThat(layer.sourceType()).isEqualTo(Layer.SourceType.PMTILES);
  }

  // ── layers.json ───────────────────────────────────────────────────────────

  @Test
  void layerWrittenWithTheLegacyName_stillDeserialises() throws Exception {
    String legacyJson =
        """
        {
          "id": "basemap",
          "name": "Basemap",
          "sourceType": "VECTOR_PMTILES",
          "urlTemplate": "/tiles/basemap.pmtiles",
          "maxZoom": 15,
          "initZoom": 0,
          "tileExpirationMinutes": 0,
          "allowedUsers": [],
          "allowedGroups": []
        }
        """;

    Layer layer = mapper.readValue(legacyJson, Layer.class);

    assertThat(layer).isInstanceOf(PmtilesLayer.class);
    assertThat(layer.effectiveId()).isEqualTo("basemap");
    assertThat(layer.sourceType()).isEqualTo(Layer.SourceType.PMTILES);
  }

  @Test
  void layerWrittenWithTheCurrentName_deserialises() throws Exception {
    String json =
        """
        {
          "id": "basemap",
          "name": "Basemap",
          "sourceType": "PMTILES",
          "urlTemplate": "/tiles/basemap.pmtiles",
          "maxZoom": 15,
          "initZoom": 0,
          "tileExpirationMinutes": 0,
          "allowedUsers": [],
          "allowedGroups": []
        }
        """;

    assertThat(mapper.readValue(json, Layer.class)).isInstanceOf(PmtilesLayer.class);
  }

  @Test
  void layersAreWrittenBackUnderTheCurrentName() throws Exception {
    Layer layer =
        mapper.readValue(
            """
            { "id": "basemap", "name": "Basemap", "sourceType": "VECTOR_PMTILES",
              "urlTemplate": "/tiles/basemap.pmtiles", "maxZoom": 15, "initZoom": 0,
              "tileExpirationMinutes": 0, "allowedUsers": [], "allowedGroups": [] }
            """,
            Layer.class);

    // Reading a legacy file and writing it back migrates the spelling in place.
    assertThat(mapper.writeValueAsString(layer)).contains("\"sourceType\":\"PMTILES\"");
  }

  @Test
  void aLegacyLayerRoundTripsThroughSerialisation() throws Exception {
    Layer original =
        mapper.readValue(
            """
            { "id": "basemap", "name": "Basemap", "sourceType": "VECTOR_PMTILES",
              "urlTemplate": "/tiles/basemap.pmtiles", "maxZoom": 15, "initZoom": 0,
              "tileExpirationMinutes": 0, "allowedUsers": [], "allowedGroups": [] }
            """,
            Layer.class);

    Layer reread = mapper.readValue(mapper.writeValueAsString(original), Layer.class);

    assertThat(reread).isEqualTo(original);
  }
}
