package org.lockard.xyztilecache.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** GET /stats reports the cached-tile totals held by the inventory, filtered by read access. */
@SpringBootTest
@AutoConfigureMockMvc
class StatsCachedTotalsTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;
  @Autowired TileInventoryStore inventory;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties open = new LayerProperties();
          open.setName("open");
          open.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");

          LayerProperties restricted = new LayerProperties();
          restricted.setName("restricted");
          restricted.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          restricted.setAllowedUsers(List.of("carol"));

          return List.of(open, restricted);
        });
  }

  static RequestPostProcessor userJwt(String name) {
    return jwt().jwt(j -> j.subject(name).claim("preferred_username", name));
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("root").claim("preferred_username", "root"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @BeforeEach
  void seedInventory() {
    inventory.recordAbsolute("open", 4, 400L);
    inventory.recordAbsolute("restricted", 6, 600L);
  }

  @Test
  void stats_reportsPerLayerCachedTilesAndBytes() throws Exception {
    mvc.perform(get("/stats").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layers[?(@.name=='open')].cachedTiles").value(4))
        .andExpect(jsonPath("$.layers[?(@.name=='open')].cachedBytes").value(400))
        .andExpect(jsonPath("$.layers[?(@.name=='restricted')].cachedTiles").value(6))
        .andExpect(jsonPath("$.layers[?(@.name=='restricted')].cachedBytes").value(600));
  }

  @Test
  void stats_totalsCoverEveryVisibleLayer() throws Exception {
    mvc.perform(get("/stats").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cachedTiles").value(10))
        .andExpect(jsonPath("$.cachedBytes").value(1000));
  }

  @Test
  void stats_totalsExcludeLayersTheCallerCannotRead() throws Exception {
    // Anonymous sees only the public layer, so the totals must not leak the restricted layer's
    // volume back through the sum.
    mvc.perform(get("/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cachedTiles").value(4))
        .andExpect(jsonPath("$.cachedBytes").value(400))
        .andExpect(jsonPath("$.layers[?(@.name=='restricted')]").doesNotExist());
  }

  @Test
  void stats_allowedUserSeesTheirLayerInTheTotals() throws Exception {
    mvc.perform(get("/stats").with(userJwt("carol")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cachedTiles").value(10))
        .andExpect(jsonPath("$.cachedBytes").value(1000));
  }
}
