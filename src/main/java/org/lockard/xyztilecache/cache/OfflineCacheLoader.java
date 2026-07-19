package org.lockard.xyztilecache.cache;

import com.github.benmanes.caffeine.cache.CacheLoader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xyz.offline", havingValue = "true")
public class OfflineCacheLoader implements CacheLoader<Tile, byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OfflineCacheLoader.class);

  protected final XyzConfiguration configuration;
  protected final LayerStore layerStore;

  public OfflineCacheLoader(final XyzConfiguration configuration, final LayerStore layerStore) {
    this.configuration = configuration;
    this.layerStore = layerStore;
  }

  @Override
  public byte[] load(final Tile tile) throws Exception {
    LOGGER.debug("Loading tile {} from local file cache.", tile);
    final Layer layer = requireLayer(tile);
    final File file = toFile(tile);
    final int expirationMinutes = layer.tileExpirationMinutes();
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

  public File toFile(final Tile tile) {
    String ext = requireLayer(tile).tileFileExtension();
    File preferred = tilePath(tile, ext);
    if (preferred.exists() || ext.equals("png")) {
      return preferred;
    }
    File pngFallback = tilePath(tile, "png");
    return pngFallback.exists() ? pngFallback : preferred;
  }

  private Layer requireLayer(final Tile tile) {
    Layer layer = layerStore.getLayers().get(tile.layerId());
    if (layer == null) {
      throw new IllegalArgumentException("Layer %s is not configured.".formatted(tile.layerId()));
    }
    return layer;
  }

  private File tilePath(final Tile tile, final String ext) {
    return Paths.get(
            configuration.getBaseTileDirectory(),
            tile.layerId(),
            String.valueOf(tile.z()),
            String.valueOf(tile.x()),
            tile.y() + "." + ext)
        .toFile();
  }
}
