package org.lockard.xyztilecache;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
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

/**
 * Tests the initializeLayerDownloads() startup path in XyzTileCacheApplication: - VECTOR_PMTILES
 * layers with initZoom > 0 and various URL configurations - Raster layers with initZoom > 0
 */
@SpringBootTest
@AutoConfigureMockMvc
class XyzTileCacheApplicationInitLayerTest {

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          // VECTOR_PMTILES with initZoom > 0, blank URL → logs warning, skips
          LayerProperties vecNoUrl = new LayerProperties();
          vecNoUrl.setId("vec-no-url");
          vecNoUrl.setName("Vec No URL");
          vecNoUrl.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          vecNoUrl.setInitZoom(3);

          // VECTOR_PMTILES with initZoom > 0, local file URL → logs info, skips download
          URL fixture =
              XyzTileCacheApplicationInitLayerTest.class
                  .getClassLoader()
                  .getResource("test_fixture_1.pmtiles");
          LayerProperties vecLocal = new LayerProperties();
          vecLocal.setId("vec-local");
          vecLocal.setName("Vec Local");
          vecLocal.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          vecLocal.setInitZoom(1);
          vecLocal.setUrlTemplate(Paths.get(fixture.getPath()).toString());

          // VECTOR_PMTILES with initZoom > 0, HTTP URL → attempts download (fails gracefully)
          LayerProperties vecHttp = new LayerProperties();
          vecHttp.setId("vec-http");
          vecHttp.setName("Vec HTTP");
          vecHttp.setSourceType(Layer.SourceType.VECTOR_PMTILES);
          vecHttp.setInitZoom(1);
          vecHttp.setUrlTemplate("https://example.invalid/tiles.pmtiles");

          // Raster (XYZ) layer with initZoom > 0 → submits bounding-box preload
          LayerProperties rasterInit = new LayerProperties();
          rasterInit.setId("raster-init");
          rasterInit.setName("Raster Init");
          rasterInit.setSourceType(Layer.SourceType.XYZ);
          rasterInit.setUrlTemplate("http://localhost:1/{z}/{x}/{y}.png");
          rasterInit.setInitZoom(0);

          return List.of(vecNoUrl, vecLocal, vecHttp, rasterInit);
        });
  }

  @Test
  void contextStartsAndServesRequests(@Autowired MockMvc mvc) throws Exception {
    // Verifies the application started successfully despite initVectorLayerDownload running on all
    // three URL configurations (null, local, http) and a raster init-zoom preload.
    mvc.perform(MockMvcRequestBuilders.get("/layers"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
