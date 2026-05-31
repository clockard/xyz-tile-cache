package org.lockard.xyztilecache.cache;

import com.google.common.cache.CacheLoader;
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
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "false", matchIfMissing = true)
public class OnlineCacheLoader extends CacheLoader<Tile, byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OnlineCacheLoader.class);

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;

  private final OfflineCacheLoader offlineCacheLoader;

  private final TileWriter tileWriter;

  private final HttpClient httpClient;

  private final ConcurrentMap<String, CountDownLatch> retryLatches = new ConcurrentHashMap<>();

  public OnlineCacheLoader(
      final XyzConfiguration configuration,
      final TileWriter tileWriter,
      final LayerStore layerStore) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    offlineCacheLoader = new OfflineCacheLoader(configuration);
    this.tileWriter = tileWriter;
    httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.of(configuration.getTileTimeoutSeconds(), ChronoUnit.SECONDS))
            .build();
  }

  @Override
  public byte[] load(final Tile tile) throws InterruptedException, IOException, URISyntaxException {
    try {
      final var fileBytes = offlineCacheLoader.load(tile);
      LOGGER.debug("Tile {} found in local file cache.", tile);
      return fileBytes;
    } catch (Exception e) {
      LOGGER.debug("Failed to load tile {} from local file cache.", tile, e);
    }

    if (tile.layer().getSourceType() == Layer.SourceType.LOCAL) {
      throw new IOException("Tile %s not present for LOCAL layer.".formatted(tile));
    }

    if (tile.layer().getSourceType() == Layer.SourceType.VECTOR_PMTILES) {
      throw new IOException(
          "VECTOR_PMTILES layers are not served through the raster tile cache: %s"
              .formatted(tile.layer()));
    }

    if (configuration.isOffline()) {
      throw new IOException("Offline mode is enabled; tile %s not in local cache.".formatted(tile));
    }

    LayerRuntimeState state = layerStore.getRuntimeState(tile.layer().getEffectiveId());
    final var requestStrategy = state.requestStrategy();
    if (requestStrategy == Layer.RequestStrategy.PROCEED) {
      return loadTileOnline(tile, state);
    }
    if (requestStrategy == Layer.RequestStrategy.BLOCK) {
      throw new UpstreamUnavailableException(
          "Source for layer %s is temporarily blocked.".formatted(tile.layer()));
    }

    // RETRY: one thread probes the source; concurrent callers wait on a latch and then re-read
    // the state so they get a clean PROCEED / BLOCK answer instead of a false "blocked".
    String layerId = tile.layer().getEffectiveId();
    CountDownLatch ourLatch = new CountDownLatch(1);
    CountDownLatch existing = retryLatches.putIfAbsent(layerId, ourLatch);
    if (existing == null) {
      LOGGER.info("Retrying source for layer {}.", tile.layer());
      try {
        return loadTileOnline(tile, state);
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
      return loadTileOnline(tile, state);
    }
    throw new UpstreamUnavailableException(
        "Source for layer %s is temporarily blocked.".formatted(tile.layer()));
  }

  private byte[] loadTileOnline(final Tile tile, final LayerRuntimeState state)
      throws InterruptedException, IOException, URISyntaxException {
    LOGGER.debug("Loading tile {} from an online source.", tile);
    final byte[] tileData;
    final var start = System.currentTimeMillis();
    try {
      tileData = getTileFromSource(tile);
    } catch (InterruptedException | IOException | URISyntaxException e) {
      state.sourceFailed();
      throw e;
    }

    LOGGER.debug("Tile retrieval time: {} ms", System.currentTimeMillis() - start);

    if (tileData != null && tileData.length > 0) {
      state.sourceSucceeded();
      tileWriter.storeTile(tile, tileData);
      return tileData;
    } else {
      state.sourceFailed();
      throw new IOException("Failed to retrieve tile %s.".formatted(tile));
    }
  }

  private String buildTileUrl(Tile tile) {
    final var layer = tile.layer();
    String url =
        switch (layer.getSourceType()) {
          case XYZ ->
              layer
                  .getUrlTemplate()
                  .replace("{x}", String.valueOf(tile.x()))
                  .replace("{y}", String.valueOf(tile.y()))
                  .replace("{z}", String.valueOf(tile.z()));

          case WMTS_REST ->
              layer
                  .getUrlTemplate()
                  .replace("{TileMatrix}", String.valueOf(tile.z()))
                  .replace("{TileRow}", String.valueOf(tile.y()))
                  .replace("{TileCol}", String.valueOf(tile.x()));

          case WMTS_KVP ->
              layer.getUrlTemplate()
                  + "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                  + "&LAYER="
                  + layer.getWmtsLayerName()
                  + "&STYLE="
                  + layer.getWmtsStyle()
                  + "&FORMAT="
                  + layer.getWmtsFormat()
                  + "&TILEMATRIXSET="
                  + layer.getWmtsTileMatrixSet()
                  + "&TILEMATRIX="
                  + tile.z()
                  + "&TILEROW="
                  + tile.y()
                  + "&TILECOL="
                  + tile.x()
                  + (layer.isWmtsTime() ? "&TIME={time}" : "");

          case LOCAL ->
              throw new IllegalStateException(
                  "LOCAL layers should not reach buildTileUrl: " + layer);

          case VECTOR_PMTILES ->
              throw new IllegalStateException(
                  "VECTOR_PMTILES layers are not served through the raster tile cache: " + layer);
        };

    if (tile.layer().doesUrlHaveTime()) {
      final String timeStr =
          java.time.Instant.ofEpochMilli(System.currentTimeMillis())
              .atZone(java.time.ZoneOffset.UTC)
              .format(java.time.format.DateTimeFormatter.ofPattern(layer.getTimeFormat()));
      url = url.replace("{time}", timeStr);
    }
    return url;
  }

  private byte[] getTileFromSource(Tile tile)
      throws InterruptedException, IOException, URISyntaxException {
    final var layer = tile.layer();
    final var url = buildTileUrl(tile);
    LOGGER.debug("Tile url for {}: {}", tile, url);
    final var requestBuilder =
        HttpRequest.newBuilder(new URI(url))
            .GET()
            .timeout(Duration.of(configuration.getTileTimeoutSeconds(), ChronoUnit.SECONDS));
    final var layerHeaders = layer.getHeaders();
    layerHeaders.forEach(requestBuilder::header);
    // User-Agent needed for some sources to respond properly
    requestBuilder.header(
        "User-Agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");

    var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "Upstream returned HTTP " + response.statusCode() + " for tile " + tile);
    }
    return response.body();
  }
}
