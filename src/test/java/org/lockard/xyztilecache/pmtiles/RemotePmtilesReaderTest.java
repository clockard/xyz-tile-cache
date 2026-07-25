package org.lockard.xyztilecache.pmtiles;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.lockard.xyztilecache.model.TileResult;

class RemotePmtilesReaderTest {

  private static final byte[] FIXTURE_BYTES = loadFixture();

  private static byte[] loadFixture() {
    try {
      URL url =
          RemotePmtilesReaderTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
      return Files.readAllBytes(Path.of(url.toURI()));
    } catch (Exception e) {
      throw new RuntimeException("Failed to load test_fixture_1.pmtiles", e);
    }
  }

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(
              wireMockConfig()
                  .dynamicPort()
                  .extensions(new TestRangeResponseTransformer(FIXTURE_BYTES)))
          .build();

  @BeforeEach
  void stubPmtilesEndpoint() {
    wireMock.stubFor(
        get(urlEqualTo("/test.pmtiles"))
            .willReturn(aResponse().withStatus(206).withTransformers("range")));
    // 200 OK is also accepted per the PMTiles spec (some servers don't support 206)
    wireMock.stubFor(
        get(urlEqualTo("/test-200.pmtiles"))
            .willReturn(aResponse().withStatus(200).withTransformers("range")));
  }

  @Test
  void getTile_presentInFixture_returnsTile() throws Exception {
    RemotePmtilesReader reader = newReader();
    // z=0, x=0, y=0 is in test_fixture_1.pmtiles
    Optional<TileResult> result = reader.getTile(0, 0, 0);
    assertThat(result).isPresent();
    assertThat(result.get().data()).isNotEmpty();
  }

  @Test
  void getTile_z1KnownTile_returnsTile() throws Exception {
    RemotePmtilesReader reader = newReader();
    // z=1, x=1, y=1 is in test_fixture_1.pmtiles
    Optional<TileResult> result = reader.getTile(1, 1, 1);
    assertThat(result).isPresent();
  }

  @Test
  void getTile_notInFixture_returnsEmpty() throws Exception {
    RemotePmtilesReader reader = newReader();
    // z=1, x=0, y=0 is NOT in test_fixture_1.pmtiles
    Optional<TileResult> result = reader.getTile(1, 0, 0);
    assertThat(result).isEmpty();
  }

  @Test
  void getTile_highZoom_returnsEmpty() throws Exception {
    RemotePmtilesReader reader = newReader();
    Optional<TileResult> result = reader.getTile(20, 0, 0);
    assertThat(result).isEmpty();
  }

  @Test
  void getTile_sharedReaderUsesLeafCache() throws Exception {
    // Two calls on the same reader exercise the leaf-cache path
    RemotePmtilesReader reader = newReader();
    reader.getTile(0, 0, 0);
    Optional<TileResult> second = reader.getTile(1, 1, 1);
    assertThat(second).isPresent();
  }

  @Test
  void getTile_initializationFails_returnsEmpty() throws Exception {
    // Port 1 is privileged and never open — connection refused → IOException in fetchRange
    // ensureInitialized catches it, leaves header null → getTile returns empty
    RemotePmtilesReader reader =
        new RemotePmtilesReader("http://localhost:1/test.pmtiles", HttpClient.newHttpClient(), 2);
    assertThat(reader.getTile(0, 0, 0)).isEmpty();
  }

  @Test
  void getTile_alreadyInitialized_fastPath() throws Exception {
    RemotePmtilesReader reader = newReader();
    // First call initializes; second call takes the fast path (no synchronized block entered)
    reader.getTile(0, 0, 0);
    Optional<TileResult> second = reader.getTile(0, 0, 0);
    assertThat(second).isPresent();
  }

  @Test
  void getTile_retriesAfterBackoff_recoversFromInitFailure() throws Exception {
    // First, stub the URL with a 500 so init fails
    wireMock.stubFor(
        get(urlEqualTo("/recoverable.pmtiles")).willReturn(aResponse().withStatus(500)));
    RemotePmtilesReader reader =
        new RemotePmtilesReader(
            wireMock.baseUrl() + "/recoverable.pmtiles", HttpClient.newHttpClient(), 10);

    // First call: init fails, header stays null
    assertThat(reader.getTile(0, 0, 0)).isEmpty();

    // Reset the backoff so the next call can retry immediately
    java.lang.reflect.Field f = RemotePmtilesReader.class.getDeclaredField("lastInitAttemptMs");
    f.setAccessible(true);
    f.setLong(reader, 0L);

    // Re-stub with a working range response and retry
    wireMock.stubFor(
        get(urlEqualTo("/recoverable.pmtiles"))
            .willReturn(aResponse().withStatus(206).withTransformers("range")));
    assertThat(reader.getTile(0, 0, 0)).isPresent();
  }

  @Test
  void getTile_withinBackoffWindow_doesNotRetry() throws Exception {
    wireMock.stubFor(get(urlEqualTo("/locked.pmtiles")).willReturn(aResponse().withStatus(500)));
    RemotePmtilesReader reader =
        new RemotePmtilesReader(
            wireMock.baseUrl() + "/locked.pmtiles", HttpClient.newHttpClient(), 10);

    assertThat(reader.getTile(0, 0, 0)).isEmpty();
    int requestsAfterFirst = wireMock.getAllServeEvents().size();

    // Without resetting the backoff timer, a second call must not hit the network again
    assertThat(reader.getTile(0, 0, 0)).isEmpty();
    assertThat(wireMock.getAllServeEvents()).hasSize(requestsAfterFirst);
  }

  @Test
  void getTile_serverReturns200_treatedAsSuccess() throws Exception {
    // Some servers return 200 OK instead of 206 Partial Content for range requests;
    // RemotePmtilesReader accepts both.
    RemotePmtilesReader reader =
        new RemotePmtilesReader(
            wireMock.baseUrl() + "/test-200.pmtiles", HttpClient.newHttpClient(), 10);
    Optional<TileResult> result = reader.getTile(0, 0, 0);
    assertThat(result).isPresent();
  }

  private RemotePmtilesReader newReader() {
    return new RemotePmtilesReader(
        wireMock.baseUrl() + "/test.pmtiles", HttpClient.newHttpClient(), 10);
  }
}
