package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for /preloads (CRUD lifecycle) and /vector (tile serving from a real PMTiles
 * file seeded in the download directory).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PreloadEndToEndTest {

  private static final String ADMIN_TOKEN = "e2e-preload-token";

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
          LayerProperties layer = new LayerProperties();
          layer.setName("osm");
          layer.setUrlTemplate(tileBaseUrl + "/{z}/{y}/{x}");
          layer.setMaxZoom(18);
          return List.of(layer);
        });
  }

  @Autowired TestRestTemplate http;

  private WireMock wireMock;

  @BeforeEach
  void setupWireMock() {
    wireMock = new WireMock(wireMockContainer.getHost(), wireMockContainer.getMappedPort(8080));
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

  // ── POST /preloads ────────────────────────────────────────────────────────

  @Test
  void createPreload_noToken_returns401() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        """
        {
          "name": "unauth-preload",
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["osm"]
        }
        """;

    ResponseEntity<String> response =
        http.postForEntity("/preloads", new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void createPreload_missingBoundingBox_returns400() {
    String body = "{\"name\": \"no-bbox\", \"maxZoom\": 0, \"layers\": [\"osm\"]}";

    ResponseEntity<String> response =
        http.postForEntity("/preloads", new HttpEntity<>(body, jsonAdminHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void createPreload_noValidLayers_returns400() {
    String body =
        """
        {
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["unknown-layer-xyz"]
        }
        """;

    ResponseEntity<String> response =
        http.postForEntity("/preloads", new HttpEntity<>(body, jsonAdminHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ── full CRUD lifecycle ───────────────────────────────────────────────────

  @Test
  void createListDeletePreload_fullLifecycle() {
    String body =
        """
        {
          "name": "e2e-preload",
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["osm"]
        }
        """;

    // Create → 202
    ResponseEntity<Map> created =
        http.postForEntity("/preloads", new HttpEntity<>(body, jsonAdminHeaders()), Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(created.getBody()).containsKey("id");
    String id = (String) created.getBody().get("id");
    assertThat(created.getBody().get("name")).isEqualTo("e2e-preload");
    assertThat((List) created.getBody().get("layers")).contains("osm");

    // List → preload appears
    ResponseEntity<List> listed = http.getForEntity("/preloads", List.class);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listed.getBody()).isNotEmpty();
    assertThat(listed.getBody())
        .anyMatch(
            o -> {
              Map<?, ?> m = (Map<?, ?>) o;
              return id.equals(m.get("id"));
            });

    // Delete → 204
    ResponseEntity<Void> deleted =
        http.exchange(
            "/preloads/" + id, HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    // Confirm gone
    ResponseEntity<List> after = http.getForEntity("/preloads", List.class);
    assertThat(after.getBody())
        .noneMatch(
            o -> {
              Map<?, ?> m = (Map<?, ?>) o;
              return id.equals(m.get("id"));
            });
  }

  @Test
  void createPreload_withAcl_visibleToAdminAndPresentInList() {
    String body =
        """
        {
          "name": "acl-preload",
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["osm"],
          "allowedUsers": ["alice"],
          "allowedGroups": ["team-foresters"]
        }
        """;

    ResponseEntity<Map> created =
        http.postForEntity("/preloads", new HttpEntity<>(body, jsonAdminHeaders()), Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    String id = (String) created.getBody().get("id");
    assertThat((List) created.getBody().get("allowedUsers")).contains("alice");
    assertThat((List) created.getBody().get("allowedGroups")).contains("team-foresters");

    // Admin token can see ACL-restricted preloads (admin bypasses all ACLs).
    // Must send the token on GET too — the security context is anonymous without it.
    ResponseEntity<List> listed =
        http.exchange("/preloads", HttpMethod.GET, new HttpEntity<>(adminHeaders()), List.class);
    assertThat(listed.getBody()).anyMatch(o -> id.equals(((Map<?, ?>) o).get("id")));

    // Cleanup
    http.exchange(
        "/preloads/" + id, HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
  }

  @Test
  void deletePreload_notFound_returns404() {
    ResponseEntity<Void> response =
        http.exchange(
            "/preloads/non-existent-id",
            HttpMethod.DELETE,
            new HttpEntity<>(adminHeaders()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
