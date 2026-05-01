package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
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

@SpringBootTest
@AutoConfigureMockMvc
class PreloadControllerTest {

  static final String ADMIN_KEY = "test-secret";

  @TempDir static File tileDir;

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().gzipDisabled(true))
          .build();

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.adminKey", () -> ADMIN_KEY);
    registry.add(
        "xyz.layers",
        () -> {
          Layer layer = new Layer();
          layer.setName("test");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
    // Disable vector path so no real download is attempted from the controller.
    registry.add("xyz.vector.downloadDirectory", () -> tileDir.getAbsolutePath() + "/vec");
    registry.add("xyz.vector.bundledPath", () -> "/nonexistent.pmtiles");
    registry.add("xyz.vector.sourceUrl", () -> wireMock.baseUrl() + "/vector.pmtiles");
  }

  private static String validBboxJson() {
    return "{\"north\":1,\"south\":0,\"east\":1,\"west\":0,\"maxZoom\":2}";
  }

  // ── Auth ───────────────────────────────────────────────────────────────────

  @Test
  void postPreloadWithoutAdminKey_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/preloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":"
                        + validBboxJson()
                        + ",\"maxZoom\":2,\"layers\":[\"test\"]}"))
        .andExpect(status().isUnauthorized());
  }

  // ── Validation ─────────────────────────────────────────────────────────────

  @Test
  void postPreloadWithoutBoundingBox_returns400(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/preloads")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxZoom\":2,\"layers\":[\"test\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postPreloadWithUnknownLayerAndNoVector_returns400(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/preloads")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":"
                        + validBboxJson()
                        + ",\"maxZoom\":2,\"layers\":[\"ghost\"]}"))
        .andExpect(status().isBadRequest());
  }

  // ── Happy path ─────────────────────────────────────────────────────────────

  @Test
  void postPreload_returns202_andAppearsInList(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/preloads")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"area-1\",\"boundingBox\":"
                        + validBboxJson()
                        + ",\"maxZoom\":2,\"layers\":[\"test\"]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.name").value("area-1"));

    mvc.perform(get("/preloads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='area-1')]").exists());
  }

  // ── Delete ─────────────────────────────────────────────────────────────────

  @Test
  void deleteUnknownPreload_returns404(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(delete("/preloads/does-not-exist").header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteCreatedPreload_returns204(@Autowired MockMvc mvc) throws Exception {
    String body =
        mvc.perform(
                post("/preloads")
                    .header(AdminKeyInterceptor.HEADER, ADMIN_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"deletable\",\"boundingBox\":"
                            + validBboxJson()
                            + ",\"maxZoom\":2,\"layers\":[\"test\"]}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    mvc.perform(delete("/preloads/" + id).header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isNoContent());
  }
}
