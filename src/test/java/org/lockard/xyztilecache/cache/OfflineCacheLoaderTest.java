package org.lockard.xyztilecache.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.XyzLayer;

class OfflineCacheLoaderTest {

  @TempDir File tempDir;

  private XyzConfiguration configuration;
  private OfflineCacheLoader loader;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.getAbsolutePath());
    configuration.installLayers(List.of(layer(0)));
    loader = new OfflineCacheLoader(configuration);
  }

  private static XyzLayer layer(int expirationMinutes) {
    return new XyzLayer(
        "test", "test", null, null, 22, 0, expirationMinutes, List.of(), List.of(), Map.of(), null);
  }

  @Test
  void toFile_constructsCorrectPath() {
    Tile tile = new Tile(layer(0), 1, 2, 3);
    File file = loader.toFile(tile);
    assertThat(file.getAbsolutePath())
        .endsWith(
            File.separator
                + "test"
                + File.separator
                + "3"
                + File.separator
                + "1"
                + File.separator
                + "2.png");
  }

  @Test
  void load_returnsBytesFromExistingFile() throws Exception {
    Tile tile = new Tile(layer(0), 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {1, 2, 3};
    Files.write(file.toPath(), expected);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_throwsWhenTileIsExpired() throws Exception {
    Tile tile = new Tile(layer(1), 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), new byte[] {1});
    // Age the file to 2 minutes in the past
    file.setLastModified(System.currentTimeMillis() - 2 * 60_000L);

    assertThatThrownBy(() -> loader.load(tile)).hasMessage("Tile expired");
  }

  @Test
  void load_doesNotExpireWhenWithinExpirationWindow() throws Exception {
    Tile tile = new Tile(layer(60), 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {4, 5, 6};
    Files.write(file.toPath(), expected);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_throwsWhenFileMissingAndExpirationConfigured() {
    Tile tile = new Tile(layer(60), 1, 2, 3);
    assertThatThrownBy(() -> loader.load(tile)).isInstanceOf(java.io.FileNotFoundException.class);
  }

  @Test
  void load_skipsExpirationCheckWhenExpirationIsZero() throws Exception {
    Tile tile = new Tile(layer(0), 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {7, 8, 9};
    Files.write(file.toPath(), expected);
    file.setLastModified(0L);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }
}
