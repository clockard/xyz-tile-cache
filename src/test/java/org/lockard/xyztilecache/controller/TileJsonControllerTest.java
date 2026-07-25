package org.lockard.xyztilecache.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class TileJsonControllerTest {

  @TempDir static File tileDir;

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("admin").claim("preferred_username", "admin"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static RequestPostProcessor userJwt(String username) {
    return jwt().jwt(j -> j.subject(username).claim("preferred_username", username));
  }

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties pub = new LayerProperties();
          pub.setId("osm");
          pub.setName("OpenStreetMap");
          pub.setSourceType(Layer.SourceType.XYZ);
          pub.setUrlTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
          pub.setAttribution("© OSM contributors");
          pub.setMaxZoom(19);

          LayerProperties wmtsJpeg = new LayerProperties();
          wmtsJpeg.setId("nasa");
          wmtsJpeg.setName("NASA");
          wmtsJpeg.setSourceType(Layer.SourceType.WMTS_KVP);
          wmtsJpeg.setUrlTemplate("https://example.com/wmts");
          wmtsJpeg.setWmtsFormat("image/jpeg");
          wmtsJpeg.setMaxZoom(8);

          LayerProperties restricted = new LayerProperties();
          restricted.setId("restricted");
          restricted.setName("Restricted");
          restricted.setSourceType(Layer.SourceType.XYZ);
          restricted.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          restricted.setMaxZoom(10);
          restricted.setAllowedUsers(List.of("alice"));

          return List.of(pub, wmtsJpeg, restricted);
        });
  }

  @Test
  void publicLayer_anonymous_returnsTileJson(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/osm/tilejson"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.tilejson").value("3.0.0"))
        .andExpect(jsonPath("$.name").value("OpenStreetMap"))
        .andExpect(jsonPath("$.attribution").value("© OSM contributors"))
        .andExpect(jsonPath("$.scheme").value("xyz"))
        .andExpect(jsonPath("$.minzoom").value(0))
        .andExpect(jsonPath("$.maxzoom").value(19))
        .andExpect(jsonPath("$.format").value("png"))
        .andExpect(
            jsonPath("$.tiles[0]")
                .value(org.hamcrest.Matchers.endsWith("/tilesZXY/osm/{z}/{x}/{y}.png")))
        .andExpect(jsonPath("$.bounds.length()").value(4))
        .andExpect(jsonPath("$.center.length()").value(3));
  }

  @Test
  void wmtsJpegLayer_formatIsJpg(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/nasa/tilejson"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("jpg"))
        .andExpect(jsonPath("$.tiles[0]").value(org.hamcrest.Matchers.endsWith(".jpg")))
        .andExpect(jsonPath("$.maxzoom").value(8));
  }

  @Test
  void unknownLayer_returns404(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/does-not-exist/tilejson")).andExpect(status().isNotFound());
  }

  @Test
  void restrictedLayer_anonymous_returns401(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/restricted/tilejson")).andExpect(status().isUnauthorized());
  }

  @Test
  void restrictedLayer_wrongUser_returns403(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/restricted/tilejson").with(userJwt("bob")))
        .andExpect(status().isForbidden());
  }

  @Test
  void restrictedLayer_allowedUser_returnsTileJson(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/restricted/tilejson").with(userJwt("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Restricted"));
  }

  @Test
  void restrictedLayer_admin_returnsTileJson(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/layers/restricted/tilejson").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Restricted"));
  }
}
