package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
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
class XyzTileCacheApplicationOnlineTests {

  @TempDir static File tileDir;

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().gzipDisabled(true))
          .build();

  @DynamicPropertySource
  static void testProperties(final DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          final var layer = new Layer();
          layer.setName("test");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
  }

  @Test
  void loadTileFromLayerSourceAndCacheResponse(@Autowired final MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get("/3/2/1").willReturn(ok().withBody(new byte[] {3, 2, 1})));
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/3/2/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().bytes(new byte[] {3, 2, 1}));
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/3/2/1.png"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().bytes(new byte[] {3, 2, 1}));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/3/2/1")));
  }

  @Test
  void return404WhenTileNotCachedOrFoundFromSource(@Autowired final MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get("/9/8/7").willReturn(notFound()));
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/9/8/7.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
