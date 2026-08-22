package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** A WMS layer created over the JSON API must survive the trip through layers.json. */
@SpringBootTest
@AutoConfigureMockMvc
class WmsLayerControllerTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", List::of);
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("root").claim("preferred_username", "root"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  void postWmsLayer_isPersistedAndReadBackWithItsWmsFields() throws Exception {
    String body =
        """
        {
          "id": "nowcoast",
          "name": "NOAA Radar",
          "sourceType": "WMS",
          "urlTemplate": "https://example.com/geoserver/ows",
          "wmsLayers": "radar:reflectivity",
          "wmsFormat": "image/png",
          "wmsVersion": "1.1.1",
          "wmsTransparent": true,
          "wmsTileSize": 512,
          "maxZoom": 12
        }
        """;

    mvc.perform(
            post("/layers").contentType(MediaType.APPLICATION_JSON).content(body).with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(get("/layers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='nowcoast')].sourceType").value("WMS"))
        .andExpect(jsonPath("$[?(@.id=='nowcoast')].wmsLayers").value("radar:reflectivity"))
        .andExpect(jsonPath("$[?(@.id=='nowcoast')].wmsVersion").value("1.1.1"))
        .andExpect(jsonPath("$[?(@.id=='nowcoast')].wmsTileSize").value(512))
        .andExpect(jsonPath("$[?(@.id=='nowcoast')].maxZoom").value(12));

    // The discriminator has to be written too, or the layer deserialises as XYZ on restart.
    String persisted = Files.readString(tileDir.toPath().resolve("layers.json"));
    assertThat(persisted).contains("\"sourceType\" : \"WMS\"");
    assertThat(persisted).contains("radar:reflectivity");
  }

  @Test
  void postWmsLayer_omittedFieldsComeBackWithDefaults() throws Exception {
    String body =
        """
        {
          "id": "sparse",
          "name": "Sparse WMS",
          "sourceType": "WMS",
          "urlTemplate": "https://example.com/wms",
          "wmsLayers": "roads"
        }
        """;

    mvc.perform(
            post("/layers").contentType(MediaType.APPLICATION_JSON).content(body).with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(get("/layers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='sparse')].wmsVersion").value("1.3.0"))
        .andExpect(jsonPath("$[?(@.id=='sparse')].wmsFormat").value("image/png"))
        .andExpect(jsonPath("$[?(@.id=='sparse')].wmsTileSize").value(256))
        .andExpect(jsonPath("$[?(@.id=='sparse')].maxZoom").value(22));
  }

  @Test
  void postWmsLayer_withoutUrlTemplate_isRejected() throws Exception {
    String body =
        """
        { "id": "nourl", "name": "No URL", "sourceType": "WMS", "wmsLayers": "roads" }
        """;

    mvc.perform(
            post("/layers").contentType(MediaType.APPLICATION_JSON).content(body).with(adminJwt()))
        .andExpect(status().isBadRequest());
  }
}
