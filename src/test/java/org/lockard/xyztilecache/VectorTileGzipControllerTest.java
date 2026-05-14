package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
class VectorTileGzipControllerTest {

  @TempDir static File tileDir;
  @TempDir static File vectorDir;

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().gzipDisabled(true))
          .build();

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", () -> List.of());

    // Copy gzip fixture to download directory so VectorTileService loads it at startup.
    try {
      URL fixture =
          VectorTileGzipControllerTest.class
              .getClassLoader()
              .getResource("test_fixture_gzip.pmtiles");
      Files.copy(Path.of(fixture.toURI()), vectorDir.toPath().resolve("test_fixture_gzip.pmtiles"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to copy gzip test fixture", e);
    }

    registry.add("xyz.vector.downloadDirectory", () -> vectorDir.getAbsolutePath());
    registry.add("xyz.vector.sourceUrl", () -> wireMock.baseUrl() + "/planet.pmtiles");
    registry.add("xyz.vector.enabled", () -> "true");
  }

  @Test
  void getTile_gzipCompressedTile_setsContentEncodingGzip(@Autowired MockMvc mvc) throws Exception {
    // The gzip fixture has a single tile at z=0,x=0,y=0 with tileCompression=gzip
    mvc.perform(MockMvcRequestBuilders.get("/vector/0/0/0"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Encoding", "gzip"))
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"));
  }
}
