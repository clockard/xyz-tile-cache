package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.store.LayerStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests that spin up a WireMock container (TestContainers) as the upstream tile source
 * and exercise the full HTTP API via a real Spring Boot server on a random port.
 *
 * <p>Requires Docker. Auth uses token mode to avoid Keycloak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EndToEndTest {

  private static final String ADMIN_TOKEN = "e2e-admin-token";

  @Container
  static final GenericContainer<?> wireMockContainer =
      new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.9.2"))
          .withCommand("--disable-gzip")
          .withExposedPorts(8080);

  @TempDir static File tileDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", tileDir::getAbsolutePath);
    registry.add("xyz.auth.mode", () -> "token");
    registry.add("xyz.auth.adminToken", () -> ADMIN_TOKEN);
    registry.add(
        "xyz.layers",
        () -> {
          String tileBaseUrl =
              "http://" + wireMockContainer.getHost() + ":" + wireMockContainer.getMappedPort(8080);
          Layer osm = new Layer();
          osm.setName("osm");
          // URL template: {z}/{y}/{x} — matches ZYX path variable order
          osm.setUrlTemplate(tileBaseUrl + "/{z}/{y}/{x}");
          osm.setMaxZoom(18);
          return List.of(osm);
        });
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper objectMapper;
  @Autowired LayerStore layerStore;

  private WireMock wireMock;

  @BeforeEach
  void setupWireMock() {
    wireMock = new WireMock(wireMockContainer.getHost(), wireMockContainer.getMappedPort(8080));
    // Tests share a Spring context; reset any leftover circuit-breaker state per test.
    layerStore.getRuntimeState("osm").sourceSucceeded();
  }

  // ── GET /layers ───────────────────────────────────────────────────────────

  @Test
  void getLayers_anonymous_returnsConfiguredLayers() {
    ResponseEntity<List> response = http.getForEntity("/layers", List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotEmpty();
    assertThat(response.getBody()).extracting(o -> ((Map<?, ?>) o).get("name")).contains("osm");
  }

  // ── POST /layers ──────────────────────────────────────────────────────────

  @Test
  void createLayer_noToken_returns401() {
    Layer layer = new Layer();
    layer.setName("unauthorized-attempt");
    layer.setUrlTemplate("http://example.com/{z}/{y}/{x}");

    ResponseEntity<String> response =
        http.postForEntity("/layers", new HttpEntity<>(layer), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void layerCrudLifecycle_admin_createUpdateDelete() {
    Layer layer = new Layer();
    layer.setName("e2e-crud");
    layer.setUrlTemplate("http://example.com/tiles/{z}/{y}/{x}");
    layer.setMaxZoom(10);

    // Create
    ResponseEntity<Layer> created =
        http.postForEntity("/layers", new HttpEntity<>(layer, jsonAdminHeaders()), Layer.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().getEffectiveId()).isEqualTo("e2e-crud");

    // Update
    Layer update = new Layer();
    update.setName("e2e-crud");
    update.setUrlTemplate("http://example.com/updated/{z}/{y}/{x}");
    update.setMaxZoom(12);
    ResponseEntity<Layer> updated =
        http.exchange(
            "/layers/e2e-crud",
            HttpMethod.PUT,
            new HttpEntity<>(update, jsonAdminHeaders()),
            Layer.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody().getMaxZoom()).isEqualTo(12);

    // Delete
    ResponseEntity<Void> deleted =
        http.exchange(
            "/layers/e2e-crud", HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    // Confirm it's gone from the list
    ResponseEntity<List> layers = http.getForEntity("/layers", List.class);
    assertThat(layers.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .doesNotContain("e2e-crud");
  }

  // ── GET /tilesZYX ─────────────────────────────────────────────────────────

  @Test
  void getTile_publicLayer_fetchesFromUpstreamAndReturns200() {
    // URL /tilesZYX/osm/1/0/0.png → z=1,y=0,x=0 → upstream /1/0/0
    wireMock.register(
        WireMock.get(WireMock.urlEqualTo("/1/0/0"))
            .willReturn(WireMock.aResponse().withStatus(200).withBody(new byte[] {1, 2, 3})));

    ResponseEntity<byte[]> response = http.getForEntity("/tilesZYX/osm/1/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).contains("image/png");
    assertThat(response.getBody()).containsExactly(1, 2, 3);
  }

  @Test
  void getTile_tileServedTwice_secondCallComesFromGuavaCache() {
    // First call: upstream returns [9]. Override stub to return [99] afterward.
    // If Guava cache is working, the second call will still return [9] without hitting upstream.
    wireMock.register(
        WireMock.get(WireMock.urlEqualTo("/2/0/0"))
            .willReturn(WireMock.aResponse().withStatus(200).withBody(new byte[] {9})));

    ResponseEntity<byte[]> first = http.getForEntity("/tilesZYX/osm/2/0/0.png", byte[].class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody()).containsExactly(9);

    // Change what WireMock would serve — cache must shield the second request from this
    wireMock.register(
        WireMock.get(WireMock.urlEqualTo("/2/0/0"))
            .willReturn(WireMock.aResponse().withStatus(200).withBody(new byte[] {99})));

    ResponseEntity<byte[]> second = http.getForEntity("/tilesZYX/osm/2/0/0.png", byte[].class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).containsExactly(9); // still [9], not [99]
  }

  @Test
  void getTile_unknownLayer_returns400() {
    ResponseEntity<String> response =
        http.getForEntity("/tilesZYX/no-such-layer/1/0/0.png", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void getTile_zoomExceedsLayerMax_returns404() {
    // osm has maxZoom=18; request z=23
    ResponseEntity<byte[]> response = http.getForEntity("/tilesZYX/osm/23/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getTile_upstreamReturns404_propagatesNotFound() {
    wireMock.register(
        WireMock.get(WireMock.urlEqualTo("/3/0/0"))
            .willReturn(WireMock.aResponse().withStatus(404)));

    ResponseEntity<byte[]> response = http.getForEntity("/tilesZYX/osm/3/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getTile_cachedOnDisk_servedWithoutCallingUpstream() throws Exception {
    // Disk layout is {z}/{x}/{y}.png; URL path variables are {z}/{y}/{x}
    // Request: z=6, y=5, x=5 (symmetric) → disk at osm/6/5/5.png, upstream /6/5/5
    java.nio.file.Path tilePath =
        tileDir.toPath().resolve("osm").resolve("6").resolve("5").resolve("5.png");
    Files.createDirectories(tilePath.getParent());
    Files.write(tilePath, new byte[] {9, 8, 7});

    ResponseEntity<byte[]> response = http.getForEntity("/tilesZYX/osm/6/5/5.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactly(9, 8, 7);
    // No stub registered for /6/5/5 — if the app hit the upstream, WireMock would return 404
    // and the 200 assertion above would have failed. Disk serving is implicitly verified.
  }

  @Test
  void tilesZXY_endpoint_alsoServesTiles() {
    wireMock.register(
        WireMock.get(WireMock.urlEqualTo("/4/0/0"))
            .willReturn(WireMock.aResponse().withStatus(200).withBody(new byte[] {7})));

    // /tilesZXY/{layer}/{z}/{x}/{y}.png — x and y swapped in URL but same tile
    ResponseEntity<byte[]> response = http.getForEntity("/tilesZXY/osm/4/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // ── GET /stats ────────────────────────────────────────────────────────────

  @Test
  void getStats_anonymous_returnsStatsWithLayerInfo() {
    ResponseEntity<Map> response = http.getForEntity("/stats", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsKeys("instanceId", "tilesServedByInstance", "layers");
    assertThat((List<?>) response.getBody().get("layers")).isNotEmpty();
  }

  // ── POST /export ──────────────────────────────────────────────────────────

  @Test
  void export_noToken_returns401() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        http.postForEntity(
            "/export", new HttpEntity<>("{\"layers\":[\"osm\"]}", headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void export_unknownLayer_returns404() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/export",
            new HttpEntity<>("{\"layers\":[\"does-not-exist\"]}", jsonAdminHeaders()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void export_admin_returnsZipContainingLayerJson() throws Exception {
    byte[] zip = doExport("{\"layers\":[\"osm\"]}");
    Map<String, byte[]> entries = readZip(zip);
    assertThat(entries).containsKey("osm/layer.json");
    Layer exported = objectMapper.readValue(entries.get("osm/layer.json"), Layer.class);
    assertThat(exported.getEffectiveId()).isEqualTo("osm");
  }

  @Test
  void export_admin_includesTilesFromDisk() throws Exception {
    // Seed a tile: z=8, x=0, y=0 → disk at osm/8/0/0.png
    java.nio.file.Path tilePath =
        tileDir.toPath().resolve("osm").resolve("8").resolve("0").resolve("0.png");
    Files.createDirectories(tilePath.getParent());
    Files.write(tilePath, new byte[] {0x42});

    byte[] zip = doExport("{\"layers\":[\"osm\"]}");
    Map<String, byte[]> entries = readZip(zip);
    assertThat(entries).containsKey("osm/8/0/0.png");
    assertThat(entries.get("osm/8/0/0.png")).containsExactly(0x42);
  }

  // ── POST /import ──────────────────────────────────────────────────────────

  @Test
  void import_noToken_returns401() throws Exception {
    byte[] zip = buildZip(Map.of("osm/1/0/0.png", new byte[] {1}));

    ResponseEntity<String> response =
        http.postForEntity("/import", multipartRequest(zip, new HttpHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void import_admin_registersNewLayerAndWritesTile() throws Exception {
    Layer fresh = new Layer();
    fresh.setName("e2e-imported");
    fresh.setUrlTemplate("https://example.com/imported/{z}/{y}/{x}");
    fresh.setMaxZoom(8);

    byte[] zip =
        buildZip(
            Map.of(
                "e2e-imported/layer.json",
                objectMapper.writeValueAsBytes(fresh),
                "e2e-imported/2/1/0.png",
                new byte[] {42, 43}));

    ResponseEntity<Map> response =
        http.postForEntity("/import", multipartRequest(zip, adminHeaders()), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) response.getBody().get("layersAdded")).contains("e2e-imported");
    assertThat(response.getBody().get("tilesWritten")).isEqualTo(1);

    // Disk layout: {z}/{x}/{y}.png — z=2, x=1, y=0
    java.nio.file.Path tile =
        tileDir.toPath().resolve("e2e-imported").resolve("2").resolve("1").resolve("0.png");
    assertThat(Files.readAllBytes(tile)).containsExactly(42, 43);
  }

  @Test
  void import_zipSlipPath_returns400() throws Exception {
    byte[] zip = buildZip(Map.of("../escape.txt", new byte[] {0}));

    ResponseEntity<String> response =
        http.postForEntity("/import", multipartRequest(zip, adminHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void import_existingLayer_skipsLayerJsonButWritesTiles() throws Exception {
    // "osm" is already configured; importing its layer.json should be skipped
    Layer impostor = new Layer();
    impostor.setName("osm");
    impostor.setUrlTemplate("https://malicious.example.com/{z}/{y}/{x}");
    impostor.setMaxZoom(99);

    byte[] zip =
        buildZip(
            Map.of(
                "osm/layer.json",
                objectMapper.writeValueAsBytes(impostor),
                "osm/9/0/0.png",
                new byte[] {77}));

    ResponseEntity<Map> response =
        http.postForEntity("/import", multipartRequest(zip, adminHeaders()), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List) response.getBody().get("layersSkipped")).contains("osm");
    assertThat(response.getBody().get("tilesWritten")).isEqualTo(1);
  }

  // ── round-trip ────────────────────────────────────────────────────────────

  @Test
  void importThenExport_roundTrip_preservesTileContent() throws Exception {
    Layer layer = new Layer();
    layer.setName("e2e-roundtrip");
    layer.setUrlTemplate("https://example.com/rt/{z}/{y}/{x}");
    layer.setMaxZoom(5);
    byte[] tileData = {10, 20, 30, 40};

    // Import
    byte[] importZip =
        buildZip(
            Map.of(
                "e2e-roundtrip/layer.json",
                objectMapper.writeValueAsBytes(layer),
                "e2e-roundtrip/0/0/0.png",
                tileData));
    ResponseEntity<Map> importResp =
        http.postForEntity("/import", multipartRequest(importZip, adminHeaders()), Map.class);
    assertThat(importResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Export and verify tile content survived the round trip
    Map<String, byte[]> entries = readZip(doExport("{\"layers\":[\"e2e-roundtrip\"]}"));
    assertThat(entries).containsKey("e2e-roundtrip/layer.json");
    assertThat(entries).containsKey("e2e-roundtrip/0/0/0.png");
    assertThat(entries.get("e2e-roundtrip/0/0/0.png")).containsExactly(10, 20, 30, 40);

    Layer roundTripped =
        objectMapper.readValue(entries.get("e2e-roundtrip/layer.json"), Layer.class);
    assertThat(roundTripped.getEffectiveId()).isEqualTo("e2e-roundtrip");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private byte[] doExport(String jsonBody) throws Exception {
    ResponseEntity<Map> submit =
        http.postForEntity("/export", new HttpEntity<>(jsonBody, jsonAdminHeaders()), Map.class);
    assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String jobId = (String) submit.getBody().get("id");

    String status = "PENDING";
    for (int i = 0; i < 100 && !"DONE".equals(status) && !"FAILED".equals(status); i++) {
      Thread.sleep(100);
      ResponseEntity<Map> statusResp =
          http.exchange(
              "/exports/" + jobId, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
      status = (String) statusResp.getBody().get("status");
    }
    assertThat(status).isEqualTo("DONE");

    ResponseEntity<byte[]> download =
        http.exchange(
            "/exports/" + jobId + "/download",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders()),
            byte[].class);
    assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
    return download.getBody();
  }

  private HttpHeaders adminHeaders() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(ADMIN_TOKEN);
    return h;
  }

  private HttpHeaders jsonAdminHeaders() {
    HttpHeaders h = adminHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private HttpEntity<?> multipartRequest(byte[] zip, HttpHeaders extraHeaders) {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(extraHeaders);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add(
        "file",
        new ByteArrayResource(zip) {
          @Override
          public String getFilename() {
            return "export.zip";
          }
        });
    return new HttpEntity<>(body, headers);
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
    Map<String, byte[]> result = new HashMap<>();
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          result.put(entry.getName(), zis.readAllBytes());
        }
      }
    }
    return result;
  }
}
