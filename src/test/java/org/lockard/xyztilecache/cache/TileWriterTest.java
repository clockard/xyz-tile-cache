package org.lockard.xyztilecache.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;

class TileWriterTest {

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private LayerStore layerStore;
  private Layer layer;

  @BeforeEach
  void setUp() throws Exception {
    layer = new Layer();
    layer.setName("test");

    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    configuration.setLayers(List.of(layer));

    layerStore = new LayerStore(configuration, new ObjectMapper(), event -> {});
    layerStore.init();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (layerStore != null) {
      layerStore.close();
    }
  }

  @Test
  void toPath_constructsLayerZXYPath() {
    TileWriter writer = new TileWriter(configuration, layerStore);
    Tile tile = new Tile(layer, 1, 2, 3);
    assertThat(writer.toPath(tile)).isEqualTo(tempDir.resolve(Path.of("test", "3", "1", "2.png")));
  }

  @Test
  void storeTile_writesBytesToDisk() throws IOException {
    TileWriter writer = new TileWriter(configuration, layerStore);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    assertThat(writer.toPath(tile)).exists();
    assertThat(Files.readAllBytes(writer.toPath(tile))).isEqualTo(data);
  }

  @Test
  void storeTile_updatesLayerStats() {
    TileWriter writer = new TileWriter(configuration, layerStore);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    LayerRuntimeState state = layerStore.getRuntimeState(layer.getEffectiveId());
    assertThat(state.getCachedTiles()).isEqualTo(1);
    assertThat(state.getCachedTilesSize()).isEqualTo(data.length);
  }

  @Test
  void storeTile_writesToPreexistingDirectory() throws IOException {
    Path dir = tempDir.resolve(Path.of("test", "3", "1"));
    Files.createDirectories(dir);
    TileWriter writer = new TileWriter(configuration, layerStore);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {7, 8, 9};

    writer.storeTile(tile, data);

    assertThat(dir.resolve("2.png")).exists();
    assertThat(Files.readAllBytes(dir.resolve("2.png"))).isEqualTo(data);
  }

  @Test
  void inventoryExistingTiles_countsPreExistingTilesInLayerStats() throws IOException {
    Path tileFile = tempDir.resolve(Path.of("test", "3", "1", "2.png"));
    Files.createDirectories(tileFile.getParent());
    byte[] existing = {1, 2, 3, 4, 5};
    Files.write(tileFile, existing);

    TileWriter writer = new TileWriter(configuration, layerStore);
    writer.inventoryExistingTiles();

    LayerRuntimeState state = layerStore.getRuntimeState(layer.getEffectiveId());
    assertThat(state.getCachedTiles()).isEqualTo(1);
    assertThat(state.getCachedTilesSize()).isEqualTo(existing.length);
  }

  @Test
  void storeTile_skipsWhenFreeDiskBelowMinimum() {
    // Setting minFreeDiskBytes to Long.MAX_VALUE guarantees the threshold is never met
    configuration.setMinFreeDiskBytes(Long.MAX_VALUE);
    TileWriter writer = new TileWriter(configuration, layerStore);
    Tile tile = new Tile(layer, 1, 2, 3);

    writer.storeTile(tile, new byte[] {1, 2, 3});

    assertThat(writer.toPath(tile)).doesNotExist();
  }

  @Test
  void onLayerChanged_removed_deletesDirectory() throws IOException {
    Path layerDir = tempDir.resolve("ghost");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});

    TileWriter writer = new TileWriter(configuration, layerStore);
    writer.onLayerChanged(new LayerChangedEvent("ghost", LayerChangedEvent.Kind.REMOVED));

    assertThat(layerDir).doesNotExist();
  }

  @Test
  void onLayerChanged_updatedSource_deletesDirectoryAndResetsStats() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});
    LayerRuntimeState state = layerStore.getRuntimeState("test");
    state.addTileStats(3);

    TileWriter writer = new TileWriter(configuration, layerStore);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.UPDATED_SOURCE));

    assertThat(layerDir).doesNotExist();
    assertThat(state.getCachedTiles()).isZero();
    assertThat(state.getCachedTilesSize()).isZero();
  }

  @Test
  void onLayerChanged_updatedAcl_keepsDirectoryAndStats() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});
    LayerRuntimeState state = layerStore.getRuntimeState("test");
    state.addTileStats(3);

    TileWriter writer = new TileWriter(configuration, layerStore);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.UPDATED_ACL));

    assertThat(tileFile).exists();
    assertThat(state.getCachedTiles()).isEqualTo(1);
    assertThat(state.getCachedTilesSize()).isEqualTo(3);
  }

  @Test
  void onLayerChanged_added_keepsDirectory() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});

    TileWriter writer = new TileWriter(configuration, layerStore);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.ADDED));

    assertThat(tileFile).exists();
  }
}
