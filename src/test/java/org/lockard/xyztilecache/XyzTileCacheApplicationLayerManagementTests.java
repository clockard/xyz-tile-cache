package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationLayerManagementTests {

  @TempDir static File tileDir;

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static RequestPostProcessor nonAdminJwt() {
    return jwt().jwt(j -> j.subject("dan").claim("preferred_username", "dan"));
  }

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().gzipDisabled(true))
          .build();

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties layer = new LayerProperties();
          layer.setName("existing");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
  }

  // ── Auth tests ────────────────────────────────────────────────────────────

  @Test
  void postLayerWithoutJwt_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/layers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void postLayerWithNonAdminJwt_returns403(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/layers")
                .with(nonAdminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isForbidden());
  }

  // ── Layer CRUD tests ──────────────────────────────────────────────────────

  @Test
  void addLayer_returns201AndAppearsInGetLayers(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/layers")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"added\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("added"));

    mvc.perform(MockMvcRequestBuilders.get("/layers"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$[?(@.name=='added')]").exists());
  }

  @Test
  void addDuplicateLayer_returns409(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/layers")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"existing\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isConflict());
  }

  @Test
  void updateLayer_returns200WithUpdatedLayer(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.put("/layers/existing")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"existing\",\"urlTemplate\":\"https://updated.co/{z}/{x}/{y}.png\",\"maxZoom\":12}"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.maxZoom").value(12));
  }

  @Test
  void updateUnknownLayer_returns404(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.put("/layers/no-such-layer")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"no-such-layer\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void deleteLayer_returns204AndDisappearsFromGetLayers(@Autowired MockMvc mvc) throws Exception {
    // Add a layer to delete so we don't remove "existing" from other tests
    mvc.perform(
            MockMvcRequestBuilders.post("/layers")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"to-delete\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(MockMvcResultMatchers.status().isCreated());

    mvc.perform(MockMvcRequestBuilders.delete("/layers/to-delete").with(adminJwt()))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    mvc.perform(MockMvcRequestBuilders.get("/layers"))
        .andExpect(MockMvcResultMatchers.jsonPath("$[?(@.name=='to-delete')]").doesNotExist());
  }

  @Test
  void deleteUnknownLayer_returns404(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.delete("/layers/ghost").with(adminJwt()))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  // ── Stats tests ───────────────────────────────────────────────────────────

  @Test
  void getStats_returnsExpectedShape(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/stats"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.instanceId").isString())
        .andExpect(MockMvcResultMatchers.jsonPath("$.tilesServedByInstance").isNumber())
        .andExpect(MockMvcResultMatchers.jsonPath("$.diskFreeBytes").isNumber())
        .andExpect(MockMvcResultMatchers.jsonPath("$.layers").isArray());
  }
}
