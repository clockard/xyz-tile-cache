package org.lockard.xyztilecache.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PmtilesLayer;

/**
 * Pins the on-disk layers.json format. {@code layers-boot35.json} is what the Spring Boot 3.5 /
 * Jackson 2 build wrote; the Jackson 3 migration must keep reading and writing it unchanged.
 */
class LayersJsonGoldenTest {

  private static final Path DIR = Path.of("src/test/resources/golden");
  private static final TypeReference<List<Layer>> LAYERS = new TypeReference<>() {};

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void hand_written_input_serializes_to_the_golden_output() throws Exception {
    List<Layer> layers =
        mapper.readValue(Files.readAllBytes(DIR.resolve("layers-input.json")), LAYERS);

    JsonNode actual = mapper.valueToTree(layers);
    assertThat(actual).isEqualTo(golden());
  }

  @Test
  void golden_file_round_trips_unchanged() throws Exception {
    List<Layer> layers =
        mapper.readValue(Files.readAllBytes(DIR.resolve("layers-boot35.json")), LAYERS);

    assertThat(layers).hasSize(6);
    JsonNode actual = mapper.valueToTree(layers);
    assertThat(actual).isEqualTo(golden());
  }

  @Test
  void legacy_vector_pmtiles_name_is_read_as_pmtiles_and_written_as_pmtiles() throws Exception {
    List<Layer> layers =
        mapper.readValue(Files.readAllBytes(DIR.resolve("layers-input.json")), LAYERS);

    Layer pmtiles = layers.get(5);
    assertThat(pmtiles).isInstanceOf(PmtilesLayer.class);
    assertThat(mapper.valueToTree(pmtiles).get("sourceType").asText()).isEqualTo("PMTILES");
  }

  private JsonNode golden() throws Exception {
    return mapper.readTree(Files.readAllBytes(DIR.resolve("layers-boot35.json")));
  }
}
