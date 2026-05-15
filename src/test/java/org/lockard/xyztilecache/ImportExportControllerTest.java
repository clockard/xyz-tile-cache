package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class ImportExportControllerTest {

  @TempDir static File tileDir;
  @TempDir static File vectorDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.vector.downloadDirectory", () -> vectorDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          Layer pub = new Layer();
          pub.setName("public-layer");
          pub.setUrlTemplate("https://example.com/pub/{z}/{y}/{x}");
          pub.setMaxZoom(5);

          Layer aliceOnly = new Layer();
          aliceOnly.setName("alice-only");
          aliceOnly.setUrlTemplate("https://example.com/alice/{z}/{y}/{x}");
          aliceOnly.setAllowedUsers(List.of("alice"));
          aliceOnly.setMaxZoom(5);

          Layer vector = new Layer();
          vector.setName("vector-layer");
          vector.setUrlTemplate("https://example.com/vec/{z}/{x}/{y}.pbf");
          vector.setMaxZoom(5);

          return List.of(pub, aliceOnly, vector);
        });
  }

  @Autowired MockMvc mvc;
  @Autowired LayerStore layerStore;
  @Autowired ObjectMapper objectMapper;
  @Autowired VectorConfiguration vectorConfiguration;

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("admin").claim("preferred_username", "admin"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  static RequestPostProcessor userJwt(String username) {
    return jwt().jwt(j -> j.subject(username).claim("preferred_username", username));
  }

  @BeforeEach
  void seedTiles() throws Exception {
    Path pub = Paths.get(tileDir.getAbsolutePath(), "public-layer");
    Files.createDirectories(pub.resolve("0").resolve("0"));
    Files.write(pub.resolve("0").resolve("0").resolve("0.png"), new byte[] {1, 2, 3});
    Files.createDirectories(pub.resolve("1").resolve("0"));
    Files.write(pub.resolve("1").resolve("0").resolve("0.png"), new byte[] {4, 5, 6});

    Path al = Paths.get(tileDir.getAbsolutePath(), "alice-only");
    Files.createDirectories(al.resolve("0").resolve("0"));
    Files.write(al.resolve("0").resolve("0").resolve("0.png"), new byte[] {9});
  }

  // ── /export ───────────────────────────────────────────────────────────────

  @Test
  void export_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layers\":[\"public-layer\"]}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void export_emptyLayers_returns400() throws Exception {
    mvc.perform(
            post("/export")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layers\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void export_unknownLayer_returns404() throws Exception {
    mvc.perform(
            post("/export")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layers\":[\"does-not-exist\"]}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void export_vectorLayer_returnsZipWithLayerJson() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"vector-layer\"]}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKey("vector-layer/layer.json");
    Layer roundTripped =
        objectMapper.readValue(entries.get("vector-layer/layer.json"), Layer.class);
    assertThat(roundTripped.getUrlTemplate()).isEqualTo("https://example.com/vec/{z}/{x}/{y}.pbf");
  }

  @Test
  void export_userWithoutAccess_returns403() throws Exception {
    mvc.perform(
            post("/export")
                .with(userJwt("dan"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layers\":[\"alice-only\"]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void export_publicLayer_anonymousUserJwt_returnsZipWithLayerJsonAndTiles() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(userJwt("dan"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"public-layer\"]}"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).contains("application/zip");
    assertThat(result.getResponse().getHeader("Content-Disposition"))
        .contains("attachment")
        .contains("tile-export-");

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries)
        .containsKeys(
            "public-layer/layer.json", "public-layer/0/0/0.png", "public-layer/1/0/0.png");
    assertThat(entries.get("public-layer/0/0/0.png")).containsExactly(1, 2, 3);

    Layer roundTripped =
        objectMapper.readValue(entries.get("public-layer/layer.json"), Layer.class);
    assertThat(roundTripped.getEffectiveId()).isEqualTo("public-layer");
    assertThat(roundTripped.getUrlTemplate()).isEqualTo("https://example.com/pub/{z}/{y}/{x}");
  }

  @Test
  void export_userWithAccess_returns200() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(userJwt("alice"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"alice-only\"]}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKeys("alice-only/layer.json", "alice-only/0/0/0.png");
  }

  @Test
  void export_admin_skipsTilesOutsideBbox() throws Exception {
    Path pub = Paths.get(tileDir.getAbsolutePath(), "public-layer");
    Files.createDirectories(pub.resolve("1").resolve("1"));
    Files.write(pub.resolve("1").resolve("1").resolve("1.png"), new byte[] {7, 7, 7});

    String body =
        "{\"layers\":[\"public-layer\"],"
            + "\"boundingBox\":{\"north\":85,\"south\":1,\"east\":-1,\"west\":-179,\"maxZoom\":5}}";

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKeys("public-layer/layer.json", "public-layer/0/0/0.png");
    assertThat(entries).containsKey("public-layer/1/0/0.png");
    assertThat(entries).doesNotContainKey("public-layer/1/1/1.png");
  }

  // ── /import ───────────────────────────────────────────────────────────────

  @Test
  void importZip_anonymous_returns401() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.zip", "application/zip", buildZip(Map.of()));
    mvc.perform(multipart("/import").file(file)).andExpect(status().isUnauthorized());
  }

  @Test
  void importZip_emptyFile_returns400() throws Exception {
    MockMultipartFile empty =
        new MockMultipartFile("file", "x.zip", "application/zip", new byte[0]);
    mvc.perform(multipart("/import").file(empty).with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importZip_freshLayer_registersLayerAndWritesTiles() throws Exception {
    Layer fresh = new Layer();
    fresh.setName("imported-fresh");
    fresh.setUrlTemplate("https://example.com/fresh/{z}/{y}/{x}");
    fresh.setMaxZoom(7);

    byte[] zip =
        buildZip(
            Map.of(
                "imported-fresh/layer.json",
                objectMapper.writeValueAsBytes(fresh),
                "imported-fresh/3/4/5.png",
                new byte[] {10, 11}));

    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layersAdded[0]").value("imported-fresh"))
        .andExpect(jsonPath("$.tilesWritten").value(1));

    assertThat(layerStore.getLayer("imported-fresh")).isPresent();
    assertThat(layerStore.getLayer("imported-fresh").get().getMaxZoom()).isEqualTo(7);
    Path tile = Paths.get(tileDir.getAbsolutePath(), "imported-fresh", "3", "4", "5.png");
    assertThat(Files.readAllBytes(tile)).containsExactly(10, 11);
  }

  @Test
  void importZip_existingLayerId_skipsLayerJsonStillWritesTiles() throws Exception {
    Layer impostor = new Layer();
    impostor.setName("public-layer");
    impostor.setUrlTemplate("https://malicious.example.com/{z}/{y}/{x}");
    impostor.setMaxZoom(99);

    byte[] zip =
        buildZip(
            Map.of(
                "public-layer/layer.json",
                objectMapper.writeValueAsBytes(impostor),
                "public-layer/2/2/2.png",
                new byte[] {77}));

    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layersSkipped[0]").value("public-layer"))
        .andExpect(jsonPath("$.tilesWritten").value(1));

    assertThat(layerStore.getLayer("public-layer").get().getUrlTemplate())
        .isEqualTo("https://example.com/pub/{z}/{y}/{x}");
    Path imported = Paths.get(tileDir.getAbsolutePath(), "public-layer", "2", "2", "2.png");
    assertThat(Files.readAllBytes(imported)).containsExactly(77);
  }

  @Test
  void importZip_overwritesExistingTileFile() throws Exception {
    Path pub = Paths.get(tileDir.getAbsolutePath(), "public-layer");
    Files.createDirectories(pub.resolve("4").resolve("4"));
    Files.write(pub.resolve("4").resolve("4").resolve("4.png"), new byte[] {1});

    byte[] zip = buildZip(Map.of("public-layer/4/4/4.png", new byte[] {99, 99}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt())).andExpect(status().isOk());

    assertThat(Files.readAllBytes(pub.resolve("4").resolve("4").resolve("4.png")))
        .containsExactly(99, 99);
  }

  @Test
  void importZip_zipSlipPath_returns400AndWritesNothing() throws Exception {
    byte[] zip = buildZip(Map.of("../escape.png", new byte[] {0}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isBadRequest());

    Path baseDir = Paths.get(tileDir.getAbsolutePath()).toAbsolutePath().normalize();
    Path escape = baseDir.getParent().resolve("escape.png");
    assertThat(escape).doesNotExist();
  }

  @Test
  void importZip_invalidLayerId_returns400() throws Exception {
    byte[] zip = buildZip(Map.of("../etc/passwd", new byte[] {0}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importZip_strayTopLevelFileAndUnknownExtension_areIgnored() throws Exception {
    byte[] zip =
        buildZip(
            Map.of(
                "README", new byte[] {1, 2, 3},
                "imported-junk/notes.txt", new byte[] {4, 5, 6},
                "imported-junk/3/4/5.png", new byte[] {7}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(1));

    Path notes = Paths.get(tileDir.getAbsolutePath(), "imported-junk", "notes.txt");
    assertThat(notes).doesNotExist();
  }

  @Test
  void importZip_layerJsonWithBlankId_takesIdFromDirectory() throws Exception {
    Layer blank = new Layer();
    blank.setUrlTemplate("https://example.com/blank/{z}/{y}/{x}");
    blank.setMaxZoom(4);
    byte[] zip =
        buildZip(Map.of("imported-by-dirname/layer.json", objectMapper.writeValueAsBytes(blank)));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layersAdded[0]").value("imported-by-dirname"));
    assertThat(layerStore.getLayer("imported-by-dirname")).isPresent();
    assertThat(layerStore.getLayer("imported-by-dirname").get().getName())
        .isEqualTo("imported-by-dirname");
  }

  @Test
  void importZip_layerJsonInvalidLayer_isSkipped() throws Exception {
    // A layer with id but no url template (and not LOCAL) fails LayerStore validation.
    Layer broken = new Layer();
    broken.setName("broken-layer");
    broken.setUrlTemplate("");
    byte[] zip =
        buildZip(Map.of("broken-layer/layer.json", objectMapper.writeValueAsBytes(broken)));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layersSkipped[0]").value("broken-layer"));
    assertThat(layerStore.getLayer("broken-layer")).isEmpty();
  }

  @Test
  void export_admin_layerWithoutTileDir_returnsOnlyLayerJson() throws Exception {
    // Register a layer that has no on-disk tile directory yet.
    Layer empty = new Layer();
    empty.setName("empty-layer");
    empty.setUrlTemplate("https://example.com/empty/{z}/{y}/{x}");
    empty.setMaxZoom(3);
    layerStore.addLayer(empty);

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"empty-layer\"]}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries.keySet()).containsExactly("empty-layer/layer.json");
  }

  @Test
  void export_admin_minAndMaxZoomOverridesAreApplied() throws Exception {
    // Bbox covers tile at z=0 and z=1 (already on disk via @BeforeEach).
    String body =
        "{\"layers\":[\"public-layer\"],"
            + "\"minZoom\":1,\"maxZoom\":1,"
            + "\"boundingBox\":{\"north\":85,\"south\":1,\"east\":-1,\"west\":-179,\"maxZoom\":5}}";

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKeys("public-layer/layer.json", "public-layer/1/0/0.png");
    assertThat(entries).doesNotContainKey("public-layer/0/0/0.png");
  }

  @Test
  void export_admin_blankLayerIdInList_returns400() throws Exception {
    mvc.perform(
            post("/export")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layers\":[\"  \"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importZip_missingFileParam_returns400() throws Exception {
    mvc.perform(multipart("/import").with(adminJwt())).andExpect(status().isBadRequest());
  }

  @Test
  void importZip_userWithoutLayerAccess_returns403AndWritesNothing() throws Exception {
    byte[] zip = buildZip(Map.of("alice-only/2/2/2.png", new byte[] {5}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(userJwt("bob")))
        .andExpect(status().isForbidden());

    Path tile = Paths.get(tileDir.getAbsolutePath(), "alice-only", "2", "2", "2.png");
    assertThat(tile).doesNotExist();
  }

  @Test
  void importZip_nonAdminNewLayer_returns403() throws Exception {
    Layer fresh = new Layer();
    fresh.setName("non-admin-fresh");
    fresh.setUrlTemplate("https://example.com/fresh/{z}/{y}/{x}");
    byte[] zip =
        buildZip(
            Map.of(
                "non-admin-fresh/layer.json",
                objectMapper.writeValueAsBytes(fresh),
                "non-admin-fresh/3/4/5.png",
                new byte[] {10}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(userJwt("alice")))
        .andExpect(status().isForbidden());
    assertThat(layerStore.getLayer("non-admin-fresh")).isEmpty();
  }

  @Test
  void importZip_adminCanImportToAllowedLayer() throws Exception {
    byte[] zip = buildZip(Map.of("alice-only/5/5/5.png", new byte[] {77}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(1));
    Path tile = Paths.get(tileDir.getAbsolutePath(), "alice-only", "5", "5", "5.png");
    assertThat(Files.readAllBytes(tile)).containsExactly(77);
  }

  @Test
  void importZip_adminCanImportToPublicLayer() throws Exception {
    byte[] zip = buildZip(Map.of("public-layer/5/5/5.png", new byte[] {88}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(1));
  }

  // ── vector export ─────────────────────────────────────────────────────────

  @Test
  void export_includeVectorOnly_noLayers_returnsZipWithVectorTiles() throws Exception {
    // Seed a cached PBF tile in the remote-cache directory
    Path cacheDir = vectorDir.toPath().resolve("remote-cache").resolve("3").resolve("4");
    Files.createDirectories(cacheDir);
    Files.write(cacheDir.resolve("5.pbf"), new byte[] {0x1a, 0x2b});

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"includeVector\":true}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKey("vector/tiles/3/4/5.pbf");
    assertThat(entries.get("vector/tiles/3/4/5.pbf")).containsExactly(0x1a, 0x2b);
  }

  @Test
  void export_noLayersNoVector_returns400() throws Exception {
    mvc.perform(
            post("/export").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void export_includeVector_withLayers_includesBoth() throws Exception {
    Path cacheDir = vectorDir.toPath().resolve("remote-cache").resolve("2").resolve("1");
    Files.createDirectories(cacheDir);
    Files.write(cacheDir.resolve("0.pbf"), new byte[] {0x0f});

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"public-layer\"],\"includeVector\":true}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKey("public-layer/layer.json");
    assertThat(entries).containsKey("vector/tiles/2/1/0.pbf");
  }

  // ── vector import ─────────────────────────────────────────────────────────

  @Test
  void importZip_vectorTiles_writesToRemoteCache() throws Exception {
    byte[] zip = buildZip(Map.of("vector/tiles/4/2/3.pbf", new byte[] {0x0a, 0x0b, 0x0c}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(1));

    Path tilePath =
        vectorDir.toPath().resolve("remote-cache").resolve("4").resolve("2").resolve("3.pbf");
    assertThat(Files.readAllBytes(tilePath)).containsExactly(0x0a, 0x0b, 0x0c);
  }

  @Test
  void importZip_vectorTiles_nonAdmin_returns403() throws Exception {
    byte[] zip = buildZip(Map.of("vector/tiles/4/2/3.pbf", new byte[] {0x01}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(userJwt("bob")))
        .andExpect(status().isForbidden());
  }

  @Test
  void importZip_vectorTile_invalidTailPattern_isSkipped() throws Exception {
    // tail "x/y/z.pbf" doesn't match \d+/\d+/\d+\.pbf
    byte[] zip = buildZip(Map.of("vector/tiles/x/y/z.pbf", new byte[] {0x01}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(0));
  }

  @Test
  void importZip_vectorPmtiles_unsafeName_isSkipped() throws Exception {
    // "bad!file.pmtiles" has '!' which is not in [A-Za-z0-9._-]
    byte[] zip = buildZip(Map.of("vector/pmtiles/bad!file.pmtiles", new byte[] {0x01}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pmtilesImported").value(0));
  }

  @Test
  void importZip_vectorTiles_noDownloadDir_skips() throws Exception {
    String original = vectorConfiguration.getDownloadDirectory();
    vectorConfiguration.setDownloadDirectory("");
    try {
      byte[] zip = buildZip(Map.of("vector/tiles/4/2/3.pbf", new byte[] {0x0a}));
      MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
      mvc.perform(multipart("/import").file(file).with(adminJwt()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tilesWritten").value(0));
    } finally {
      vectorConfiguration.setDownloadDirectory(original);
    }
  }

  @Test
  void export_includeVector_downloadDirBlank_returnsEmptyZip() throws Exception {
    String original = vectorConfiguration.getDownloadDirectory();
    vectorConfiguration.setDownloadDirectory("");
    try {
      MvcResult result =
          mvc.perform(
                  post("/export")
                      .with(adminJwt())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"includeVector\":true}"))
              .andExpect(status().isOk())
              .andReturn();
      Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
      assertThat(entries).doesNotContainKey("vector/tiles/0/0/0.pbf");
    } finally {
      vectorConfiguration.setDownloadDirectory(original);
    }
  }

  @Test
  void export_includeVector_pmtilesFiles_includedInZip() throws Exception {
    java.nio.file.Files.write(
        vectorDir.toPath().resolve("region.pmtiles"), new byte[] {0x01, 0x02});

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"includeVector\":true}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKey("vector/pmtiles/region.pmtiles");
    assertThat(entries.get("vector/pmtiles/region.pmtiles")).containsExactly(0x01, 0x02);
  }

  @Test
  void export_includeVector_zoomFilters_excludeOutOfRangePbfTiles() throws Exception {
    // Seed tiles at z=0 and z=5; export with minZoom=2, maxZoom=4 — both should be excluded
    Path cacheZ0 = vectorDir.toPath().resolve("remote-cache").resolve("0").resolve("0");
    Path cacheZ5 = vectorDir.toPath().resolve("remote-cache").resolve("5").resolve("10");
    java.nio.file.Files.createDirectories(cacheZ0);
    java.nio.file.Files.createDirectories(cacheZ5);
    java.nio.file.Files.write(cacheZ0.resolve("0.pbf"), new byte[] {0x01});
    java.nio.file.Files.write(cacheZ5.resolve("0.pbf"), new byte[] {0x02});

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"includeVector\":true,\"minZoom\":2,\"maxZoom\":4}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).doesNotContainKey("vector/tiles/0/0/0.pbf");
    assertThat(entries).doesNotContainKey("vector/tiles/5/10/0.pbf");
  }

  @Test
  void importZip_layerJsonWithMismatchedId_takesIdFromDirectory() throws Exception {
    // layer.json has name="original-name" (used as effectiveId) but directory is "dir-name"
    Layer mismatch = new Layer();
    mismatch.setName("original-name");
    mismatch.setUrlTemplate("https://example.com/m/{z}/{y}/{x}");
    mismatch.setMaxZoom(4);
    byte[] zip = buildZip(Map.of("dir-name/layer.json", objectMapper.writeValueAsBytes(mismatch)));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.layersAdded[0]").value("dir-name"));
    assertThat(layerStore.getLayer("dir-name")).isPresent();
    // Name is preserved (non-blank) even though id was fixed
    assertThat(layerStore.getLayer("dir-name").get().getName()).isEqualTo("original-name");
  }

  // ── pmtiles bbox export ───────────────────────────────────────────────────

  @Test
  void export_includeVector_pmtilesOutsideBbox_isSkipped() throws Exception {
    // Australia bounds; bbox = North America → no overlap
    byte[] pmtiles =
        buildMinimalPmtiles(
            (byte) 0,
            (byte) 1,
            1_130_000_000,
            -440_000_000, // minLon=113, minLat=-44
            1_540_000_000,
            -100_000_000); // maxLon=154, maxLat=-10
    Files.write(vectorDir.toPath().resolve("australia.pmtiles"), pmtiles);

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"includeVector\":true,"
                            + "\"boundingBox\":{\"north\":60,\"south\":20,\"east\":-60,\"west\":-130,\"maxZoom\":1}}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).doesNotContainKey("vector/pmtiles/australia.pmtiles");
  }

  @Test
  void export_includeVector_pmtilesFullyInBbox_includedAsIs() throws Exception {
    // Fixture has bounds [0,0,0,0]; world bbox fully contains it
    URL url = getClass().getClassLoader().getResource("test_fixture_1.pmtiles");
    byte[] fixtureBytes = Files.readAllBytes(Paths.get(url.getPath()));
    Files.write(vectorDir.toPath().resolve("fixture-bbox.pmtiles"), fixtureBytes);

    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"includeVector\":true,"
                            + "\"boundingBox\":{\"north\":90,\"south\":-90,\"east\":180,\"west\":-180,\"maxZoom\":1}}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).containsKey("vector/pmtiles/fixture-bbox.pmtiles");
  }

  @Test
  void export_includeVector_pmtilesPartialBbox_extractsTilesInBbox() throws Exception {
    // Copy fixture and widen its declared bounds to world so any sub-world bbox triggers partial
    URL url = getClass().getClassLoader().getResource("test_fixture_1.pmtiles");
    byte[] fixtureBytes = Files.readAllBytes(Paths.get(url.getPath()));
    ByteBuffer bb = ByteBuffer.wrap(fixtureBytes).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(102, -1_800_000_000); // minLon = -180
    bb.putInt(106, -900_000_000); // minLat = -90
    bb.putInt(110, 1_800_000_000); // maxLon = 180
    bb.putInt(114, 900_000_000); // maxLat = 90
    Files.write(vectorDir.toPath().resolve("world-partial.pmtiles"), fixtureBytes);

    // south=1 means the file's declared minLat=-90 is outside the bbox → partial extraction
    MvcResult result =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"includeVector\":true,"
                            + "\"boundingBox\":{\"north\":85,\"south\":1,\"east\":180,\"west\":-180,\"maxZoom\":1}}"))
            .andExpect(status().isOk())
            .andReturn();

    Map<String, byte[]> entries = readZip(result.getResponse().getContentAsByteArray());
    assertThat(entries).doesNotContainKey("vector/pmtiles/world-partial.pmtiles");
    // z=0 tile (0,0) covers the whole world and exists in the fixture
    assertThat(entries).containsKey("vector/tiles/0/0/0.pbf");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static byte[] buildMinimalPmtiles(
      byte minZoom, byte maxZoom, int minLonE7, int minLatE7, int maxLonE7, int maxLatE7) {
    // 127-byte header + 1-byte empty root directory (varint 0 = 0 entries)
    ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
    buf.put((byte) 'P')
        .put((byte) 'M')
        .put((byte) 'T')
        .put((byte) 'i')
        .put((byte) 'l')
        .put((byte) 'e')
        .put((byte) 's');
    buf.put((byte) 3); // version
    buf.putLong(127); // root_dir_offset
    buf.putLong(1); // root_dir_length
    buf.putLong(128); // metadata_offset
    buf.putLong(0); // metadata_length
    buf.putLong(128); // leaf_dirs_offset
    buf.putLong(0); // leaf_dirs_length
    buf.putLong(128); // tile_data_offset
    buf.putLong(0); // tile_data_length
    buf.position(96);
    buf.put((byte) 0); // clustered
    buf.put((byte) 1); // internal_compression: none
    buf.put((byte) 1); // tile_compression: none
    buf.put((byte) 1); // tile_type: MVT
    buf.put(minZoom);
    buf.put(maxZoom);
    buf.putInt(minLonE7);
    buf.putInt(minLatE7);
    buf.putInt(maxLonE7);
    buf.putInt(maxLatE7);
    buf.position(127);
    buf.put((byte) 0); // empty root directory
    return buf.array();
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

  private static Map<String, byte[]> readZip(byte[] data) throws Exception {
    Map<String, byte[]> out = new HashMap<>();
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (e.isDirectory()) continue;
        out.put(e.getName(), zis.readAllBytes());
      }
    }
    return out;
  }
}
