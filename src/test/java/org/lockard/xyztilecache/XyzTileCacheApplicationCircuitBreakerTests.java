package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.temporaryRedirect;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.store.LayerStore;
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
class XyzTileCacheApplicationCircuitBreakerTests {

  @TempDir static File tileDir;

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().gzipDisabled(true))
          .build();

  @Autowired LayerStore layerStore;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties layer = new LayerProperties();
          layer.setName("cb");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
  }

  @BeforeEach
  void clearCircuitBreaker() {
    // Tests share a Spring context; reset the per-layer block so each test sees a clean state.
    layerStore.getRuntimeState("cb").sourceSucceeded();
  }

  @Test
  void sourceBlockedAfterServerError(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/1/0/0")).willReturn(serverError()));

    // First request: upstream 500 → TileNotFound, layer enters BLOCK (100 ms window)
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    // Second immediate request: BLOCK state — source is not re-contacted, returns 503
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable());

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/1/0/0")));
  }

  @Test
  void upstream404DoesNotTripCircuitBreaker(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/3/0/1")).willReturn(notFound()));

    // Missing tiles are normal (ocean/sparse coverage): every request is answered 404 and the
    // source keeps being contacted instead of blocking the whole layer.
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/3/0/1.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/3/0/1.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    wireMock.verify(2, getRequestedFor(urlPathEqualTo("/3/0/1")));
  }

  @Test
  void emptyBodyFromSourceReturns404WithoutTrippingBreaker(@Autowired MockMvc mvc)
      throws Exception {
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/2/0/0")).willReturn(ok().withBody(new byte[0])));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/2/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/2/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    wireMock.verify(2, getRequestedFor(urlPathEqualTo("/2/0/0")));
  }

  @Test
  void upstreamRedirectIsFollowed(@Autowired MockMvc mvc) throws Exception {
    byte[] tileBytes = new byte[] {7, 7, 7};
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/4/0/0"))
            .willReturn(temporaryRedirect(wireMock.baseUrl() + "/moved-tile")));
    wireMock.stubFor(
        WireMock.get(urlPathEqualTo("/moved-tile")).willReturn(ok().withBody(tileBytes)));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/4/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().bytes(tileBytes));
  }

  @Test
  void outOfRangeCoordinatesRejectedWithoutUpstreamFetch(@Autowired MockMvc mvc) throws Exception {
    // Route is /tilesZYX/{layer}/{z}/{y}/{x}
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/-1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/2/0/9.png")) // x=9 >= 2^2
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/2/9/0.png")) // y=9 >= 2^2
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/31/0/0.png")) // z > 30
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    wireMock.verify(0, getRequestedFor(anyUrl()));
  }
}
