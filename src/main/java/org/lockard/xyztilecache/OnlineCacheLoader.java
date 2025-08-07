package org.lockard.xyztilecache;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "false", matchIfMissing = true)
public class OnlineCacheLoader extends OfflineCacheLoader {
  private static final Logger LOGGER = LoggerFactory.getLogger(OnlineCacheLoader.class);

  private final TileWriter tileWriter;

  private final HttpClient httpClient;

  public OnlineCacheLoader(final XyzConfiguration configuration, final TileWriter tileWriter) {
    super(configuration);
    this.tileWriter = tileWriter;
    httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.of(configuration.getTileTimeoutSeconds(), ChronoUnit.SECONDS))
            .build();
  }

  @Override
  public byte[] load(final Tile tile) throws InterruptedException, IOException, URISyntaxException {
    try {
      final var fileBytes = super.load(tile);
      LOGGER.debug("Tile {} found in local file cache.", tile);
      return fileBytes;
    } catch (Exception e) {
      LOGGER.debug("Failed to load tile {} from local file cache.", tile, e);
    }

    LOGGER.debug("Loading tile {} from an online source.", tile);
    final byte[] tileData;
    final var start = System.currentTimeMillis();
    try {
      tileData = getTileFromSource(tile);
    } catch (InterruptedException | IOException | URISyntaxException e) {
      tile.layer().setSourceAvailable(false);
      throw e;
    }

    LOGGER.debug("Tile retrieval time: {} ms", System.currentTimeMillis() - start);

    if (tileData != null && tileData.length > 0) {
      tile.layer().setSourceAvailable(true);
      // storage is asynchronous
      tileWriter.storeTile(tile, tileData);
      return tileData;
    } else {
      tile.layer().setSourceAvailable(false);
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
    layer.setSourceLastChecked(System.currentTimeMillis());

    return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray()).body();
  }
}
