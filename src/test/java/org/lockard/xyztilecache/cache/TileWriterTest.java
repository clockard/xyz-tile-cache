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
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.XyzLayer;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.TileInventoryStore;

class TileWriterTest {

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private LayerStore layerStore;
  private TileInventoryStore inventory;
  private Layer layer;

  @BeforeEach
  void setUp() throws Exception {
    layer =
        new XyzLayer(
            "test",
            "test",
            "https://example.com/{z}/{x}/{y}.png",
            null,
            22,
            0,
            0,
            List.of(),
            List.of(),
            java.util.Map.of(),
            null);

    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    configuration.installLayers(List.of(layer));

    layerStore = new LayerStore(configuration, new ObjectMapper(), event -> {});
    layerStore.init();
    inventory = new TileInventoryStore(configuration, new ObjectMapper());
    inventory.init();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (inventory != null) {
      inventory.close();
    }
    if (layerStore != null) {
      layerStore.close();
    }
  }

  @Test
  void toPath_constructsLayerZXYPath() {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);
    assertThat(writer.toPath(tile)).isEqualTo(tempDir.resolve(Path.of("test", "3", "1", "2.png")));
  }

  @Test
  void storeTile_writesBytesToDisk() throws IOException {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    assertThat(writer.toPath(tile)).exists();
    assertThat(Files.readAllBytes(writer.toPath(tile))).isEqualTo(data);
  }

  @Test
  void storeTile_updatesLayerStats() {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    assertThat(inventory.tiles(layer.effectiveId())).isEqualTo(1);
    assertThat(inventory.bytes(layer.effectiveId())).isEqualTo(data.length);
  }

  @Test
  void storeTile_overwritingExistingTileDoesNotIncrementCount() {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);

    writer.storeTile(tile, new byte[] {10, 20, 30});
    writer.storeTile(tile, new byte[] {10, 20, 30, 40, 50});

    assertThat(inventory.tiles(layer.effectiveId())).isEqualTo(1);
    assertThat(inventory.bytes(layer.effectiveId())).isEqualTo(5);
  }

  @Test
  void storeTile_overwritingWithSmallerTileShrinksCachedSize() {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);

    writer.storeTile(tile, new byte[] {10, 20, 30, 40, 50});
    writer.storeTile(tile, new byte[] {10, 20});

    assertThat(inventory.tiles(layer.effectiveId())).isEqualTo(1);
    assertThat(inventory.bytes(layer.effectiveId())).isEqualTo(2);
  }

  @Test
  void storeTile_writesToPreexistingDirectory() throws IOException {
    Path dir = tempDir.resolve(Path.of("test", "3", "1"));
    Files.createDirectories(dir);
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);
    byte[] data = {7, 8, 9};

    writer.storeTile(tile, data);

    assertThat(dir.resolve("2.png")).exists();
    assertThat(Files.readAllBytes(dir.resolve("2.png"))).isEqualTo(data);
  }

  @Test
  void createLayerDirectories_createsADirectoryPerConfiguredLayer() {
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);

    writer.createLayerDirectories();

    assertThat(tempDir.resolve("test")).isDirectory();
  }

  @Test
  void createLayerDirectories_doesNotWalkExistingTiles() throws IOException {
    Path tileFile = tempDir.resolve(Path.of("test", "3", "1", "2.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3, 4, 5});

    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    writer.createLayerDirectories();

    // Counting pre-existing tiles is the scanner's job, and only when the inventory needs
    // rebuilding -- startup must not pay for a pass over the cache.
    assertThat(inventory.tiles(layer.effectiveId())).isZero();
    assertThat(inventory.bytes(layer.effectiveId())).isZero();
  }

  @Test
  void storeTile_skipsWhenFreeDiskBelowMinimum() {
    // Setting minFreeDiskBytes to Long.MAX_VALUE guarantees the threshold is never met
    configuration.setMinFreeDiskBytes(Long.MAX_VALUE);
    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    Tile tile = new Tile("test", 1, 2, 3);

    writer.storeTile(tile, new byte[] {1, 2, 3});

    assertThat(writer.toPath(tile)).doesNotExist();
  }

  @Test
  void onLayerChanged_removed_deletesDirectory() throws IOException {
    Path layerDir = tempDir.resolve("ghost");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});

    inventory.recordWrite("ghost", 1, 3);

    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    writer.onLayerChanged(new LayerChangedEvent("ghost", LayerChangedEvent.Kind.REMOVED));

    assertThat(layerDir).doesNotExist();
    assertThat(inventory.tiles("ghost")).isZero();
    assertThat(inventory.bytes("ghost")).isZero();
  }

  @Test
  void onLayerChanged_updatedSource_deletesDirectoryAndResetsStats() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});
    inventory.recordWrite("test", 1, 3);

    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.UPDATED_SOURCE));

    assertThat(layerDir).doesNotExist();
    assertThat(inventory.tiles("test")).isZero();
    assertThat(inventory.bytes("test")).isZero();
  }

  @Test
  void onLayerChanged_updatedAcl_keepsDirectoryAndStats() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});
    inventory.recordWrite("test", 1, 3);

    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.UPDATED_ACL));

    assertThat(tileFile).exists();
    assertThat(inventory.tiles("test")).isEqualTo(1);
    assertThat(inventory.bytes("test")).isEqualTo(3);
  }

  @Test
  void onLayerChanged_added_keepsDirectory() throws IOException {
    Path layerDir = tempDir.resolve("test");
    Path tileFile = layerDir.resolve(Path.of("1", "0", "0.png"));
    Files.createDirectories(tileFile.getParent());
    Files.write(tileFile, new byte[] {1, 2, 3});

    TileWriter writer = new TileWriter(configuration, layerStore, inventory);
    writer.onLayerChanged(new LayerChangedEvent("test", LayerChangedEvent.Kind.ADDED));

    assertThat(tileFile).exists();
  }
}
