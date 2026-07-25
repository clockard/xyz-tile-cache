package org.lockard.xyztilecache.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
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

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
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
  void getStats_anonymous_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/stats")).andExpect(status().isOk());
  }

  @Test
  void postLayers_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(post("/layers").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void postLayers_nonAdminJwt_returns403(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/layers")
                .with(jwt().jwt(j -> j.subject("dan").claim("preferred_username", "dan")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteLayer_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(delete("/layers/anything")).andExpect(status().isUnauthorized());
  }

  @Test
  void deleteLayer_adminJwt_passesAuth(@Autowired MockMvc mvc) throws Exception {
    // Layer doesn't exist, so we expect 404 (not 401/403). Confirms admin role passes auth gate.
    mvc.perform(
            delete("/layers/nonexistent")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void preload_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(post("/preload").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authConfig_anonymous_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/auth/config")).andExpect(status().isOk());
  }
}
