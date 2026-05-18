package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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

  // ── async helpers ─────────────────────────────────────────────────────────

  /** Submit POST /export and return the job id from the 202 response. */
  private String submitExport(String body, RequestPostProcessor auth) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/export").with(auth).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /**
   * Poll GET /exports/{id} until status is DONE (or FAILED), then fetch the zip via GET
   * /exports/{id}/download. The same auth used to submit must be passed here.
   */
  private Map<String, byte[]> waitAndDownload(String jobId, RequestPostProcessor auth)
      throws Exception {
    String jobStatus = "PENDING";
    for (int i = 0; i < 100 && !"DONE".equals(jobStatus) && !"FAILED".equals(jobStatus); i++) {
      Thread.sleep(100);
      String body =
          mvc.perform(get("/exports/" + jobId).with(auth))
              .andReturn()
              .getResponse()
              .getContentAsString();
      jobStatus = objectMapper.readTree(body).get("status").asText();
    }
    assertThat(jobStatus).isEqualTo("DONE");
    MvcResult result =
        mvc.perform(get("/exports/" + jobId + "/download").with(auth))
            .andExpect(status().isOk())
            .andReturn();
    return readZip(result.getResponse().getContentAsByteArray());
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
    String jobId = submitExport("{\"layers\":[\"vector-layer\"]}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
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
    String jobId = submitExport("{\"layers\":[\"public-layer\"]}", userJwt("dan"));
    MvcResult download =
        mvc.perform(get("/exports/" + jobId + "/download").with(userJwt("dan"))).andReturn();

    // Poll until done (download endpoint returns 409 while not ready)
    for (int i = 0; i < 100 && download.getResponse().getStatus() == 409; i++) {
      Thread.sleep(100);
      download =
          mvc.perform(get("/exports/" + jobId + "/download").with(userJwt("dan"))).andReturn();
    }

    assertThat(download.getResponse().getStatus()).isEqualTo(200);
    assertThat(download.getResponse().getContentType()).contains("application/zip");
    assertThat(download.getResponse().getHeader("Content-Disposition"))
        .contains("attachment")
        .contains("tile-export-");

    Map<String, byte[]> entries = readZip(download.getResponse().getContentAsByteArray());
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
  void export_userWithAccess_returnsZip() throws Exception {
    String jobId = submitExport("{\"layers\":[\"alice-only\"]}", userJwt("alice"));
    Map<String, byte[]> entries = waitAndDownload(jobId, userJwt("alice"));
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

    String jobId = submitExport(body, adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).containsKeys("public-layer/layer.json", "public-layer/0/0/0.png");
    assertThat(entries).containsKey("public-layer/1/0/0.png");
    assertThat(entries).doesNotContainKey("public-layer/1/1/1.png");
  }

  @Test
  void export_admin_layerWithoutTileDir_returnsOnlyLayerJson() throws Exception {
    Layer empty = new Layer();
    empty.setName("empty-layer");
    empty.setUrlTemplate("https://example.com/empty/{z}/{y}/{x}");
    empty.setMaxZoom(3);
    layerStore.addLayer(empty);

    String jobId = submitExport("{\"layers\":[\"empty-layer\"]}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries.keySet()).containsExactly("empty-layer/layer.json");
  }

  @Test
  void export_admin_minAndMaxZoomOverridesAreApplied() throws Exception {
    String body =
        "{\"layers\":[\"public-layer\"],"
            + "\"minZoom\":1,\"maxZoom\":1,"
            + "\"boundingBox\":{\"north\":85,\"south\":1,\"east\":-1,\"west\":-179,\"maxZoom\":5}}";

    String jobId = submitExport(body, adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
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
  void export_noLayersNoVector_returns400() throws Exception {
    mvc.perform(
            post("/export").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ── GET /exports ──────────────────────────────────────────────────────────

  @Test
  void listExports_noAuth_returns401() throws Exception {
    mvc.perform(get("/exports")).andExpect(status().isUnauthorized());
  }

  @Test
  void listExports_noJobs_returnsEmptyList() throws Exception {
    mvc.perform(get("/exports").with(userJwt("nobody")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void listExports_returnsOnlyOwnJobs() throws Exception {
    String adminJobId = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    String aliceJobId = submitExport("{\"layers\":[\"alice-only\"]}", userJwt("alice"));

    String aliceBody =
        mvc.perform(get("/exports").with(userJwt("alice")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(aliceBody).contains(aliceJobId);
    assertThat(aliceBody).doesNotContain(adminJobId);
  }

  // ── GET /exports/{id} ─────────────────────────────────────────────────────

  @Test
  void getExportStatus_noAuth_returns401() throws Exception {
    mvc.perform(get("/exports/any-id")).andExpect(status().isUnauthorized());
  }

  @Test
  void getExportStatus_unknownId_returns404() throws Exception {
    mvc.perform(get("/exports/no-such-id").with(adminJwt())).andExpect(status().isNotFound());
  }

  @Test
  void getExportStatus_wrongUser_returns403() throws Exception {
    String jobId = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    mvc.perform(get("/exports/" + jobId).with(userJwt("dan"))).andExpect(status().isForbidden());
  }

  @Test
  void getExportStatus_pendingJob_returnsPendingOrRunningOrDone() throws Exception {
    String jobId = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    String body =
        mvc.perform(get("/exports/" + jobId).with(adminJwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = objectMapper.readTree(body);
    assertThat(node.get("id").asText()).isEqualTo(jobId);
    assertThat(node.get("status").asText()).isIn("PENDING", "RUNNING", "DONE");
    assertThat(node.get("filename").asText()).startsWith("tile-export-");
  }

  // ── GET /exports/{id}/download ────────────────────────────────────────────

  @Test
  void downloadExport_noAuth_returns401() throws Exception {
    mvc.perform(get("/exports/any-id/download")).andExpect(status().isUnauthorized());
  }

  @Test
  void downloadExport_unknownId_returns404() throws Exception {
    mvc.perform(get("/exports/no-such-id/download").with(adminJwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void downloadExport_wrongUser_returns403() throws Exception {
    String jobId = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    // Wait until the job is done so we don't get a 409 instead
    waitAndDownload(jobId, adminJwt());
    // The job is now cleaned up (404), so re-submit for the 403 check
    String jobId2 = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    for (int i = 0; i < 100; i++) {
      Thread.sleep(100);
      String s =
          mvc.perform(get("/exports/" + jobId2).with(adminJwt()))
              .andReturn()
              .getResponse()
              .getContentAsString();
      if ("DONE".equals(objectMapper.readTree(s).get("status").asText())) break;
    }
    mvc.perform(get("/exports/" + jobId2 + "/download").with(userJwt("dan")))
        .andExpect(status().isForbidden());
  }

  @Test
  void downloadExport_pendingJob_returns409() throws Exception {
    MvcResult submit =
        mvc.perform(
                post("/export")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"public-layer\"]}"))
            .andExpect(status().isAccepted())
            .andReturn();
    String jobId =
        objectMapper.readTree(submit.getResponse().getContentAsString()).get("id").asText();

    // Immediately try to download — may be PENDING/RUNNING (409) or already done (200)
    int sc =
        mvc.perform(get("/exports/" + jobId + "/download").with(adminJwt()))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(sc).isIn(200, 409);
  }

  @Test
  void downloadExport_completedJob_servesZipAndCleansUp() throws Exception {
    String jobId = submitExport("{\"layers\":[\"public-layer\"]}", adminJwt());
    waitAndDownload(jobId, adminJwt());
    // After download the job is removed — second request returns 404
    mvc.perform(get("/exports/" + jobId + "/download").with(adminJwt()))
        .andExpect(status().isNotFound());
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
    Path cacheDir = vectorDir.toPath().resolve("remote-cache").resolve("3").resolve("4");
    Files.createDirectories(cacheDir);
    Files.write(cacheDir.resolve("5.pbf"), new byte[] {0x1a, 0x2b});

    String jobId = submitExport("{\"includeVector\":true}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).containsKey("vector/tiles/3/4/5.pbf");
    assertThat(entries.get("vector/tiles/3/4/5.pbf")).containsExactly(0x1a, 0x2b);
  }

  @Test
  void export_includeVector_withLayers_includesBoth() throws Exception {
    Path cacheDir = vectorDir.toPath().resolve("remote-cache").resolve("2").resolve("1");
    Files.createDirectories(cacheDir);
    Files.write(cacheDir.resolve("0.pbf"), new byte[] {0x0f});

    String jobId =
        submitExport("{\"layers\":[\"public-layer\"],\"includeVector\":true}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
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
    byte[] zip = buildZip(Map.of("vector/tiles/x/y/z.pbf", new byte[] {0x01}));
    MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/zip", zip);
    mvc.perform(multipart("/import").file(file).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tilesWritten").value(0));
  }

  @Test
  void importZip_vectorPmtiles_unsafeName_isSkipped() throws Exception {
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
      String jobId = submitExport("{\"includeVector\":true}", adminJwt());
      Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
      assertThat(entries).doesNotContainKey("vector/tiles/0/0/0.pbf");
    } finally {
      vectorConfiguration.setDownloadDirectory(original);
    }
  }

  @Test
  void export_includeVector_pmtilesFiles_includedInZip() throws Exception {
    java.nio.file.Files.write(
        vectorDir.toPath().resolve("region.pmtiles"), new byte[] {0x01, 0x02});

    String jobId = submitExport("{\"includeVector\":true}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).containsKey("vector/pmtiles/region.pmtiles");
    assertThat(entries.get("vector/pmtiles/region.pmtiles")).containsExactly(0x01, 0x02);
  }

  @Test
  void export_includeVector_zoomFilters_excludeOutOfRangePbfTiles() throws Exception {
    Path cacheZ0 = vectorDir.toPath().resolve("remote-cache").resolve("0").resolve("0");
    Path cacheZ5 = vectorDir.toPath().resolve("remote-cache").resolve("5").resolve("10");
    java.nio.file.Files.createDirectories(cacheZ0);
    java.nio.file.Files.createDirectories(cacheZ5);
    java.nio.file.Files.write(cacheZ0.resolve("0.pbf"), new byte[] {0x01});
    java.nio.file.Files.write(cacheZ5.resolve("0.pbf"), new byte[] {0x02});

    String jobId = submitExport("{\"includeVector\":true,\"minZoom\":2,\"maxZoom\":4}", adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).doesNotContainKey("vector/tiles/0/0/0.pbf");
    assertThat(entries).doesNotContainKey("vector/tiles/5/10/0.pbf");
  }

  @Test
  void importZip_layerJsonWithMismatchedId_takesIdFromDirectory() throws Exception {
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
    assertThat(layerStore.getLayer("dir-name").get().getName()).isEqualTo("original-name");
  }

  // ── pmtiles bbox export ───────────────────────────────────────────────────

  @Test
  void export_includeVector_pmtilesOutsideBbox_isSkipped() throws Exception {
    byte[] pmtiles =
        buildMinimalPmtiles(
            (byte) 0, (byte) 1, 1_130_000_000, -440_000_000, 1_540_000_000, -100_000_000);
    Files.write(vectorDir.toPath().resolve("australia.pmtiles"), pmtiles);

    String body =
        "{\"includeVector\":true,"
            + "\"boundingBox\":{\"north\":60,\"south\":20,\"east\":-60,\"west\":-130,\"maxZoom\":1}}";
    String jobId = submitExport(body, adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).doesNotContainKey("vector/pmtiles/australia.pmtiles");
  }

  @Test
  void export_includeVector_pmtilesFullyInBbox_includedAsIs() throws Exception {
    URL url = getClass().getClassLoader().getResource("test_fixture_1.pmtiles");
    byte[] fixtureBytes = Files.readAllBytes(Paths.get(url.getPath()));
    Files.write(vectorDir.toPath().resolve("fixture-bbox.pmtiles"), fixtureBytes);

    String body =
        "{\"includeVector\":true,"
            + "\"boundingBox\":{\"north\":90,\"south\":-90,\"east\":180,\"west\":-180,\"maxZoom\":1}}";
    String jobId = submitExport(body, adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).containsKey("vector/pmtiles/fixture-bbox.pmtiles");
  }

  @Test
  void export_includeVector_pmtilesPartialBbox_extractsTilesInBbox() throws Exception {
    URL url = getClass().getClassLoader().getResource("test_fixture_1.pmtiles");
    byte[] fixtureBytes = Files.readAllBytes(Paths.get(url.getPath()));
    ByteBuffer bb = ByteBuffer.wrap(fixtureBytes).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(102, -1_800_000_000);
    bb.putInt(106, -900_000_000);
    bb.putInt(110, 1_800_000_000);
    bb.putInt(114, 900_000_000);
    Files.write(vectorDir.toPath().resolve("world-partial.pmtiles"), fixtureBytes);

    String body =
        "{\"includeVector\":true,"
            + "\"boundingBox\":{\"north\":85,\"south\":1,\"east\":180,\"west\":-180,\"maxZoom\":1}}";
    String jobId = submitExport(body, adminJwt());
    Map<String, byte[]> entries = waitAndDownload(jobId, adminJwt());
    assertThat(entries).doesNotContainKey("vector/pmtiles/world-partial.pmtiles");
    assertThat(entries).containsKey("vector/tiles/0/0/0.pbf");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static byte[] buildMinimalPmtiles(
      byte minZoom, byte maxZoom, int minLonE7, int minLatE7, int maxLonE7, int maxLatE7) {
    ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
    buf.put((byte) 'P')
        .put((byte) 'M')
        .put((byte) 'T')
        .put((byte) 'i')
        .put((byte) 'l')
        .put((byte) 'e')
        .put((byte) 's');
    buf.put((byte) 3);
    buf.putLong(127);
    buf.putLong(1);
    buf.putLong(128);
    buf.putLong(0);
    buf.putLong(128);
    buf.putLong(0);
    buf.putLong(128);
    buf.putLong(0);
    buf.position(96);
    buf.put((byte) 0);
    buf.put((byte) 1);
    buf.put((byte) 1);
    buf.put((byte) 1);
    buf.put(minZoom);
    buf.put(maxZoom);
    buf.putInt(minLonE7);
    buf.putInt(minLatE7);
    buf.putInt(maxLonE7);
    buf.putInt(maxLatE7);
    buf.position(127);
    buf.put((byte) 0);
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
