package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class VectorPmtilesLayerControllerTest {

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());

    URL fixture =
        VectorPmtilesLayerControllerTest.class
            .getClassLoader()
            .getResource("test_fixture_1.pmtiles");
    URL gzipFixture =
        VectorPmtilesLayerControllerTest.class
            .getClassLoader()
            .getResource("test_fixture_gzip.pmtiles");

    registry.add(
        "xyz.layers",
        () -> {
          Layer vectorLayer = new Layer();
          vectorLayer.setId("vector-test");
          vectorLayer.setName("Vector Test");
          vectorLayer.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          vectorLayer.setUrlTemplate(Paths.get(fixture.getPath()).toString());
          vectorLayer.setMaxZoom(14);

          Layer gzipLayer = new Layer();
          gzipLayer.setId("vector-gzip");
          gzipLayer.setName("Vector Gzip");
          gzipLayer.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          gzipLayer.setUrlTemplate(Paths.get(gzipFixture.getPath()).toString());
          gzipLayer.setMaxZoom(14);

          Layer privateLayer = new Layer();
          privateLayer.setId("vector-private");
          privateLayer.setName("Vector Private");
          privateLayer.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          privateLayer.setUrlTemplate(Paths.get(fixture.getPath()).toString());
          privateLayer.setMaxZoom(14);
          privateLayer.setAllowedUsers(List.of("alice"));

          return List.of(vectorLayer, gzipLayer, privateLayer);
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
  void getTile_presentTile_returns200WithProtobufType(@Autowired MockMvc mvc) throws Exception {
    // z=0, x=0, y=0 is present in test_fixture_1.pmtiles
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.mvt"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"))
        .andExpect(MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"))
        .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
  }

  @Test
  void getTile_pbfAlias_returns200(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.pbf"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string("Content-Type", "application/x-protobuf"));
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
  void rasterEndpoint_returnsNotFound_forVectorLayer(@Autowired MockMvc mvc) throws Exception {
    // .png on a VECTOR_PMTILES layer should return 404 (cache loader throws)
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/vector-test/0/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
