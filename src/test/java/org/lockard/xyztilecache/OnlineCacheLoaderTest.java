package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class OnlineCacheLoaderTest {

  @TempDir File tempDir;

  private XyzConfiguration configuration;
  private TileWriter tileWriter;
  private OnlineCacheLoader loader;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.getAbsolutePath());
    configuration.setTileTimeoutSeconds(1);
    configuration.setLayers(List.of());

    tileWriter = Mockito.mock(TileWriter.class);
    loader = new OnlineCacheLoader(configuration, tileWriter);
  }

  @Test
  void load_returnsBytesFromDiskForLocalLayer() throws Exception {
    Layer local = new Layer();
    local.setName("local-layer");
    local.setSourceType(Layer.SourceType.LOCAL);

    Tile tile = new Tile(local, 1, 2, 3);
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
    Layer local = new Layer();
    local.setName("local-layer");
    local.setSourceType(Layer.SourceType.LOCAL);
    // Bogus URL — if HTTP were attempted, the test would hang/fail differently.
    local.setUrlTemplate("http://127.0.0.1:1/{z}/{x}/{y}.png");

    Tile tile = new Tile(local, 1, 2, 99);
    assertThatThrownBy(() -> loader.load(tile))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("LOCAL");
    Mockito.verifyNoInteractions(tileWriter);
  }
}
