package org.lockard.xyztilecache;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GeoTiffControllerTest {

  static final String ADMIN_KEY = "test-secret";

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.adminKey", () -> ADMIN_KEY);
    registry.add(
        "xyz.layers",
        () -> {
          Layer existing = new Layer();
          existing.setName("existing");
          existing.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          return List.of(existing);
        });
  }

  @TestConfiguration
  static class MockTilerConfig {
    @Bean
    @Primary
    GeoTiffTiler stubTiler() {
      return Mockito.mock(GeoTiffTiler.class);
    }
  }

  @Autowired GeoTiffTiler tiler;

  @BeforeEach
  void resetTilerStub() throws Exception {
    Mockito.reset(tiler);
    Mockito.when(tiler.tile(any(Path.class), any(Path.class)))
        .thenAnswer(
            inv -> {
              Path output = inv.getArgument(1);
              Files.createDirectories(output.resolve("0").resolve("0"));
              Files.write(output.resolve("0").resolve("0").resolve("0.png"), new byte[] {1, 2});
              return new GeoTiffTiler.Result(0, 2L, 1L);
            });
  }

  @Test
  void uploadGeoTiff_withoutAdminKey_returns401(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "no-auth"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void uploadGeoTiff_withInvalidName_returns400(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "../bad")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadGeoTiff_withEmptyFile_returns400(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile empty = new MockMultipartFile("file", "input.tif", "image/tiff", new byte[0]);
    mvc.perform(
            multipart("/layers/geotiff")
                .file(empty)
                .param("name", "ok-name")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadGeoTiff_whenLayerExists_returns409(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "existing")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isConflict());
  }

  @Test
  void uploadGeoTiff_whenOutputDirAlreadyExists_returns409(@Autowired MockMvc mvc)
      throws Exception {
    Files.createDirectories(Paths.get(tileDir.getAbsolutePath(), "stale-dir"));
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "stale-dir")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isConflict());
  }

  @Test
  void uploadGeoTiff_whenTilerFails_returns422AndCleansUp(@Autowired MockMvc mvc) throws Exception {
    Mockito.reset(tiler);
    Mockito.when(tiler.tile(any(Path.class), any(Path.class)))
        .thenThrow(new IOException("gdal2tiles boom"));
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "fails-tiling")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isUnprocessableEntity());
    // Output dir should have been cleaned up so a retry doesn't 409 on dir-exists.
    org.assertj.core.api.Assertions.assertThat(Paths.get(tileDir.getAbsolutePath(), "fails-tiling"))
        .doesNotExist();
  }

  @Test
  void uploadGeoTiff_happyPath_returns201AndRegistersLayer(@Autowired MockMvc mvc)
      throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3, 4});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "uploaded")
                .header(AdminKeyInterceptor.HEADER, ADMIN_KEY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("uploaded"))
        .andExpect(jsonPath("$.sourceType").value("LOCAL"))
        .andExpect(jsonPath("$.maxZoom").value(0));
  }
}
