package org.lockard.xyztilecache;

import com.google.common.cache.CacheLoader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "false", matchIfMissing = true)
public class OnlineCacheLoader extends CacheLoader<Tile, byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OnlineCacheLoader.class);

  private final XyzConfiguration configuration;

  private final OfflineCacheLoader offlineCacheLoader;

  private final TileWriter tileWriter;

  private final HttpClient httpClient;

  private final Set<String> layerLocks = Collections.synchronizedSet(new HashSet<>());

  public OnlineCacheLoader(final XyzConfiguration configuration, final TileWriter tileWriter) {
    this.configuration = configuration;
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

    final var requestStrategy = tile.layer().requestStrategy();
    if (requestStrategy == Layer.RequestStrategy.PROCEED) {
      return loadTileOnline(tile);
    } else if (requestStrategy == Layer.RequestStrategy.RETRY
        && layerLocks.add(tile.layer().getName())) {
      LOGGER.info("Retrying source for layer {}.", tile.layer());
      try {
        return loadTileOnline(tile);
      } finally {
        layerLocks.remove(tile.layer().getName());
      }
    } else {
      throw new IOException("Source for layer %s is temporarily blocked.".formatted(tile.layer()));
    }
  }

  private byte[] loadTileOnline(final Tile tile)
      throws InterruptedException, IOException, URISyntaxException {
    LOGGER.debug("Loading tile {} from an online source.", tile);
    final byte[] tileData;
    final var start = System.currentTimeMillis();
    try {
      tileData = getTileFromSource(tile);
    } catch (InterruptedException | IOException | URISyntaxException e) {
      tile.layer().sourceFailed();
      throw e;
    }

    LOGGER.debug("Tile retrieval time: {} ms", System.currentTimeMillis() - start);

    if (tileData != null && tileData.length > 0) {
      tile.layer().sourceSucceeded();
      // storage is asynchronous
      tileWriter.storeTile(tile, tileData);
      return tileData;
    } else {
      tile.layer().sourceFailed();
      throw new IOException("Failed to retrieve tile %s.".formatted(tile));
    }
  }

  private byte[] getTileFromSource(Tile tile)
      throws InterruptedException, IOException, URISyntaxException {
    final var layer = tile.layer();
    final var urlBase = layer.getUrlTemplate();
    final var url =
        urlBase
            .replace("{x}", String.valueOf(tile.x()))
            .replace("{y}", String.valueOf(tile.y()))
            .replace("{z}", String.valueOf(tile.z()));
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
    requestBuilder.header("Accept-Encoding", "gzip, deflate, br");

    return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray()).body();
  }
}
