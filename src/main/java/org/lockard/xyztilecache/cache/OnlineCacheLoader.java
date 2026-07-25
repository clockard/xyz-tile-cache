package org.lockard.xyztilecache.cache;

import com.github.benmanes.caffeine.cache.CacheLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.lockard.xyztilecache.model.LocalLayer;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.VectorPmtilesLayer;
import org.lockard.xyztilecache.model.WmtsKvpLayer;
import org.lockard.xyztilecache.model.WmtsRestLayer;
import org.lockard.xyztilecache.model.XyzLayer;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "false", matchIfMissing = true)
public class OnlineCacheLoader implements CacheLoader<Tile, byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OnlineCacheLoader.class);

  static final String UPSTREAM_FETCH_TIMER = "xyz_upstream_fetch_seconds";

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;

  private final OfflineCacheLoader offlineCacheLoader;

  private final TileWriter tileWriter;

  private final HttpClient httpClient;

  private final MeterRegistry meterRegistry;

  private final ConcurrentMap<String, CountDownLatch> retryLatches = new ConcurrentHashMap<>();

  public OnlineCacheLoader(
      final XyzConfiguration configuration,
      final TileWriter tileWriter,
      final LayerStore layerStore,
      final MeterRegistry meterRegistry) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    offlineCacheLoader = new OfflineCacheLoader(configuration, layerStore);
    this.tileWriter = tileWriter;
    this.meterRegistry = meterRegistry;
    httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.of(configuration.getTileTimeoutSeconds(), ChronoUnit.SECONDS))
            .build();
  }

  @Override
  public byte[] load(final Tile tile) throws InterruptedException, IOException, URISyntaxException {
    final Layer layer = layerStore.getLayers().get(tile.layerId());
    if (layer == null) {
      throw new IOException("Layer %s is not configured.".formatted(tile.layerId()));
    }

    byte[] staleBytes = null;
    try {
      final var fileBytes = offlineCacheLoader.load(tile);
      LOGGER.debug("Tile {} found in local file cache.", tile);
      return fileBytes;
    } catch (TileExpiredException e) {
      // Keep the expired bytes around: if the source turns out to be unavailable, serving stale
      // beats answering 404/503 for a tile we actually have.
      staleBytes = e.staleData();
      LOGGER.debug("Tile {} on disk is expired; refreshing from source.", tile);
    } catch (Exception e) {
      LOGGER.debug("Failed to load tile {} from local file cache.", tile, e);
    }

    if (layer instanceof LocalLayer) {
      throw new IOException("Tile %s not present for LOCAL layer.".formatted(tile));
    }

    if (layer instanceof VectorPmtilesLayer) {
      throw new IOException(
          "VECTOR_PMTILES layers are not served through the raster tile cache: %s"
              .formatted(layer));
    }

    if (configuration.isOffline()) {
      throw new IOException("Offline mode is enabled; tile %s not in local cache.".formatted(tile));
    }

    String layerId = layer.effectiveId();
    LayerRuntimeState state = layerStore.getRuntimeState(layerId);
    final var requestStrategy = state.requestStrategy();
    if (requestStrategy == Layer.RequestStrategy.PROCEED) {
      return loadOnlineOrStale(tile, layer, state, staleBytes);
    }
    if (requestStrategy == Layer.RequestStrategy.BLOCK) {
      throw blockedOrStale(layerId, staleBytes);
    }

    // RETRY: one thread probes the source; concurrent callers wait on a latch and then re-read
    // the state so they get a clean PROCEED / BLOCK answer instead of a false "blocked".
    CountDownLatch ourLatch = new CountDownLatch(1);
    CountDownLatch existing = retryLatches.putIfAbsent(layerId, ourLatch);
    if (existing == null) {
      LOGGER.info("Retrying source for layer {}.", layerId);
      try {
        return loadOnlineOrStale(tile, layer, state, staleBytes);
      } finally {
        retryLatches.remove(layerId);
        ourLatch.countDown();
      }
    }
    try {
      existing.await(configuration.getTileTimeoutSeconds() + 1L, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedException("Interrupted while waiting for retry of layer " + layerId);
    }
    Layer.RequestStrategy after = state.requestStrategy();
    if (after == Layer.RequestStrategy.PROCEED) {
      return loadOnlineOrStale(tile, layer, state, staleBytes);
    }
    throw blockedOrStale(layerId, staleBytes);
  }

  /**
   * Fetches from the source; if the fetch fails (not an authoritative 404) and expired disk bytes
   * exist, throws {@link StaleTileException} so the handler serves them without caching.
   */
  private byte[] loadOnlineOrStale(
      final Tile tile, final Layer layer, final LayerRuntimeState state, final byte[] staleBytes)
      throws InterruptedException, IOException, URISyntaxException {
    try {
      return loadTileOnline(tile, layer, state);
    } catch (UpstreamTileNotFoundException e) {
      // The source authoritatively has no tile; don't resurrect a stale copy.
      throw e;
    } catch (IOException | URISyntaxException e) {
      if (staleBytes != null) {
        LOGGER.debug("Upstream failed for {}; serving stale disk tile.", tile);
        throw new StaleTileException(staleBytes, e);
      }
      throw e;
    }
  }

  private IOException blockedOrStale(final String layerId, final byte[] staleBytes) {
    UpstreamUnavailableException blocked =
        new UpstreamUnavailableException(
            "Source for layer %s is temporarily blocked.".formatted(layerId));
    return staleBytes != null ? new StaleTileException(staleBytes, blocked) : blocked;
  }

  private byte[] loadTileOnline(final Tile tile, final Layer layer, final LayerRuntimeState state)
      throws InterruptedException, IOException, URISyntaxException {
    LOGGER.debug("Loading tile {} from an online source.", tile);
    final byte[] tileData;
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      tileData = getTileFromSource(tile, layer);
    } catch (UpstreamTileNotFoundException e) {
      // The source answered; a missing tile is not a source failure.
      state.sourceSucceeded();
      sample.stop(upstreamTimer(layer.effectiveId(), "not_found"));
      throw e;
    } catch (InterruptedException | IOException | URISyntaxException e) {
      state.sourceFailed();
      sample.stop(upstreamTimer(layer.effectiveId(), "failure"));
      throw e;
    }

    if (tileData != null && tileData.length > 0) {
      state.sourceSucceeded();
      sample.stop(upstreamTimer(layer.effectiveId(), "success"));
      tileWriter.storeTile(tile, tileData);
      return tileData;
    }
    // Empty body: the source answered but has no tile — also not a source failure.
    state.sourceSucceeded();
    sample.stop(upstreamTimer(layer.effectiveId(), "not_found"));
    throw new UpstreamTileNotFoundException("Empty tile body for %s.".formatted(tile));
  }

  private Timer upstreamTimer(String layerId, String outcome) {
    return Timer.builder(UPSTREAM_FETCH_TIMER)
        .description("Upstream tile-source HTTP fetch duration.")
        .tags(Tags.of(Tag.of("layer", layerId), Tag.of("outcome", outcome)))
        .publishPercentileHistogram()
        .register(meterRegistry);
  }

  private String buildTileUrl(Tile tile, Layer layer) {
    String timeStr = layer.doesUrlHaveTime() ? currentTimeString(layer.timeFormat()) : null;
    return switch (layer) {
      case XyzLayer xyz -> xyz.buildUrl(tile.z(), tile.x(), tile.y(), timeStr);
      case WmtsRestLayer wmts -> wmts.buildUrl(tile.z(), tile.x(), tile.y(), timeStr);
      case WmtsKvpLayer wmts -> wmts.buildUrl(tile.z(), tile.x(), tile.y(), timeStr);
      case LocalLayer ignored ->
          throw new IllegalStateException("LOCAL layers should not reach buildTileUrl: " + layer);
      case VectorPmtilesLayer ignored ->
          throw new IllegalStateException(
              "VECTOR_PMTILES layers are not served through the raster tile cache: " + layer);
    };
  }

  private static String currentTimeString(String pattern) {
    return java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        .atZone(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern(pattern));
  }

  private byte[] getTileFromSource(Tile tile, Layer layer)
      throws InterruptedException, IOException, URISyntaxException {
    final var url = buildTileUrl(tile, layer);
    LOGGER.debug("Tile url for {}: {}", tile, url);
    final var requestBuilder =
        HttpRequest.newBuilder(new URI(url))
            .GET()
            .timeout(Duration.of(configuration.getTileTimeoutSeconds(), ChronoUnit.SECONDS));
    final var layerHeaders = layer.headers();
    layerHeaders.forEach(requestBuilder::header);
    // User-Agent needed for some sources to respond properly
    requestBuilder.header(
        "User-Agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");

    var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() == 404) {
      throw new UpstreamTileNotFoundException("Upstream returned HTTP 404 for tile " + tile);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "Upstream returned HTTP " + response.statusCode() + " for tile " + tile);
    }
    return response.body();
  }
}
