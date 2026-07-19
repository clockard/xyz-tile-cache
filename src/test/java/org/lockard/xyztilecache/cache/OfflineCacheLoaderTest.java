package org.lockard.xyztilecache.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.XyzLayer;
import org.lockard.xyztilecache.store.LayerStore;

class OfflineCacheLoaderTest {

  @TempDir File tempDir;

  private XyzConfiguration configuration;
  private LayerStore layerStore;
  private OfflineCacheLoader loader;

  @BeforeEach
  void setUp() throws Exception {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.getAbsolutePath());
    configuration.installLayers(List.of(layer(0)));
    layerStore = new LayerStore(configuration, new ObjectMapper(), event -> {});
    layerStore.init();
    loader = new OfflineCacheLoader(configuration, layerStore);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (layerStore != null) {
      layerStore.close();
    }
  }

  private static XyzLayer layer(int expirationMinutes) {
    return new XyzLayer(
        "test", "test", null, null, 22, 0, expirationMinutes, List.of(), List.of(), Map.of(), null);
  }

  /** Installs the "test" layer with the given expiration and returns its 3/1/2 tile key. */
  private Tile tile(int expirationMinutes) {
    layerStore.getLayers().put("test", layer(expirationMinutes));
    return new Tile("test", 1, 2, 3);
  }

  @Test
  void toFile_constructsCorrectPath() {
    Tile tile = tile(0);
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
    Tile tile = tile(0);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {1, 2, 3};
    Files.write(file.toPath(), expected);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_throwsWhenTileIsExpired() throws Exception {
    Tile tile = tile(1);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), new byte[] {1});
    // Age the file to 2 minutes in the past
    file.setLastModified(System.currentTimeMillis() - 2 * 60_000L);

    assertThatThrownBy(() -> loader.load(tile)).hasMessage("Tile expired");
  }

  @Test
  void load_doesNotExpireWhenWithinExpirationWindow() throws Exception {
    Tile tile = tile(60);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {4, 5, 6};
    Files.write(file.toPath(), expected);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }

  @Test
  void load_throwsWhenFileMissingAndExpirationConfigured() {
    Tile tile = tile(60);
    assertThatThrownBy(() -> loader.load(tile)).isInstanceOf(java.io.FileNotFoundException.class);
  }

  @Test
  void load_skipsExpirationCheckWhenExpirationIsZero() throws Exception {
    Tile tile = tile(0);
    File file = loader.toFile(tile);
    file.getParentFile().mkdirs();
    byte[] expected = {7, 8, 9};
    Files.write(file.toPath(), expected);
    file.setLastModified(0L);

    assertThat(loader.load(tile)).isEqualTo(expected);
  }
}
