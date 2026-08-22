package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

/** Creating PMTiles layers by uploading archives, and adding archives to existing ones. */
@SpringBootTest
@AutoConfigureMockMvc
class PmtilesUploadControllerTest {

  /** tile_type 2 (PNG). */
  private static final String RASTER_FIXTURE = "test_fixture_1.pmtiles";

  /** tile_type 1 (MVT). */
  private static final String VECTOR_FIXTURE = "test_fixture_gzip.pmtiles";

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", List::of);
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("root").claim("preferred_username", "root"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static byte[] fixture(String name) throws Exception {
    return Files.readAllBytes(
        Path.of(PmtilesUploadControllerTest.class.getClassLoader().getResource(name).toURI()));
  }

  private static MockMultipartFile upload(String fileName, String fixtureName) throws Exception {
    return new MockMultipartFile(
        "files", fileName, "application/octet-stream", fixture(fixtureName));
  }

  private Path layerDir(String id) {
    return tileDir.toPath().resolve(id);
  }

  // ── Creating a layer ──────────────────────────────────────────────────────

  @Test
  void upload_createsALayerServingTheUploadedArchive() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("basemap.pmtiles", RASTER_FIXTURE))
                .param("id", "uploaded")
                .param("name", "Uploaded Basemap")
                .with(adminJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("uploaded"))
        .andExpect(jsonPath("$.tileType").value("PNG"))
        .andExpect(jsonPath("$.tileExtension").value("png"))
        .andExpect(jsonPath("$.archives[0].name").value("basemap.pmtiles"));

    assertThat(layerDir("uploaded").resolve("basemap.pmtiles")).exists();

    // Registered as a real layer and serving immediately, without a restart.
    mvc.perform(get("/layers"))
        .andExpect(jsonPath("$[?(@.id=='uploaded')].sourceType").value("PMTILES"));
    mvc.perform(get("/tilesZXY/uploaded/0/0/0.png"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Content-Type", "image/png"));
  }

  @Test
  void upload_acceptsSeveralArchivesForOneLayer() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("east.pmtiles", RASTER_FIXTURE))
                .file(upload("west.pmtiles", RASTER_FIXTURE))
                .param("id", "multi")
                .with(adminJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.archives.length()").value(2));

    assertThat(layerDir("multi").resolve("east.pmtiles")).exists();
    assertThat(layerDir("multi").resolve("west.pmtiles")).exists();
  }

  @Test
  void upload_rejectsArchivesOfDifferentTypesInOneRequest() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("raster.pmtiles", RASTER_FIXTURE))
                .file(upload("vector.pmtiles", VECTOR_FIXTURE))
                .param("id", "mixedupload")
                .with(adminJwt()))
        .andExpect(status().isBadRequest());

    // Nothing was created: neither the layer nor a half-populated directory.
    mvc.perform(get("/layers")).andExpect(jsonPath("$[?(@.id=='mixedupload')]").doesNotExist());
    assertThat(layerDir("mixedupload")).doesNotExist();
  }

  @Test
  void upload_rejectsAFileThatIsNotAnArchive() throws Exception {
    MockMultipartFile junk =
        new MockMultipartFile(
            "files", "notes.pmtiles", "application/octet-stream", "hello".getBytes());

    mvc.perform(multipart("/layers/pmtiles").file(junk).param("id", "junklayer").with(adminJwt()))
        .andExpect(status().isBadRequest());

    assertThat(layerDir("junklayer")).doesNotExist();
  }

  @Test
  void upload_rejectsAnInvalidLayerId() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .param("id", "../escape")
                .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void upload_rejectsADuplicateLayerId() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .param("id", "dupe")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("b.pmtiles", RASTER_FIXTURE))
                .param("id", "dupe")
                .with(adminJwt()))
        .andExpect(status().isConflict());
  }

  @Test
  void upload_requiresAdmin() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .param("id", "noauth"))
        .andExpect(status().isUnauthorized());
  }

  // ── Adding to an existing layer ───────────────────────────────────────────

  @Test
  void addArchive_extendsAnExistingLayer() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("first.pmtiles", RASTER_FIXTURE))
                .param("id", "growing")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(
            multipart("/layers/growing/pmtiles")
                .file(upload("second.pmtiles", RASTER_FIXTURE))
                .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archives[0].name").value("second.pmtiles"));

    assertThat(layerDir("growing").resolve("first.pmtiles")).exists();
    assertThat(layerDir("growing").resolve("second.pmtiles")).exists();
  }

  @Test
  void addArchive_refusesADifferentTileType() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("raster.pmtiles", RASTER_FIXTURE))
                .param("id", "rasteronly")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(
            multipart("/layers/rasteronly/pmtiles")
                .file(upload("vector.pmtiles", VECTOR_FIXTURE))
                .with(adminJwt()))
        .andExpect(status().isBadRequest());

    // Refused before anything was written: the layer keeps only its original archive.
    assertThat(layerDir("rasteronly").resolve("vector.pmtiles")).doesNotExist();
  }

  @Test
  void addArchive_refusesAnArchiveNameAlreadyInTheLayer() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("same.pmtiles", RASTER_FIXTURE))
                .param("id", "collide")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(
            multipart("/layers/collide/pmtiles")
                .file(upload("same.pmtiles", RASTER_FIXTURE))
                .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addArchive_toAnUnknownLayer_returns404() throws Exception {
    mvc.perform(
            multipart("/layers/nope/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .with(adminJwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void addArchive_toANonPmtilesLayer_returns400() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/layers")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "id": "rasterxyz", "name": "Raster", "sourceType": "XYZ",
                      "urlTemplate": "https://example.com/{z}/{x}/{y}.png" }
                    """)
                .with(adminJwt()))
        .andExpect(status().isCreated());

    mvc.perform(
            multipart("/layers/rasterxyz/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void upload_stagesInsideTheTileDirectoryAndCleansUpAfterItself() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("staged.pmtiles", RASTER_FIXTURE))
                .param("id", "staging")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    // Staging has to share a filesystem with the destination or the move into place cannot be a
    // rename -- with the tile directory typically a mounted volume, staging in the system temp
    // space makes it a cross-device move that fails outright.
    Path staging = tileDir.toPath().resolve(".uploads");
    assertThat(staging).exists();
    try (var entries = Files.list(staging)) {
      assertThat(entries).as("staging directories are removed once the upload finishes").isEmpty();
    }
  }

  @Test
  void stagingDirectoryIsNotMistakenForALayer() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .param("id", "notalayer")
                .with(adminJwt()))
        .andExpect(status().isCreated());

    // Layer ids must start with a letter or digit, so the dot-prefixed staging directory cannot
    // be picked up as one by anything scanning the tile directory.
    assertThat(
            org.lockard.xyztilecache.store.LayerStore.SAFE_LAYER_ID.matcher(".uploads").matches())
        .isFalse();
  }

  @Test
  void upload_takesMaxZoomFromTheArchiveWhenNoneIsGiven() throws Exception {
    // The raster fixture's header declares maxzoom 1. Left to a generic default the layer would
    // claim zooms it has no tiles for, and the map would request blanks.
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("deep.pmtiles", RASTER_FIXTURE))
                .param("id", "derivedzoom")
                .with(adminJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.maxZoom").value(1));

    mvc.perform(get("/layers")).andExpect(jsonPath("$[?(@.id=='derivedzoom')].maxZoom").value(1));
  }

  @Test
  void upload_respectsAnExplicitMaxZoom() throws Exception {
    mvc.perform(
            multipart("/layers/pmtiles")
                .file(upload("a.pmtiles", RASTER_FIXTURE))
                .param("id", "explicitzoom")
                .param("maxZoom", "9")
                .with(adminJwt()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.maxZoom").value(9));
  }
}
