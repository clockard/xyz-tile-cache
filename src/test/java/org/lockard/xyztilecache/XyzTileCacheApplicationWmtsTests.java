package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationWmtsTests {

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
          LayerProperties wmtsRest = new LayerProperties();
          wmtsRest.setName("wmts-rest");
          wmtsRest.setSourceType(Layer.SourceType.WMTS_REST);
          wmtsRest.setUrlTemplate(wireMock.baseUrl() + "/{TileMatrix}/{TileRow}/{TileCol}");

          LayerProperties wmtsKvp = new LayerProperties();
          wmtsKvp.setName("wmts-kvp");
          wmtsKvp.setSourceType(Layer.SourceType.WMTS_KVP);
          wmtsKvp.setUrlTemplate(wireMock.baseUrl() + "/wmts");
          wmtsKvp.setWmtsLayerName("TestLayer");
          wmtsKvp.setWmtsTileMatrixSet("EPSG:3857");
          wmtsKvp.setWmtsStyle("default");
          wmtsKvp.setWmtsFormat("image/png");

          return List.of(wmtsRest, wmtsKvp);
        });
  }

  @Test
  void wmtsRest_substitutesTileMatrixRowCol(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get("/3/2/1").willReturn(ok().withBody(new byte[] {1, 2, 3})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/wmts-rest/3/2/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    // z→TileMatrix, y→TileRow, x→TileCol
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/3/2/1")));
  }

  @Test
  void wmtsKvp_buildsKvpQueryString(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/wmts"))
            .withQueryParam("SERVICE", equalTo("WMTS"))
            .withQueryParam("REQUEST", equalTo("GetTile"))
            .withQueryParam("LAYER", equalTo("TestLayer"))
            .withQueryParam("TILEMATRIXSET", equalTo("EPSG:3857"))
            .withQueryParam("TILEMATRIX", equalTo("3"))
            .withQueryParam("TILEROW", equalTo("2"))
            .withQueryParam("TILECOL", equalTo("1"))
            .willReturn(ok().withBody(new byte[] {4, 5, 6})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/wmts-kvp/3/2/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
