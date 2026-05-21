package org.lockard.xyztilecache.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.model.Layer;
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
class SecurityConfigTokenModeCustomRoleTest {

  static final String ADMIN_TOKEN = "test-secret";

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.auth.mode", () -> "token");
    registry.add("xyz.auth.admin-token", () -> ADMIN_TOKEN);
    registry.add("xyz.adminRole", () -> "superuser");
    registry.add(
        "xyz.layers",
        () -> {
          Layer publicLayer = new Layer();
          publicLayer.setName("publicL");
          publicLayer.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          return List.of(publicLayer);
        });
  }

  @Test
  void deleteLayer_correctToken_passesAuthWithCustomAdminRole(@Autowired MockMvc mvc)
      throws Exception {
    mvc.perform(
            delete("/layers/nonexistent")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
        .andExpect(status().isNotFound());
  }

  @Test
  void postLayers_anonymous_returns401WithCustomAdminRole(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(post("/layers").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
