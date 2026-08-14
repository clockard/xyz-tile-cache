package org.lockard.xyztilecache.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        .andExpect(jsonPath("$.mode").value("token"))
        .andExpect(jsonPath("$.adminRole").value("admin"));
  }

  // ── preload endpoints ─────────────────────────────────────────────────────

  @Test
  void deletePreload_correctToken_passesAuth(@Autowired MockMvc mvc) throws Exception {
    // Preload doesn't exist, so 404 (not 401/403) confirms the admin token cleared the auth gate.
    mvc.perform(
            delete("/preloads/00000000-0000-0000-0000-000000000000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletePreload_lowercaseBearerScheme_passesAuth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            delete("/preloads/00000000-0000-0000-0000-000000000000")
                .header(HttpHeaders.AUTHORIZATION, "bearer " + ADMIN_TOKEN))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletePreload_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(delete("/preloads/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deletePreload_wrongToken_returns401WithInvalidTokenError(@Autowired MockMvc mvc)
      throws Exception {
    mvc.perform(
            delete("/preloads/00000000-0000-0000-0000-000000000000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("WWW-Authenticate"))
                    .contains("error=\"invalid_token\""));
  }

  // ── CORS preflight ────────────────────────────────────────────────────────

  @Test
  void preflightDeletePreload_returns200WithoutAuth(@Autowired MockMvc mvc) throws Exception {
    // Browsers never attach Authorization to a preflight, so it must be answered before the
    // authorization rules run or every cross-origin DELETE fails 401.
    mvc.perform(
            options("/preloads/00000000-0000-0000-0000-000000000000")
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }

  @Test
  void deletePreload_withOrigin_carriesSingleCorsHeader(@Autowired MockMvc mvc) throws Exception {
    // Two Access-Control-Allow-Origin headers (filter + hand-set) would make browsers reject the
    // response, so the actual request must carry exactly one.
    mvc.perform(
            delete("/preloads/00000000-0000-0000-0000-000000000000")
                .header("Origin", "http://example.com")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
        .andExpect(status().isNotFound())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeaders("Access-Control-Allow-Origin"))
                    .containsExactly("*"));
  }
}
