package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationOnlineTests {

  @TempDir static File tileDir;

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

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
          final var layer = new LayerProperties();
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

  @Test
  void loadTileFromDiskCacheWithoutHittingSource(@Autowired final MockMvc mvc) throws Exception {
    // Pre-populate the disk cache for tile z=4,y=4,x=4
    Path tilePath = tileDir.toPath().resolve(Path.of("test", "4", "4", "4.png"));
    Files.createDirectories(tilePath.getParent());
    Files.write(tilePath, new byte[] {9, 8, 7});

    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/4/4/4.png"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().bytes(new byte[] {9, 8, 7}));

    // Source must not have been contacted
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/4/4/4")));
  }

  @Test
  void return400WhenLayerIsUnknown(@Autowired final MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/no-such-layer/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  void return404WhenZoomExceedsMaxZoom(@Autowired final MockMvc mvc) throws Exception {
    // Default maxZoom for Layer is 22; request z=23 to exceed it
    mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/23/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void tilesZXYEndpointDelegatesToZYX(@Autowired final MockMvc mvc) throws Exception {
    wireMock.stubFor(WireMock.get("/1/0/0").willReturn(ok().withBody(new byte[] {1})));
    mvc.perform(MockMvcRequestBuilders.get("/tilesZXY/test/1/0/0.png"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void getLayersReturnsConfiguredLayers(@Autowired final MockMvc mvc) throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/layers"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(
            MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(MockMvcResultMatchers.jsonPath("$[0].name").value("test"));
  }

  @Test
  void preloadReturns200ForValidLayer(@Autowired final MockMvc mvc) throws Exception {
    String body =
        "{\"layers\":[\"test\"],"
            + "\"boundingBox\":{\"north\":1,\"south\":-1,\"east\":1,\"west\":-1,"
            + "\"maxZoom\":0,\"preCache\":false}}";
    mvc.perform(
            MockMvcRequestBuilders.post("/preload")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void preloadReturns200ForUnknownLayer(@Autowired final MockMvc mvc) throws Exception {
    String body =
        "{\"layers\":[\"nonexistent\"],"
            + "\"boundingBox\":{\"north\":1,\"south\":-1,\"east\":1,\"west\":-1,"
            + "\"maxZoom\":0,\"preCache\":false}}";
    mvc.perform(
            MockMvcRequestBuilders.post("/preload")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void offlineModeRuntimeToggleBlocksOnlineFetch(@Autowired final MockMvc mvc) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.put("/config/offline")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"offline\":true}"))
        .andExpect(MockMvcResultMatchers.status().isOk());
    try {
      wireMock.stubFor(WireMock.get("/6/6/6").willReturn(ok().withBody(new byte[] {1})));
      mvc.perform(MockMvcRequestBuilders.get("/tilesZYX/test/6/6/6.png"))
          .andExpect(MockMvcResultMatchers.status().isNotFound());
      wireMock.verify(0, getRequestedFor(urlPathEqualTo("/6/6/6")));
    } finally {
      mvc.perform(
              MockMvcRequestBuilders.put("/config/offline")
                  .with(adminJwt())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"offline\":false}"))
          .andExpect(MockMvcResultMatchers.status().isOk());
    }
  }
}
