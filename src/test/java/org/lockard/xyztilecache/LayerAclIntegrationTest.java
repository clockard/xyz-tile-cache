package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class LayerAclIntegrationTest {

  @TempDir static File tileDir;

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
          Layer publicLayer = new Layer();
          publicLayer.setName("public-layer");
          publicLayer.setUrlTemplate(wireMock.baseUrl() + "/pub/{z}/{y}/{x}");

          Layer userOnly = new Layer();
          userOnly.setName("carol-only");
          userOnly.setUrlTemplate(wireMock.baseUrl() + "/carol/{z}/{y}/{x}");
          userOnly.setAllowedUsers(List.of("carol"));

          Layer groupOnly = new Layer();
          groupOnly.setName("foresters-only");
          groupOnly.setUrlTemplate(wireMock.baseUrl() + "/forest/{z}/{y}/{x}");
          groupOnly.setAllowedGroups(List.of("team-foresters"));

          return List.of(publicLayer, userOnly, groupOnly);
        });
  }

  @BeforeEach
  void stubTiles() {
    wireMock.stubFor(WireMock.get("/pub/0/0/0").willReturn(ok().withBody(new byte[] {1})));
    wireMock.stubFor(WireMock.get("/carol/0/0/0").willReturn(ok().withBody(new byte[] {2})));
    wireMock.stubFor(WireMock.get("/forest/0/0/0").willReturn(ok().withBody(new byte[] {3})));
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static RequestPostProcessor userJwt(String username, String... groups) {
    return jwt()
        .jwt(
            j -> {
              j.subject(username).claim("preferred_username", username);
              if (groups.length > 0) {
                j.claim("groups", List.of(groups));
              }
            });
  }

  // ── Tile endpoint ACL ─────────────────────────────────────────────────────

  @Test
  void tile_publicLayer_anonymous_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/public-layer/0/0/0.png")).andExpect(status().isOk());
  }

  @Test
  void tile_restrictedLayer_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/carol-only/0/0/0.png")).andExpect(status().isUnauthorized());
  }

  @Test
  void tile_restrictedLayer_disallowedUser_returns403(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/carol-only/0/0/0.png").with(userJwt("dan")))
        .andExpect(status().isForbidden());
  }

  @Test
  void tile_userRestrictedLayer_allowedUser_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/carol-only/0/0/0.png").with(userJwt("carol")))
        .andExpect(status().isOk());
  }

  @Test
  void tile_groupRestrictedLayer_userInGroup_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/foresters-only/0/0/0.png").with(userJwt("bob", "team-foresters")))
        .andExpect(status().isOk());
  }

  @Test
  void tile_groupRestrictedLayer_userNotInGroup_returns403(@Autowired MockMvc mvc)
      throws Exception {
    mvc.perform(get("/tilesZYX/foresters-only/0/0/0.png").with(userJwt("bob", "team-imagery")))
        .andExpect(status().isForbidden());
  }

  @Test
  void tile_restrictedLayer_admin_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/tilesZYX/carol-only/0/0/0.png").with(adminJwt())).andExpect(status().isOk());
  }

  // ── /layers list filtering ────────────────────────────────────────────────

  @Test
  void listLayers_anonymous_returnsOnlyPublic(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='public-layer')]").exists())
        .andExpect(jsonPath("$[?(@.name=='carol-only')]").doesNotExist())
        .andExpect(jsonPath("$[?(@.name=='foresters-only')]").doesNotExist());
  }

  @Test
  void listLayers_carol_seesPublicAndOwn(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers").with(userJwt("carol")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='public-layer')]").exists())
        .andExpect(jsonPath("$[?(@.name=='carol-only')]").exists())
        .andExpect(jsonPath("$[?(@.name=='foresters-only')]").doesNotExist());
  }

  @Test
  void listLayers_bobInForesters_seesPublicAndForesters(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers").with(userJwt("bob", "team-foresters")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='public-layer')]").exists())
        .andExpect(jsonPath("$[?(@.name=='foresters-only')]").exists())
        .andExpect(jsonPath("$[?(@.name=='carol-only')]").doesNotExist());
  }

  @Test
  void listLayers_admin_seesAll(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='public-layer')]").exists())
        .andExpect(jsonPath("$[?(@.name=='carol-only')]").exists())
        .andExpect(jsonPath("$[?(@.name=='foresters-only')]").exists());
  }
}
