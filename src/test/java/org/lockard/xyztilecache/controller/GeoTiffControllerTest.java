package org.lockard.xyztilecache.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.service.GeoTiffTiler;
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

  @TempDir static File tileDir;

  static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
    return jwt().jwt(j -> j.subject("alice").claim("preferred_username", "alice"));
  }

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties existing = new LayerProperties();
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
  void uploadGeoTiff_withoutJwt_returns401(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "no-auth"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void uploadGeoTiff_withInvalidName_returns400(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "../bad").with(userJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadGeoTiff_withEmptyFile_returns400(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile empty = new MockMultipartFile("file", "input.tif", "image/tiff", new byte[0]);
    mvc.perform(multipart("/layers/geotiff").file(empty).param("name", "ok-name").with(userJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadGeoTiff_whenLayerExists_returns409(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "existing").with(userJwt()))
        .andExpect(status().isConflict());
  }

  @Test
  void uploadGeoTiff_whenOutputDirAlreadyExists_returns409(@Autowired MockMvc mvc)
      throws Exception {
    Files.createDirectories(Paths.get(tileDir.getAbsolutePath(), "stale-dir"));
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "stale-dir").with(userJwt()))
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
            multipart("/layers/geotiff").file(file).param("name", "fails-tiling").with(userJwt()))
        .andExpect(status().isUnprocessableEntity());
    // Output dir should have been cleaned up so a retry doesn't 409 on dir-exists.
    org.assertj.core.api.Assertions.assertThat(Paths.get(tileDir.getAbsolutePath(), "fails-tiling"))
        .doesNotExist();
  }

  @Test
  void uploadGeoTiff_whenTilerInterrupted_returns500AndCleansUp(@Autowired MockMvc mvc)
      throws Exception {
    Mockito.reset(tiler);
    Mockito.when(tiler.tile(any(Path.class), any(Path.class)))
        .thenThrow(new InterruptedException("interrupted"));
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "interrupted-tiling")
                .with(userJwt()))
        .andExpect(status().isInternalServerError());
    org.assertj.core.api.Assertions.assertThat(
            Paths.get(tileDir.getAbsolutePath(), "interrupted-tiling"))
        .doesNotExist();
  }

  @Test
  void uploadGeoTiff_happyPath_returns201AndRegistersLayer(@Autowired MockMvc mvc)
      throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3, 4});
    mvc.perform(multipart("/layers/geotiff").file(file).param("name", "uploaded").with(userJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("uploaded"))
        .andExpect(jsonPath("$.sourceType").value("LOCAL"))
        .andExpect(jsonPath("$.maxZoom").value(0));
  }

  @Test
  void uploadGeoTiff_withAcl_setsAllowedUsersAndGroups(@Autowired MockMvc mvc) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "input.tif", "image/tiff", new byte[] {1, 2, 3, 4});
    mvc.perform(
            multipart("/layers/geotiff")
                .file(file)
                .param("name", "acl-layer")
                .param("allowedUsers", "alice, bob")
                .param("allowedGroups", "team-foresters")
                .with(userJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.allowedUsers[0]").value("alice"))
        .andExpect(jsonPath("$.allowedUsers[1]").value("bob"))
        .andExpect(jsonPath("$.allowedGroups[0]").value("team-foresters"));
  }
}
