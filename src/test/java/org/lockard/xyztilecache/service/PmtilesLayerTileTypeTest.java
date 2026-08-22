package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PmtilesLayer;
import org.lockard.xyztilecache.pmtiles.PmtilesTileType;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.TileInventoryStore;

/**
 * A PMTiles layer serves one kind of tile. The type comes from the archive's header, and an archive
 * holding something else is refused rather than mixed into the layer.
 */
class PmtilesLayerTileTypeTest {

  /** tile_type 2 (PNG) — a raster archive despite the historical "vector" naming. */
  private static final String RASTER_FIXTURE = "test_fixture_1.pmtiles";

  /** tile_type 1 (MVT). */
  private static final String VECTOR_FIXTURE = "test_fixture_gzip.pmtiles";

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private LayerStore layerStore;
  private PmtilesManager manager;

  @BeforeEach
  void setUp() throws Exception {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    layerStore = new LayerStore(configuration, new ObjectMapper(), event -> {});
    layerStore.init();
    manager =
        new PmtilesManager(
            layerStore, configuration, new TileInventoryStore(configuration, new ObjectMapper()));
  }

  private static byte[] fixture(String name) throws Exception {
    return Files.readAllBytes(
        Path.of(PmtilesLayerTileTypeTest.class.getClassLoader().getResource(name).toURI()));
  }

  /** Copies a fixture archive into a layer's directory under the given file name. */
  private void placeArchive(String layerId, String fileName, String fixture) throws Exception {
    Path dir = tempDir.resolve(layerId);
    Files.createDirectories(dir);
    Files.write(dir.resolve(fileName), fixture(fixture));
  }

  private Layer pmtilesLayer(String id) {
    return new PmtilesLayer(id, id, null, null, 14, 0, 0, List.of(), List.of());
  }

  /** A layer whose configured source is a local archive at {@code sourcePath}. */
  private Layer pmtilesLayer(String id, Path sourcePath) {
    return new PmtilesLayer(id, id, sourcePath.toString(), null, 14, 0, 0, List.of(), List.of());
  }

  @Test
  void rasterArchive_reportsItsOwnTileType() throws Exception {
    placeArchive("raster", "a.pmtiles", RASTER_FIXTURE);

    manager.initLayer(pmtilesLayer("raster"));

    assertThat(manager.tileType("raster")).contains(PmtilesTileType.PNG);
    assertThat(manager.cachedTileExtension("raster")).isEqualTo("png");
  }

  @Test
  void vectorArchive_reportsItsOwnTileType() throws Exception {
    placeArchive("vector", "a.pmtiles", VECTOR_FIXTURE);

    manager.initLayer(pmtilesLayer("vector"));

    assertThat(manager.tileType("vector")).contains(PmtilesTileType.MVT);
    assertThat(manager.cachedTileExtension("vector")).isEqualTo("pbf");
  }

  @Test
  void archiveOfADifferentTypeIsRefused() throws Exception {
    // Two archives in one layer directory, holding different kinds of tile.
    placeArchive("mixed", "a-vector.pmtiles", VECTOR_FIXTURE);
    placeArchive("mixed", "b-raster.pmtiles", RASTER_FIXTURE);

    manager.initLayer(pmtilesLayer("mixed"));

    // Whichever archive opened first sets the layer's type; the other is left out entirely rather
    // than serving tiles of the wrong kind under the same layer.
    PmtilesTileType settled = manager.tileType("mixed").orElseThrow();
    assertThat(manager.cachedTileExtension("mixed")).isEqualTo(settled.extension());

    // Both fixtures hold 0/0/0, so if the refused archive were still in the layer it could answer
    // with the other type. Every tile the layer serves carries the settled type.
    assertThat(manager.getTile("mixed", 0, 0, 0))
        .hasValueSatisfying(
            tile -> assertThat(tile.contentType()).isEqualTo(settled.contentType()));
  }

  @Test
  void eachLayerResolvesItsOwnType() throws Exception {
    placeArchive("layer-a", "a.pmtiles", VECTOR_FIXTURE);
    placeArchive("layer-b", "b.pmtiles", RASTER_FIXTURE);

    manager.initLayer(pmtilesLayer("layer-a"));
    manager.initLayer(pmtilesLayer("layer-b"));

    // The whole point of refusing a mismatch: put the other kind in its own layer.
    assertThat(manager.tileType("layer-a")).contains(PmtilesTileType.MVT);
    assertThat(manager.tileType("layer-b")).contains(PmtilesTileType.PNG);
  }

  @Test
  void unknownLayer_hasNoTypeAndFallsBackToVector() {
    assertThat(manager.tileType("never-opened")).isEmpty();
    // Callers that must name an extension keep the historical vector default.
    assertThat(manager.cachedTileExtension("never-opened")).isEqualTo("pbf");
  }

  @Test
  void closingALayerForgetsItsType() throws Exception {
    placeArchive("recycled", "a.pmtiles", RASTER_FIXTURE);
    manager.initLayer(pmtilesLayer("recycled"));
    assertThat(manager.tileType("recycled")).contains(PmtilesTileType.PNG);

    manager.closeLayer("recycled");

    // Re-resolved on the next open, since the layer's archives may have been replaced.
    assertThat(manager.tileType("recycled")).isEmpty();
  }

  @Test
  void reopeningAfterSwappingTheArchiveAdoptsTheNewType() throws Exception {
    placeArchive("swapped", "a.pmtiles", VECTOR_FIXTURE);
    manager.initLayer(pmtilesLayer("swapped"));
    assertThat(manager.tileType("swapped")).contains(PmtilesTileType.MVT);

    manager.closeLayer("swapped");
    Files.delete(tempDir.resolve("swapped").resolve("a.pmtiles"));
    placeArchive("swapped", "a.pmtiles", RASTER_FIXTURE);
    manager.initLayer(pmtilesLayer("swapped"));

    assertThat(manager.tileType("swapped")).contains(PmtilesTileType.PNG);
  }

  @Test
  void theConfiguredSourceDecidesTheType_notAStrayArchiveInTheLayerDirectory() throws Exception {
    // The layer is configured to serve a raster archive kept outside the layer directory.
    Path source = tempDir.resolve("declared.pmtiles");
    Files.write(source, fixture(RASTER_FIXTURE));
    // Something else dropped a vector archive into the layer's own directory.
    placeArchive("declared", "stray.pmtiles", VECTOR_FIXTURE);

    manager.initLayer(pmtilesLayer("declared", source));

    // The declared source wins: a stray file cannot redefine what the layer serves.
    assertThat(manager.tileType("declared")).contains(PmtilesTileType.PNG);
    assertThat(manager.getTile("declared", 0, 0, 0))
        .hasValueSatisfying(tile -> assertThat(tile.contentType()).isEqualTo("image/png"));
  }

  @Test
  void aStrayArchiveOfTheSameTypeIsStillUsed() throws Exception {
    // Downloaded extracts land in the layer directory and must keep working; only a type
    // mismatch is grounds for refusal.
    Path source = tempDir.resolve("extracts.pmtiles");
    Files.write(source, fixture(RASTER_FIXTURE));
    placeArchive("extracts", "extract-1.pmtiles", RASTER_FIXTURE);

    manager.initLayer(pmtilesLayer("extracts", source));

    assertThat(manager.tileType("extracts")).contains(PmtilesTileType.PNG);
    assertThat(manager.getTile("extracts", 0, 0, 0)).isPresent();
  }
}
