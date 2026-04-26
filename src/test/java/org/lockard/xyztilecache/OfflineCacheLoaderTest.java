package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineCacheLoaderTest {

  @TempDir File tempDir;

  private XyzConfiguration configuration;
  private OfflineCacheLoader loader;
  private Layer layer;

  @BeforeEach
  void setUp() {
    layer = new Layer();
    layer.setName("test");

    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.getAbsolutePath());
    configuration.setLayers(List.of(layer));

    loader = new OfflineCacheLoader(configuration);
  }

  @Test
  void toFile_constructsCorrectPath() {
    Tile tile = new Tile(layer, 1, 2, 3);
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
    Tile tile = new Tile(layer, 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {1, 2, 3};
    Files.write(file.toPath(), expected);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_throwsWhenTileIsExpired() throws Exception {
    layer.setTileExpirationMinutes(1);
    Tile tile = new Tile(layer, 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), new byte[] {1});
    // Age the file to 2 minutes in the past
    file.setLastModified(System.currentTimeMillis() - 2 * 60_000L);

    assertThatThrownBy(() -> loader.load(tile)).hasMessage("Tile expired");
  }

  @Test
  void load_doesNotExpireWhenWithinExpirationWindow() throws Exception {
    layer.setTileExpirationMinutes(60);
    Tile tile = new Tile(layer, 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {4, 5, 6};
    Files.write(file.toPath(), expected);
    // File was just written — age is within the 60-minute window

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_skipsExpirationCheckWhenExpirationIsZero() throws Exception {
    layer.setTileExpirationMinutes(0);
    Tile tile = new Tile(layer, 1, 2, 3);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {7, 8, 9};
    Files.write(file.toPath(), expected);
    file.setLastModified(0L); // Epoch — extremely old, but expiration is disabled

    assertThat(loader.load(tile)).isEqualTo(expected);
  }
}
