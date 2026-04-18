package org.lockard.xyztilecache;

import com.google.common.cache.CacheLoader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "true")
public class OfflineCacheLoader extends CacheLoader<Tile, byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OfflineCacheLoader.class);

  protected final XyzConfiguration configuration;

  public OfflineCacheLoader(final XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public byte[] load(final Tile tile) throws Exception {
    LOGGER.debug("Loading tile {} from local file cache.", tile);
    final File file = toFile(tile);
    final int expirationMinutes = tile.layer().getTileExpirationMinutes();
    if (expirationMinutes > 0 && file.exists()) {
      final long ageMs = System.currentTimeMillis() - file.lastModified();
      if (ageMs > expirationMinutes * 60_000L) {
        LOGGER.debug("Tile {} has expired ({} mins old), evicting.", tile, ageMs / 60_000);
        throw new Exception("Tile expired");
      }
    }
    try (final var fis = new FileInputStream(file);
        final var bis = new BufferedInputStream(fis)) {
      return bis.readAllBytes();
    }
  }

  protected File toFile(final Tile tile) {
    return Paths.get(
            configuration.getBaseTileDirectory(),
            tile.layer().getName(),
            String.valueOf(tile.z()),
            String.valueOf(tile.x()),
            tile.y() + ".png")
        .toFile();
  }
}
