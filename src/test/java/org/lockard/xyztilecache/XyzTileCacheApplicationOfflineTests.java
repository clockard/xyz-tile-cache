package org.lockard.xyztilecache;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationOfflineTests {

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(final DynamicPropertyRegistry registry) {
    registry.add("xyz.offline", () -> true);
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          final var layer = new Layer();
          layer.setName("test");
          layer.setUrlTemplate("http://localhost/{z}/{y}/{x}");
          return List.of(layer);
        });
  }

  @Test
  void loadTileFromCache(@Autowired final MockMvc mvc) throws Exception {
    final var xFolder = tileDir.toPath().resolve("test/3/1");
    if (!xFolder.toFile().mkdirs()) {
      fail("Unable to create the tile directory.");
    }
    Files.write(xFolder.resolve("2.png"), new byte[] {3, 2, 1});
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/3/2/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().bytes(new byte[] {3, 2, 1}));
  }

  @Test
  void return404WhenTileNotCached(@Autowired final MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/9/8/7.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
