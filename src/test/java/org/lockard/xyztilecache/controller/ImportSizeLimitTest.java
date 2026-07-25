package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies POST /import enforces the xyz.maxImportBytes decompressed-size cap. */
@SpringBootTest
@AutoConfigureMockMvc
class ImportSizeLimitTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.maxImportBytes", () -> 64L); // tiny cap so a single tile exceeds it
    registry.add("xyz.layers", List::of);
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  void importExceedingMaxBytes_isRejectedAndWritesNothing() throws Exception {
    byte[] oversized = new byte[256]; // > 64-byte cap
    byte[] zip = buildZip(Map.of("bigtiles/3/4/5.png", oversized));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);

    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isInternalServerError());

    // The partially written tile must not survive a rejected import.
    Path tile = Paths.get(tileDir.getAbsolutePath(), "bigtiles", "3", "4", "5.png");
    assertThat(Files.exists(tile)).isFalse();
  }

  @Test
  void importWithinMaxBytes_succeeds() throws Exception {
    byte[] small = new byte[8];
    byte[] zip = buildZip(Map.of("smalltiles/3/4/5.png", small));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);

    mvc.perform(multipart("/import").file(file).with(adminJwt())).andExpect(status().isOk());

    Path tile = Paths.get(tileDir.getAbsolutePath(), "smalltiles", "3", "4", "5.png");
    assertThat(Files.exists(tile)).isTrue();
  }

  private static byte[] buildZip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return bos.toByteArray();
  }
}
