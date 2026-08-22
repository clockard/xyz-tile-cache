package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * POST /import writes tiles straight to disk rather than through the TileWriter, so it has to
 * report what it wrote to the inventory itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportInventoryTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;
  @Autowired TileInventoryStore inventory;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", List::of);
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  void import_countsTilesAndBytesPerLayer() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("counted/3/4/5.png", new byte[10]);
    entries.put("counted/3/4/6.png", new byte[20]);
    entries.put("othercounted/1/0/0.png", new byte[7]);

    upload(entries).andExpect(status().isOk());

    assertThat(inventory.tiles("counted")).isEqualTo(2);
    assertThat(inventory.bytes("counted")).isEqualTo(30);
    assertThat(inventory.tiles("othercounted")).isEqualTo(1);
    assertThat(inventory.bytes("othercounted")).isEqualTo(7);
  }

  @Test
  void reimport_overwritesWithoutDoublingTheCount() throws Exception {
    upload(Map.of("reimported/2/1/1.png", new byte[10])).andExpect(status().isOk());
    upload(Map.of("reimported/2/1/1.png", new byte[25])).andExpect(status().isOk());

    assertThat(inventory.tiles("reimported")).isEqualTo(1);
    assertThat(inventory.bytes("reimported")).isEqualTo(25);
  }

  @Test
  void import_pmtilesArchiveCountsBytesButNotTiles() throws Exception {
    upload(Map.of("vectorlayer/basemap.pmtiles", new byte[128])).andExpect(status().isOk());

    assertThat(inventory.tiles("vectorlayer")).isZero();
    assertThat(inventory.bytes("vectorlayer")).isEqualTo(128);
  }

  @Test
  void import_cachedVectorTilesCountAsTiles() throws Exception {
    upload(Map.of("pbflayer/4/2/3.pbf", new byte[16])).andExpect(status().isOk());

    assertThat(inventory.tiles("pbflayer")).isEqualTo(1);
    assertThat(inventory.bytes("pbflayer")).isEqualTo(16);
  }

  private org.springframework.test.web.servlet.ResultActions upload(Map<String, byte[]> entries)
      throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.zip", "application/zip", buildZip(entries));
    return mvc.perform(multipart("/import").file(file).with(adminJwt()));
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
