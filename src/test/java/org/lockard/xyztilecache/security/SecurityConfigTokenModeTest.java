package org.lockard.xyztilecache.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTokenModeTest {

  static final String ADMIN_TOKEN = "test-secret";

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.auth.mode", () -> "token");
    registry.add("xyz.auth.admin-token", () -> ADMIN_TOKEN);
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties publicLayer = new LayerProperties();
          publicLayer.setName("publicL");
          publicLayer.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          return List.of(publicLayer);
        });
  }

  @Test
  void getLayers_anonymous_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers")).andExpect(status().isOk());
  }

  @Test
  void postLayers_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(post("/layers").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void postLayers_wrongToken_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/layers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deleteLayer_correctToken_passesAuth(@Autowired MockMvc mvc) throws Exception {
    // Layer doesn't exist, so we expect 404 (not 401/403). Confirms admin token passes auth gate.
    mvc.perform(
            delete("/layers/nonexistent")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
        .andExpect(status().isNotFound());
  }

  @Test
  void postLayers_blankBearer_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/layers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authConfig_returnsTokenMode(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("token"));
  }
}
