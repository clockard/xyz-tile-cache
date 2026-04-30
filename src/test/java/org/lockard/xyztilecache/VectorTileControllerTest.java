package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class VectorTileControllerTest {

  static final String ADMIN_KEY = "vector-test-key";

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
    registry.add("xyz.adminKey", () -> ADMIN_KEY);
    registry.add("xyz.layers", () -> List.of());
    URL fixture =
        VectorTileControllerTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
    registry.add("xyz.vector.bundledPath", () -> fixture.getPath());
    registry.add("xyz.vector.downloadDirectory", () -> vectorDir.getAbsolutePath());
    registry.add("xyz.vector.sourceUrl", () -> wireMock.baseUrl() + "/planet.pmtiles");
    registry.add("xyz.vector.enabled", () -> "true");
  }

  // ── GET /vector/{z}/{x}/{y} ───────────────────────────────────────────────

  @Test
  void getTile_presentInBundled_returns200WithProtobufType(@Autowired MockMvc mvc)
      throws Exception {
    // z=0, x=0, y=0 is in the test fixture
    mvc.perform(MockMvcRequestBuilders.get("/vector/0/0/0"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"))
        .andExpect(MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"))
        .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
  }

  @Test
  void getTile_z1KnownTile_returns200(@Autowired MockMvc mvc) throws Exception {
    // z=1, x=1, y=1 is in the test fixture
    mvc.perform(MockMvcRequestBuilders.get("/vector/1/1/1"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void getTile_missingTile_returns204(@Autowired MockMvc mvc) throws Exception {
    // z=1, x=0, y=0 is NOT in the test fixture
    mvc.perform(MockMvcRequestBuilders.get("/vector/1/0/0"))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  @Test
  void getTile_highZoom_returns204(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/vector/20/0/0"))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  // ── POST /vector/preload ──────────────────────────────────────────────────

  @Test
  void postPreload_withoutAdminKey_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/vector/preload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":10}"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void postPreload_withWrongKey_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/vector/preload")
                .header(AdminKeyInterceptor.HEADER, "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":10}"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void postPreload_withAdminKey_returns202(@Autowired MockMvc mvc) throws Exception {
    // 202 Accepted: download starts asynchronously (pmtiles CLI will fail, but that's OK for this
    // test)
    mvc.perform(
            MockMvcRequestBuilders.post("/vector/preload")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":5}"))
        .andExpect(MockMvcResultMatchers.status().isAccepted());
  }

  @Test
  void postPreload_missingBoundingBox_returns400(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/vector/preload")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxZoom\":10}"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  void getTile_noBundledNorDownloads_returns204(@Autowired MockMvc mvc) throws Exception {
    // z=5,x=0,y=0 is not in the small test fixture
    mvc.perform(MockMvcRequestBuilders.get("/vector/5/0/0"))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }
}
