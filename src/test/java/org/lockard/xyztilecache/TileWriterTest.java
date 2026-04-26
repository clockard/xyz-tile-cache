package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileWriterTest {

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private Layer layer;

  @BeforeEach
  void setUp() {
    layer = new Layer();
    layer.setName("test");

    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    configuration.setMaxTileStorage(1_000_000L);
    configuration.setLayers(List.of(layer));
  }

  @Test
  void toPath_constructsLayerZXYPath() {
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 1, 2, 3);
    assertThat(writer.toPath(tile)).isEqualTo(tempDir.resolve(Path.of("test", "3", "1", "2.png")));
  }

  @Test
  void storeTile_writesBytesToDisk() throws IOException {
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    assertThat(writer.toPath(tile)).exists();
    assertThat(Files.readAllBytes(writer.toPath(tile))).isEqualTo(data);
  }

  @Test
  void storeTile_updatesLayerStats() {
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {10, 20, 30};

    writer.storeTile(tile, data);

    assertThat(layer.getCachedTiles()).isEqualTo(1);
    assertThat(layer.getCachedTilesSize()).isEqualTo(data.length);
  }

  @Test
  void storeTile_doesNotWriteWhenStorageFull() {
    configuration.setMaxTileStorage(1L);
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 1, 2, 3);

    writer.storeTile(tile, new byte[] {10, 20, 30});

    assertThat(writer.toPath(tile)).doesNotExist();
  }

  @Test
  void storeTile_writesToPreexistingDirectory() throws IOException {
    Path dir = tempDir.resolve(Path.of("test", "3", "1"));
    Files.createDirectories(dir);
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 1, 2, 3);
    byte[] data = {7, 8, 9};

    writer.storeTile(tile, data);

    assertThat(dir.resolve("2.png")).exists();
    assertThat(Files.readAllBytes(dir.resolve("2.png"))).isEqualTo(data);
  }

  @Test
  void initializeTotalStorageUsed_countsPreExistingTiles() throws IOException {
    Path tileFile = tempDir.resolve(Path.of("test", "3", "1", "2.png"));
    Files.createDirectories(tileFile.getParent());
    byte[] existing = {1, 2, 3, 4, 5};
    Files.write(tileFile, existing);

    // Constructor runs initializeTotalStorageUsed — must count the existing tile
    TileWriter writer = new TileWriter(configuration);

    assertThat(layer.getCachedTiles()).isEqualTo(1);
    assertThat(layer.getCachedTilesSize()).isEqualTo(existing.length);
  }

  @Test
  void storeTile_accountsForExistingStorageWhenEnforcingLimit() throws IOException {
    // Fill up storage with an existing file
    Path existing = tempDir.resolve(Path.of("test", "0", "0", "0.png"));
    Files.createDirectories(existing.getParent());
    byte[] existingData = new byte[999_999];
    Files.write(existing, existingData);

    // Limit leaves only 1 byte; trying to store 2 bytes must be rejected
    configuration.setMaxTileStorage(1_000_000L);
    TileWriter writer = new TileWriter(configuration);
    Tile tile = new Tile(layer, 5, 6, 7);

    writer.storeTile(tile, new byte[] {1, 2});

    assertThat(writer.toPath(tile)).doesNotExist();
  }
}
