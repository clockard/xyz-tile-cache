package org.lockard.xyztilecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class VectorTileRemoteCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTileRemoteCache.class);

  private final VectorConfiguration vectorConfig;
  private final XyzConfiguration xyzConfig;

  public VectorTileRemoteCache(VectorConfiguration vectorConfig, XyzConfiguration xyzConfig) {
    this.vectorConfig = vectorConfig;
    this.xyzConfig = xyzConfig;
  }

  Optional<TileResult> get(int z, int x, int y) {
    if (!isConfigured()) {
      return Optional.empty();
    }
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
  void store(int z, int x, int y, TileResult result) {
    if (!isConfigured()) {
      return;
    }
    try {
      long freeBytes =
          Files.getFileStore(Path.of(vectorConfig.getDownloadDirectory())).getUsableSpace();
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

  Path cachePath(int z, int x, int y) {
    return Path.of(
        vectorConfig.getDownloadDirectory(),
        "remote-cache",
        String.valueOf(z),
        String.valueOf(x),
        y + ".pbf");
  }

  private boolean isConfigured() {
    String dir = vectorConfig.getDownloadDirectory();
    return dir != null && !dir.isBlank();
  }

  private boolean isGzip(byte[] data) {
    return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
  }
}
