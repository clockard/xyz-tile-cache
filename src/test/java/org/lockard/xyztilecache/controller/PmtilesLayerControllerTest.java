package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class PmtilesLayerControllerTest {

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());

    URL fixture =
        PmtilesLayerControllerTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
    URL gzipFixture =
        PmtilesLayerControllerTest.class.getClassLoader().getResource("test_fixture_gzip.pmtiles");

    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties pmtilesLayer = new LayerProperties();
          pmtilesLayer.setId("vector-test");
          pmtilesLayer.setName("Vector Test");
          pmtilesLayer.setSourceType(Layer.SourceType.PMTILES);
          pmtilesLayer.setUrlTemplate(Paths.get(fixture.getPath()).toString());
          pmtilesLayer.setMaxZoom(14);

          LayerProperties gzipLayer = new LayerProperties();
          gzipLayer.setId("vector-gzip");
          gzipLayer.setName("Vector Gzip");
          gzipLayer.setSourceType(Layer.SourceType.PMTILES);
          gzipLayer.setUrlTemplate(Paths.get(gzipFixture.getPath()).toString());
          gzipLayer.setMaxZoom(14);

          LayerProperties privateLayer = new LayerProperties();
          privateLayer.setId("vector-private");
          privateLayer.setName("Vector Private");
          privateLayer.setSourceType(Layer.SourceType.PMTILES);
          privateLayer.setUrlTemplate(Paths.get(fixture.getPath()).toString());
          privateLayer.setMaxZoom(14);
          privateLayer.setAllowedUsers(List.of("alice"));

          return List.of(pmtilesLayer, gzipLayer, privateLayer);
        });
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static RequestPostProcessor nonAllowedJwt() {
    return jwt().jwt(j -> j.subject("bob").claim("preferred_username", "bob"));
  }

  // ── tilesZYX .mvt ────────────────────────────────────────────────────────

  @Test
  void getTile_presentTile_returnsTheArchivesOwnContentType(@Autowired MockMvc mvc)
      throws Exception {
    // z=0, x=0, y=0 is present in test_fixture_1.pmtiles, whose header declares tile_type 2 (PNG).
    // Every PMTiles response used to be labelled application/x-protobuf regardless of what the
    // archive held, so this fixture was served as protobuf despite containing raster tiles.
    // CORS headers now come from the global CorsFilter, which only engages when an Origin is
    // present, so send one rather than expecting the header on a same-origin request.
    mvc.perform(
            MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.mvt")
                .header("Origin", "http://example.com"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "image/png"))
        .andExpect(MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"))
        .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
  }

  @Test
  void getTile_pbfAlias_returns200(@Autowired MockMvc mvc) throws Exception {
    // The extension in the request path is decorative: the content type comes from the archive.
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.pbf"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "image/png"));
  }

  @Test
  void getTile_zxyVariant_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/vector-test/0/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void getTile_missingTile_returns204(@Autowired MockMvc mvc) throws Exception {
    // z=1, x=0, y=0 is NOT in test_fixture_1.pmtiles
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/1/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  @Test
  void getTile_zoomExceedsMax_returns404(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/15/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void getTile_unknownLayer_returns400(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/no-such-layer/0/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  void getTile_gzipCompressed_setsContentEncoding(@Autowired MockMvc mvc) throws Exception {
    // test_fixture_gzip.pmtiles declares tile_type 1 (MVT), so this one really is vector.
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-gzip/0/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Encoding", "gzip"))
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"));
  }

  @Test
  void getTile_privateLayer_withoutAuth_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-private/0/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void getTile_privateLayer_nonAllowedUser_returns403(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.get("/tilesZYX/vector-private/0/0/0.mvt").with(nonAllowedJwt()))
        .andExpect(MockMvcResultMatchers.status().isForbidden());
  }

  @Test
  void getTile_privateLayer_allowedUser_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-private/0/0/0.mvt").with(adminJwt()))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void anyExtension_onVectorLayer_returns200(@Autowired MockMvc mvc) throws Exception {
    // extension does not control routing; handler is selected by layer source type
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
