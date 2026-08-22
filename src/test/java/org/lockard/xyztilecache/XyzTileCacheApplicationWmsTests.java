package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

/** Serves WMS-backed tiles end to end: request shape, caching, and the 1.1.1 dialect. */
@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationWmsTests {

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
          LayerProperties wms = new LayerProperties();
          wms.setName("wms");
          wms.setSourceType(Layer.SourceType.WMS);
          wms.setUrlTemplate(wireMock.baseUrl() + "/geoserver/wms");
          wms.setWmsLayers("topp:states");

          LayerProperties legacy = new LayerProperties();
          legacy.setName("wms-111");
          legacy.setSourceType(Layer.SourceType.WMS);
          legacy.setUrlTemplate(wireMock.baseUrl() + "/legacy/wms");
          legacy.setWmsLayers("roads");
          legacy.setWmsVersion("1.1.1");

          LayerProperties jpeg = new LayerProperties();
          jpeg.setName("wms-jpeg");
          jpeg.setSourceType(Layer.SourceType.WMS);
          jpeg.setUrlTemplate(wireMock.baseUrl() + "/aerial/wms");
          jpeg.setWmsLayers("imagery");
          jpeg.setWmsFormat("image/jpeg");
          jpeg.setWmsExtraParams(Map.of("CQL_FILTER", "state = 'CO'"));

          return List.of(wms, legacy, jpeg);
        });
  }

  @Test
  void wms_buildsAGetMapRequestForTheTileExtent(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/geoserver/wms"))
            .withQueryParam("SERVICE", equalTo("WMS"))
            .withQueryParam("REQUEST", equalTo("GetMap"))
            .withQueryParam("VERSION", equalTo("1.3.0"))
            .withQueryParam("LAYERS", equalTo("topp:states"))
            .withQueryParam("CRS", equalTo("EPSG:3857"))
            .withQueryParam("WIDTH", equalTo("256"))
            .withQueryParam("HEIGHT", equalTo("256"))
            // Tile 1/1/0 is the north-east quadrant: x from 0 to +half, y from 0 to +half.
            .withQueryParam("BBOX", matching("0\\.0+,0\\.0+,20037508\\.34.*,20037508\\.34.*"))
            .willReturn(ok().withBody(new byte[] {1, 2, 3})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms/1/1/0.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/geoserver/wms")));
  }

  @Test
  void wms_version111_sendsSrsInsteadOfCrs(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/legacy/wms"))
            .withQueryParam("VERSION", equalTo("1.1.1"))
            .withQueryParam("SRS", equalTo("EPSG:3857"))
            .willReturn(ok().withBody(new byte[] {4, 5, 6})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms-111/2/1/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/legacy/wms")));
  }

  @Test
  void wms_passesVendorParametersThrough(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/aerial/wms"))
            .withQueryParam("FORMAT", equalTo("image/jpeg"))
            .withQueryParam("CQL_FILTER", equalTo("state = 'CO'"))
            .willReturn(ok().withBody(new byte[] {7, 8, 9})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms-jpeg/3/2/2.jpg"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/aerial/wms")));
  }

  @Test
  void wms_cachesTheTileUnderTheFormatsExtension(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/aerial/wms"))
            .willReturn(ok().withBody(new byte[] {7, 8, 9})));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms-jpeg/4/3/5.jpg"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    // The write is @Async; give it a moment to land.
    Path tile = tileDir.toPath().resolve(Path.of("wms-jpeg", "4", "3", "5.jpg"));
    for (int i = 0; i < 50 && !Files.exists(tile); i++) {
      Thread.sleep(20);
    }
    assertThat(tile).exists();
  }

  @Test
  void wms_secondRequestIsServedFromCache(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/geoserver/wms"))
            .willReturn(ok().withBody(new byte[] {1, 2, 3})));
    // Count only this test's traffic: the journal is shared across the class.
    wireMock.resetRequests();

    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms/5/9/9.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/wms/5/9/9.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());

    // Rendering a GetMap is expensive upstream, so the second serve must come from cache.
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/geoserver/wms")));
  }
}
