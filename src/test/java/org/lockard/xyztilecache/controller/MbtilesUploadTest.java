package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.service.MbtilesConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The MBTiles upload path. The real conversion needs the {@code pmtiles} CLI, which is present in
 * the container image but not on a developer machine, so the converter is stubbed here: what is
 * under test is that an MBTiles upload is recognised, routed through conversion, and installed as
 * the archive the converter produced.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MbtilesUploadTest {

  @TempDir static File tileDir;

  /** Set when the stub converter runs, proving the upload took the conversion path. */
  static final AtomicBoolean CONVERTED = new AtomicBoolean();

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", List::of);
  }

  @TestConfiguration
  static class StubConverter {
    @Bean
    @Primary
    MbtilesConverter mbtilesConverter() {
      return new MbtilesConverter("unused") {
        @Override
        public void convert(Path mbtiles, Path output) throws java.io.IOException {
          CONVERTED.set(true);
          // Stand in for the CLI: emit a real PMTiles archive at the requested path.
          Files.write(output, fixture("test_fixture_gzip.pmtiles"));
        }
      };
    }
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("root").claim("preferred_username", "root"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static byte[] fixture(String name) {
    try {
      return Files.readAllBytes(
          Path.of(MbtilesUploadTest.class.getClassLoader().getResource(name).toURI()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A minimal file carrying SQLite's 16-byte header, which is how MBTiles are recognised. */
  private static byte[] sqliteFile() {
    byte[] header = "SQLite format 3".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] file = new byte[512];
    System.arraycopy(header, 0, file, 0, header.length);
    file[header.length] = 0;
    return file;
  }

  @Test
  void mbtilesUpload_isConvertedAndInstalledAsPmtiles() throws Exception {
    CONVERTED.set(false);
    MockMultipartFile mbtiles =
        new MockMultipartFile("files", "cities.mbtiles", "application/octet-stream", sqliteFile());

    mvc.perform(
            multipart("/layers/pmtiles").file(mbtiles).param("id", "converted").with(adminJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tileType").value("MVT"))
        // Named after the upload but stored as a PMTiles archive.
        .andExpect(jsonPath("$.archives[0].name").value("cities.pmtiles"));

    assertThat(CONVERTED).isTrue();
    assertThat(tileDir.toPath().resolve(Path.of("converted", "cities.pmtiles"))).exists();
    assertThat(tileDir.toPath().resolve(Path.of("converted", "cities.mbtiles"))).doesNotExist();
  }

  @Test
  void aFileThatIsNeitherFormat_isRejectedWithoutConverting() throws Exception {
    CONVERTED.set(false);
    MockMultipartFile junk =
        new MockMultipartFile(
            "files", "notes.mbtiles", "application/octet-stream", "nope".getBytes());

    mvc.perform(multipart("/layers/pmtiles").file(junk).param("id", "notjson").with(adminJwt()))
        .andExpect(status().isBadRequest());

    assertThat(CONVERTED).isFalse();
  }
}
