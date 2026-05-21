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
class XyzTileCacheApplicationCircuitBreakerTests {

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
          Layer layer = new Layer();
          layer.setName("cb");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
  }

  @Test
  void sourceBlockedAfterFailure(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/1/0/0")).willReturn(notFound()));

    // First request: tile not found → layer enters BLOCK state (100 ms window)
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    // Second immediate request: BLOCK state — source is not contacted again
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/1/0/0")));
  }

  @Test
  void emptyBodyFromSourceReturns404(@Autowired MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/2/0/0")).willReturn(ok().withBody(new byte[0])));

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/cb/2/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
