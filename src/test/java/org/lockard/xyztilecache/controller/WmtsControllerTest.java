package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class WmtsControllerTest {

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
          pub.setAttribution("© OSM <contributors>");
          pub.setMaxZoom(19);

          LayerProperties restricted = new LayerProperties();
          restricted.setId("restricted");
          restricted.setName("Restricted");
          restricted.setSourceType(Layer.SourceType.XYZ);
          restricted.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          restricted.setMaxZoom(10);
          restricted.setAllowedUsers(List.of("alice"));

          return List.of(pub, restricted);
        });
  }

  @Test
  void restPath_anonymous_returnsCapabilitiesXml(@Autowired MockMvc mvc) throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts/1.0.0/WMTSCapabilities.xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andReturn();
    String xml = result.getResponse().getContentAsString();
    assertThat(xml).startsWith("<?xml");
    assertThat(xml).contains("<Capabilities");
    assertThat(xml).contains("version=\"1.0.0\"");
    assertThat(xml).contains("<ows:Identifier>osm</ows:Identifier>");
    assertThat(xml).doesNotContain("<ows:Identifier>restricted</ows:Identifier>");
    assertThat(xml).contains("GoogleMapsCompatible");
    assertThat(xml).contains("urn:ogc:def:crs:EPSG::3857");
    assertThat(xml).contains("/tilesZXY/osm/{TileMatrix}/{TileCol}/{TileRow}.png");
    assertThat(xml).contains("&lt;contributors&gt;");
  }

  @Test
  void kvpPath_anonymous_returnsCapabilitiesXml(@Autowired MockMvc mvc) throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts?service=WMTS&request=GetCapabilities"))
            .andExpect(status().isOk())
            .andReturn();
    String xml = result.getResponse().getContentAsString();
    assertThat(xml).contains("<ows:Identifier>osm</ows:Identifier>");
  }

  @Test
  void kvpPath_unsupportedRequest_returnsBadRequest(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/wmts?service=WMTS&request=GetTile"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("OperationNotSupported")));
  }

  @Test
  void kvpPath_wrongService_returnsBadRequest(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/wmts?service=WMS"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("InvalidParameterValue")));
  }

  @Test
  void restrictedLayer_authorizedUser_appearsInCapabilities(@Autowired MockMvc mvc)
      throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts/1.0.0/WMTSCapabilities.xml").with(userJwt("alice")))
            .andExpect(status().isOk())
            .andReturn();
    String xml = result.getResponse().getContentAsString();
    assertThat(xml).contains("<ows:Identifier>restricted</ows:Identifier>");
    assertThat(xml).contains("<ows:Identifier>osm</ows:Identifier>");
  }

  @Test
  void restrictedLayer_adminUser_appearsInCapabilities(@Autowired MockMvc mvc) throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts/1.0.0/WMTSCapabilities.xml").with(adminJwt()))
            .andExpect(status().isOk())
            .andReturn();
    String xml = result.getResponse().getContentAsString();
    assertThat(xml).contains("<ows:Identifier>restricted</ows:Identifier>");
  }

  @Test
  void capabilities_includesTileMatrixZoom0Through22(@Autowired MockMvc mvc) throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts/1.0.0/WMTSCapabilities.xml")).andExpect(status().isOk()).andReturn();
    String xml = result.getResponse().getContentAsString();
    for (int z = 0; z <= 22; z++) {
      assertThat(xml).contains("<ows:Identifier>" + z + "</ows:Identifier>");
    }
  }

  @Test
  void restrictedLayerSubzoom_emitsTileMatrixSetLimits(@Autowired MockMvc mvc) throws Exception {
    MvcResult result =
        mvc.perform(get("/wmts/1.0.0/WMTSCapabilities.xml").with(adminJwt()))
            .andExpect(status().isOk())
            .andReturn();
    String xml = result.getResponse().getContentAsString();
    assertThat(xml).contains("<TileMatrixSetLimits>");
  }
}
