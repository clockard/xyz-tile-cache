package org.lockard.xyztilecache.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.LocalLayer;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.mockito.Mockito;

class OnlineCacheLoaderTest {

  @TempDir File tempDir;

  private XyzConfiguration configuration;
  private LayerStore layerStore;
  private TileWriter tileWriter;
  private OnlineCacheLoader loader;

  @BeforeEach
  void setUp() throws Exception {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.getAbsolutePath());
    configuration.setTileTimeoutSeconds(1);
    configuration.installLayers(List.of(localLayer()));

    layerStore = new LayerStore(configuration, new ObjectMapper(), event -> {});
    layerStore.init();

    tileWriter = Mockito.mock(TileWriter.class);
    loader =
        new OnlineCacheLoader(configuration, tileWriter, layerStore, new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (layerStore != null) {
      layerStore.close();
    }
  }

  private static LocalLayer localLayer() {
    return new LocalLayer("local-layer", "local-layer", null, 22, 0, 0, List.of(), List.of());
  }

  @Test
  void load_returnsBytesFromDiskForLocalLayer() throws Exception {
    Tile tile = new Tile("local-layer", 1, 2, 3);
    File file =
        new File(
            tempDir,
            "local-layer" + File.separator + "3" + File.separator + "1" + File.separator + "2.png");
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), new byte[] {9, 9, 9});

    assertThat(loader.load(tile)).isEqualTo(new byte[] {9, 9, 9});
    Mockito.verifyNoInteractions(tileWriter);
  }

  @Test
  void load_throwsWithoutHttpFetchWhenLocalLayerMissesTile() {
    Tile tile = new Tile("local-layer", 1, 2, 99);
    assertThatThrownBy(() -> loader.load(tile))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("LOCAL");
    Mockito.verifyNoInteractions(tileWriter);
  }
}
