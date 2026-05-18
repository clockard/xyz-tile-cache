package org.lockard.xyztilecache.pmtiles;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.model.TileResult;
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
class VectorTileRemoteS3Test {

  @TempDir static File tileDir;
  @TempDir static File vectorDir;

  private static final byte[] FIXTURE_BYTES = loadFixture();

  private static byte[] loadFixture() {
    try {
      URL url = VectorTileRemoteS3Test.class.getClassLoader().getResource("test_fixture_1.pmtiles");
      return Files.readAllBytes(Path.of(url.toURI()));
    } catch (Exception e) {
      throw new RuntimeException("Failed to load test_fixture_1.pmtiles", e);
    }
  }

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(
              wireMockConfig()
                  .dynamicPort()
                  .extensions(new TestRangeResponseTransformer(FIXTURE_BYTES)))
          .build();

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", () -> List.of());
    // No PMTiles in download dir — tiles must come from the remote PMTiles reader (sourceUrl).
    registry.add("xyz.vector.downloadDirectory", () -> vectorDir.getAbsolutePath());
    registry.add("xyz.vector.sourceUrl", () -> wireMock.baseUrl() + "/world.pmtiles");
    registry.add("xyz.vector.enabled", () -> "true");
  }

  @BeforeEach
  void stubPmtiles() {
    wireMock.stubFor(
        get(urlEqualTo("/world.pmtiles"))
            .willReturn(aResponse().withStatus(206).withTransformers("range")));
  }

  @Test
  void getTile_presentInRemote_returns200(@Autowired MockMvc mvc) throws Exception {
    // z=0, x=0, y=0 is present in test_fixture_1.pmtiles
    mvc.perform(MockMvcRequestBuilders.get("/vector/0/0/0"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"));
  }

  @Test
  void getTile_notInRemote_returns204(@Autowired MockMvc mvc) throws Exception {
    // z=1, x=0, y=0 is NOT in test_fixture_1.pmtiles
    mvc.perform(MockMvcRequestBuilders.get("/vector/1/0/0"))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  @Test
  void getTile_afterFirstFetch_cacheFileWritten(
      @Autowired MockMvc mvc, @Autowired VectorTileRemoteCache remoteCache) throws Exception {
    // First request fetches from remote and triggers async cache write
    mvc.perform(MockMvcRequestBuilders.get("/vector/0/0/0"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    Path cacheFile = remoteCache.cachePath(0, 0, 0);
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(cacheFile).exists());
  }

  @Test
  void remoteCache_get_returnsPrepopulatedTile(@Autowired VectorTileRemoteCache remoteCache)
      throws Exception {
    byte[] tileData = {0x0a, 0x0b, 0x0c};
    Path cacheFile = remoteCache.cachePath(3, 2, 1);
    Files.createDirectories(cacheFile.getParent());
    Files.write(cacheFile, tileData);

    Optional<TileResult> result = remoteCache.get(3, 2, 1);
    assertThat(result).isPresent();
    assertThat(result.get().data()).isEqualTo(tileData);
  }
}
