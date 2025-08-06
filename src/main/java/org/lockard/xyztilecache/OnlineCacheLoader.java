package org.lockard.xyztilecache;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "false", matchIfMissing = true)
public class OnlineCacheLoader extends OfflineCacheLoader {
  private static final Logger LOGGER = LoggerFactory.getLogger(OnlineCacheLoader.class);

  private final TileWriter tileWriter;

  public OnlineCacheLoader(final XyzConfiguration configuration, final TileWriter tileWriter) {
    super(configuration);
    LOGGER.info("new OnlineCacheLoader()");
    this.tileWriter = tileWriter;
  }

  @Override
  public byte[] load(final Tile tile) throws IOException {
    try {
      final var fileBytes = super.load(tile);
      LOGGER.info("Tile {} found in local file cache.", tile);
      return fileBytes;
    } catch (Exception e) {
      LOGGER.info("Failed to load tile {} from local file cache.", tile);
      LOGGER.debug("Failed to load tile {} from local file cache.", tile, e);
    }

    LOGGER.info("Loading tile {} from an online source.", tile);
    final byte[] tileData;
    final var start = System.currentTimeMillis();
    try {
      tileData = getTileFromSource(tile);
    } catch (Exception e) {
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

  private byte[] getTileFromSource(Tile tile) throws IOException {
    final var layer = tile.layer();
    final var urlBase = layer.getUrlTemplate();
    final var template =
        new RestTemplateBuilder()
            .setConnectTimeout(
                Duration.of(configuration.getTileTimeoutSeconds(), TimeUnit.SECONDS.toChronoUnit()))
            .setReadTimeout(
                Duration.of(configuration.getTileTimeoutSeconds(), TimeUnit.SECONDS.toChronoUnit()))
            .build();

    final var url =
        urlBase
            .replace("{x}", String.valueOf(tile.x()))
            .replace("{y}", String.valueOf(tile.y()))
            .replace("{z}", String.valueOf(tile.z()));
    LOGGER.debug("Tile url for {}: {}", tile, url);
    final var headers = new HttpHeaders();
    final var layerHeaders = layer.getHeaders();
    for (final var entry : layerHeaders.entrySet()) {
      headers.set(entry.getKey(), entry.getValue());
    }
    // User-Agent needed for some sources to respond properly
    headers.set(
        "User-Agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");
    headers.set("Accept-Encoding", "gzip, deflate, br");
    final var entity = new HttpEntity<>(null, headers);
    layer.setSourceLastChecked(System.currentTimeMillis());

    try {
      return template.exchange(url, HttpMethod.GET, entity, byte[].class).getBody();
    } catch (RuntimeException e) {
      LOGGER.debug("Error contacting tile source for {}", url);
      throw new IOException(e);
    }
  }
}
