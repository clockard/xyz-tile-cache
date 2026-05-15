package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests exercising per-layer access control with real Keycloak-issued JWTs.
 *
 * <p>Three layers are configured:
 *
 * <ul>
 *   <li>{@code public-layer} — no ACL, readable by everyone
 *   <li>{@code foresters-layer} — restricted to {@code allowedGroups: [team-foresters]}
 *   <li>{@code alice-layer} — restricted to {@code allowedUsers: [alice]}
 * </ul>
 *
 * <p>Test users (Keycloak realm {@code xyz-tile-cache}, password {@code password}):
 *
 * <ul>
 *   <li>{@code alice} — realm role {@code admin}, group {@code admins}
 *   <li>{@code bob} — group {@code team-foresters}
 *   <li>{@code carol} — group {@code team-imagery}
 *   <li>{@code dan} — no roles, no groups
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeycloakEndToEndTest {

  @Container
  static final KeycloakContainer keycloak =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFile("xyz-tile-cache-realm.json");

  @TempDir static File tileDir;
  @TempDir static File vectorDir;

  /**
   * Provide a {@link JwtDecoder} that validates both JWKS signature and the {@code iss} claim
   * against Keycloak's runtime URL.
   *
   * <p>Spring Security 6.5+ automatically adds an issuer validator when a JWK Set URI is
   * configured. When Keycloak runs in start-dev mode, the {@code iss} it embeds in tokens equals
   * the {@code Host} header the client uses — i.e. {@code http://localhost:<mapped-port>/realms/…}.
   * An auto-discovered issuer URI computed from a different source (e.g. the internal container
   * port) will not match, causing 401 failures. Providing an explicit {@code @Primary} decoder here
   * short-circuits that mismatch: Spring Security's filter chain selects this bean and the other
   * auto-configured decoder beans are not used.
   */
  @TestConfiguration
  static class JwtDecoderConfig {

    @Bean
    @Primary
    JwtDecoder keycloakJwtDecoder() {
      // Keycloak sets iss = authServerUrl/realms/<realm> in tokens it issues to callers
      // at authServerUrl. tokenFor() below uses getAuthServerUrl() as the base, so the
      // iss claim in each JWT will be this exact string.
      String issuerUri = keycloak.getAuthServerUrl() + "/realms/xyz-tile-cache";
      String certsUrl = issuerUri + "/protocol/openid-connect/certs";

      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(certsUrl).jwsAlgorithm(SignatureAlgorithm.RS256).build();
      // Use the same issuer the JWT will carry so iss-claim validation passes.
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
      return decoder;
    }
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", tileDir::getAbsolutePath);
    registry.add("xyz.vector.downloadDirectory", vectorDir::getAbsolutePath);
    registry.add("xyz.auth.mode", () -> "jwt");
    registry.add("xyz.offline", () -> "true");
    registry.add(
        "xyz.layers",
        () -> {
          Layer pub = new Layer();
          pub.setName("public-layer");
          pub.setUrlTemplate("http://unused/{z}/{y}/{x}");
          pub.setMaxZoom(5);

          Layer foresters = new Layer();
          foresters.setName("foresters-layer");
          foresters.setUrlTemplate("http://unused/{z}/{y}/{x}");
          foresters.setAllowedGroups(List.of("team-foresters"));
          foresters.setMaxZoom(5);

          Layer aliceOnly = new Layer();
          aliceOnly.setName("alice-layer");
          aliceOnly.setUrlTemplate("http://unused/{z}/{y}/{x}");
          aliceOnly.setAllowedUsers(List.of("alice"));
          aliceOnly.setMaxZoom(5);

          return List.of(pub, foresters, aliceOnly);
        });
  }

  /** Pre-seed tiles on disk so offline layer serving returns 200 rather than 404. */
  @BeforeAll
  static void seedTiles() throws Exception {
    for (String layerName : List.of("public-layer", "foresters-layer", "alice-layer")) {
      // Disk layout is {baseTileDir}/{layer}/{z}/{x}/{y}.png
      Path dir = tileDir.toPath().resolve(layerName).resolve("0").resolve("0");
      Files.createDirectories(dir);
      Files.write(dir.resolve("0.png"), new byte[] {0x42});
    }
  }

  @Autowired TestRestTemplate http;

  // ── token helpers ─────────────────────────────────────────────────────────

  /** Fetch a real JWT from Keycloak using the password grant. */
  private String tokenFor(String username) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "password");
    form.add("client_id", "xyz-tile-cache");
    form.add("username", username);
    form.add("password", "password");

    @SuppressWarnings("unchecked")
    Map<String, Object> body =
        new RestTemplate()
            .postForEntity(
                keycloak.getAuthServerUrl()
                    + "/realms/xyz-tile-cache/protocol/openid-connect/token",
                new HttpEntity<>(form, headers),
                Map.class)
            .getBody();

    String token = body != null ? (String) body.get("access_token") : null;
    assertThat(token)
        .as("Keycloak password grant must succeed for user '%s'", username)
        .isNotNull();
    return token;
  }

  private HttpHeaders bearerHeaders(String token) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    return h;
  }

  private HttpHeaders jsonBearerHeaders(String token) {
    HttpHeaders h = bearerHeaders(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  // ── GET /layers — visibility filtering ───────────────────────────────────

  @Test
  void getLayers_anonymous_seesOnlyPublicLayer() {
    ResponseEntity<List> response = http.getForEntity("/layers", List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .containsExactly("public-layer");
  }

  @Test
  void getLayers_alice_admin_seesAllLayers() {
    String token = tokenFor("alice");
    ResponseEntity<List> response =
        http.exchange(
            "/layers",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .containsExactlyInAnyOrder("public-layer", "foresters-layer", "alice-layer");
  }

  @Test
  void getLayers_bob_forester_seesPublicAndForestersLayers() {
    String token = tokenFor("bob");
    ResponseEntity<List> response =
        http.exchange(
            "/layers",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .containsExactlyInAnyOrder("public-layer", "foresters-layer");
  }

  @Test
  void getLayers_carol_imageryTeam_seesOnlyPublicLayer() {
    String token = tokenFor("carol");
    ResponseEntity<List> response =
        http.exchange(
            "/layers",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .containsExactly("public-layer");
  }

  @Test
  void getLayers_dan_noGroups_seesOnlyPublicLayer() {
    String token = tokenFor("dan");
    ResponseEntity<List> response =
        http.exchange(
            "/layers",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(o -> ((Map<?, ?>) o).get("name"))
        .containsExactly("public-layer");
  }

  // ── GET /tilesZYX — tile access by ACL ───────────────────────────────────

  @Test
  void getTile_publicLayer_anonymous_returns200() {
    ResponseEntity<byte[]> response =
        http.getForEntity("/tilesZYX/public-layer/0/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void getTile_forestersLayer_bob_inGroup_returns200() {
    String token = tokenFor("bob");
    ResponseEntity<byte[]> response =
        http.exchange(
            "/tilesZYX/foresters-layer/0/0/0.png",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void getTile_forestersLayer_carol_notInGroup_returns403() {
    String token = tokenFor("carol");
    ResponseEntity<byte[]> response =
        http.exchange(
            "/tilesZYX/foresters-layer/0/0/0.png",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void getTile_forestersLayer_anonymous_returns401() {
    ResponseEntity<byte[]> response =
        http.getForEntity("/tilesZYX/foresters-layer/0/0/0.png", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void getTile_aliceLayer_alice_returns200() {
    String token = tokenFor("alice");
    ResponseEntity<byte[]> response =
        http.exchange(
            "/tilesZYX/alice-layer/0/0/0.png",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void getTile_aliceLayer_bob_notInAllowedUsers_returns403() {
    String token = tokenFor("bob");
    ResponseEntity<byte[]> response =
        http.exchange(
            "/tilesZYX/alice-layer/0/0/0.png",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(token)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ── POST /layers — write access requires ROLE_ADMIN ──────────────────────

  @Test
  void createLayer_alice_admin_returns201() {
    String token = tokenFor("alice");
    Layer layer = new Layer();
    layer.setName("alice-created");
    layer.setUrlTemplate("http://example.com/{z}/{y}/{x}");
    layer.setMaxZoom(10);

    ResponseEntity<Layer> response =
        http.postForEntity(
            "/layers", new HttpEntity<>(layer, jsonBearerHeaders(token)), Layer.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().getEffectiveId()).isEqualTo("alice-created");

    // Cleanup
    http.exchange(
        "/layers/alice-created",
        org.springframework.http.HttpMethod.DELETE,
        new HttpEntity<>(bearerHeaders(token)),
        Void.class);
  }

  @Test
  void createLayer_bob_nonAdmin_returns403() {
    String token = tokenFor("bob");
    Layer layer = new Layer();
    layer.setName("bob-attempted");
    layer.setUrlTemplate("http://example.com/{z}/{y}/{x}");

    ResponseEntity<String> response =
        http.postForEntity(
            "/layers", new HttpEntity<>(layer, jsonBearerHeaders(token)), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void createLayer_noToken_returns401() {
    Layer layer = new Layer();
    layer.setName("anon-attempt");
    layer.setUrlTemplate("http://example.com/{z}/{y}/{x}");

    ResponseEntity<String> response =
        http.postForEntity("/layers", new HttpEntity<>(layer), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ── POST /preloads — requires ROLE_ADMIN ─────────────────────────────────

  @Test
  void createPreload_alice_admin_returns202() {
    String token = tokenFor("alice");
    String body =
        """
        {
          "name": "kc-preload",
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["public-layer"]
        }
        """;

    ResponseEntity<Map> response =
        http.postForEntity(
            "/preloads", new HttpEntity<>(body, jsonBearerHeaders(token)), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().get("name")).isEqualTo("kc-preload");

    String id = (String) response.getBody().get("id");
    http.exchange(
        "/preloads/" + id,
        org.springframework.http.HttpMethod.DELETE,
        new HttpEntity<>(bearerHeaders(token)),
        Void.class);
  }

  @Test
  void createPreload_bob_nonAdmin_returns403() {
    String token = tokenFor("bob");
    String body =
        """
        {
          "boundingBox": {"north": 1, "south": -1, "east": 1, "west": -1, "maxZoom": 0},
          "maxZoom": 0,
          "layers": ["public-layer"]
        }
        """;

    ResponseEntity<String> response =
        http.postForEntity(
            "/preloads", new HttpEntity<>(body, jsonBearerHeaders(token)), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
