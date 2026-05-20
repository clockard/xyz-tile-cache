package org.lockard.xyztilecache.pmtiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.TileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

public class VectorTileCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTileCache.class);

  private final Path cacheDir;
  private final XyzConfiguration xyzConfig;

  public VectorTileCache(Path cacheDir, XyzConfiguration xyzConfig) {
    this.cacheDir = cacheDir;
    this.xyzConfig = xyzConfig;
  }

  public Optional<TileResult> get(int z, int x, int y) {
    Path path = cachePath(z, x, y);
    if (!Files.exists(path)) {
      return Optional.empty();
    }
    try {
      byte[] data = Files.readAllBytes(path);
      int compression =
          isGzip(data) ? PmtilesHeader.COMPRESSION_GZIP : PmtilesHeader.COMPRESSION_NONE;
      return Optional.of(new TileResult(data, compression));
    } catch (IOException e) {
      LOGGER.debug("Failed to read cached vector tile {}/{}/{}: {}", z, x, y, e.getMessage());
      return Optional.empty();
    }
  }

  @Async
  public void store(int z, int x, int y, TileResult result) {
    try {
      long freeBytes = Files.getFileStore(cacheDir.getRoot()).getUsableSpace();
      if (freeBytes < xyzConfig.getMinFreeDiskBytes()) {
        LOGGER.warn(
            "Free disk space ({} MB) below minimum; vector tile {}/{}/{} not cached.",
            freeBytes / (1024 * 1024),
            z,
            x,
            y);
        return;
      }
    } catch (IOException e) {
      LOGGER.warn("Could not check disk space — proceeding with vector tile cache write.", e);
    }

    Path path = cachePath(z, x, y);
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, result.data());
      LOGGER.debug("Cached remote vector tile {}/{}/{}", z, x, y);
    } catch (IOException e) {
      LOGGER.debug("Failed to cache vector tile {}/{}/{}: {}", z, x, y, e.getMessage());
    }
  }

  public Path cachePath(int z, int x, int y) {
    return cacheDir.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".pbf");
  }

  private boolean isGzip(byte[] data) {
    return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
  }
}
