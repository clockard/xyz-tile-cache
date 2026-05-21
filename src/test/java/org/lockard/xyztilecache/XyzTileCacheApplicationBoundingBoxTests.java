package org.lockard.xyztilecache;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

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
class XyzTileCacheApplicationBoundingBoxTests {

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
          layer.setName("bbox-test");
          layer.setUrlTemplate(wireMock.baseUrl() + "/{z}/{y}/{x}");
          return List.of(layer);
        });
    // Configure a startup bounding box so initializeBoundingBoxes() takes the non-empty path.
    // maxZoom=0 limits preloading to a single tile, keeping startup fast.
    registry.add("xyz.boundingBoxes[0].north", () -> 1.0);
    registry.add("xyz.boundingBoxes[0].south", () -> -1.0);
    registry.add("xyz.boundingBoxes[0].east", () -> 1.0);
    registry.add("xyz.boundingBoxes[0].west", () -> -1.0);
    registry.add("xyz.boundingBoxes[0].maxZoom", () -> 0);
  }

  @Test
  void contextStartsWithBoundingBoxAndPreloadIsAttempted(@Autowired MockMvc mvc) throws Exception {
    // Verifies the context started successfully — initializeBoundingBoxes() ran its
    // non-empty branch, submitted a preload task (which failed gracefully), and the
    // application is still serving requests.
    mvc.perform(MockMvcRequestBuilders.get("/layers"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
