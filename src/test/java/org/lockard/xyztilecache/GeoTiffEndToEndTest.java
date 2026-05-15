package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests for POST /layers/geotiff.
 *
 * <p>Covers auth (401), request validation (400), conflict (409), and tiling failure (422). Because
 * {@code gdal2tiles.py} is not available outside the Docker image, any upload of a real or stub
 * GeoTIFF file will result in a 422 response — which is itself a valid E2E signal that the request
 * reached the tiling step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GeoTiffEndToEndTest {

  private static final String ADMIN_TOKEN = "e2e-geotiff-token";

  // A minimal 3-byte stub used to bypass the "empty file" check and reach the tiler.
  private static final byte[] STUB_TIFF = new byte[] {0x49, 0x49, 0x2A}; // TIFF little-endian magic

  @TempDir static File tileDir;
  @TempDir static File vectorDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", tileDir::getAbsolutePath);
    registry.add("xyz.vector.downloadDirectory", vectorDir::getAbsolutePath);
    registry.add("xyz.auth.mode", () -> "token");
    registry.add("xyz.auth.adminToken", () -> ADMIN_TOKEN);
    registry.add(
        "xyz.layers",
        () -> {
          Layer existing = new Layer();
          existing.setName("already-exists");
          existing.setUrlTemplate("http://example.com/{z}/{y}/{x}");
          return List.of(existing);
        });
  }

  @Autowired TestRestTemplate http;

  private HttpHeaders adminHeaders() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(ADMIN_TOKEN);
    return h;
  }

  private HttpEntity<?> geotiffRequest(String name, byte[] content, HttpHeaders extra) {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(extra);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    if (name != null) {
      body.add("name", name);
    }
    if (content != null) {
      body.add(
          "file",
          new ByteArrayResource(content) {
            @Override
            public String getFilename() {
              return "upload.tif";
            }
          });
    }
    return new HttpEntity<>(body, headers);
  }

  // ── auth ──────────────────────────────────────────────────────────────────

  @Test
  void upload_noToken_returns401() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff", geotiffRequest("mymap", STUB_TIFF, new HttpHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ── name validation ───────────────────────────────────────────────────────

  @Test
  void upload_invalidName_specialChars_returns400() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff",
            geotiffRequest("bad name!", STUB_TIFF, adminHeaders()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("Layer name must be");
  }

  @Test
  void upload_invalidName_pathTraversal_returns400() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff",
            geotiffRequest("../escape", STUB_TIFF, adminHeaders()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void upload_emptyName_returns400() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff", geotiffRequest("", STUB_TIFF, adminHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ── file validation ───────────────────────────────────────────────────────

  @Test
  void upload_emptyFile_returns400() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff", geotiffRequest("mymap", new byte[0], adminHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("file is required");
  }

  // ── conflict ──────────────────────────────────────────────────────────────

  @Test
  void upload_layerNameAlreadyExists_returns409() {
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff",
            geotiffRequest("already-exists", STUB_TIFF, adminHeaders()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).contains("already exists");
  }

  // ── tiling ────────────────────────────────────────────────────────────────

  @Test
  void upload_validNameAndFile_gdal2tilesNotAvailable_returns422() {
    // gdal2tiles.py is only available inside the Docker image; running outside it causes
    // GeoTiffTiler to throw IOException, which the controller maps to 422 Unprocessable Entity.
    ResponseEntity<String> response =
        http.postForEntity(
            "/layers/geotiff",
            geotiffRequest("new-geotiff-layer", STUB_TIFF, adminHeaders()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void upload_tilingFailure_doesNotLeaveOrphanedLayer() {
    // After a 422, no layer should be registered for the given name.
    http.postForEntity(
        "/layers/geotiff", geotiffRequest("orphan-check", STUB_TIFF, adminHeaders()), String.class);

    ResponseEntity<List> layers = http.getForEntity("/layers", List.class);
    assertThat(layers.getBody())
        .extracting(o -> ((java.util.Map<?, ?>) o).get("name"))
        .doesNotContain("orphan-check");
  }
}
