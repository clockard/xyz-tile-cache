package org.lockard.xyztilecache.pmtiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.TileResult;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VectorTileCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTileCache.class);

  private final Path cacheDir;
  private final XyzConfiguration xyzConfig;
  private final String layerId;
  private final TileInventoryStore inventory;

  public VectorTileCache(
      Path cacheDir, XyzConfiguration xyzConfig, String layerId, TileInventoryStore inventory) {
    this.cacheDir = cacheDir;
    this.xyzConfig = xyzConfig;
    this.layerId = layerId;
    this.inventory = inventory;
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
      return Optional.of(new TileResult(data, compression, "application/x-protobuf"));
    } catch (IOException e) {
      LOGGER.debug("Failed to read cached vector tile {}/{}/{}: {}", z, x, y, e.getMessage());
      return Optional.empty();
    }
  }

  public void store(int z, int x, int y, TileResult result) {
    try {
      long freeBytes = Files.getFileStore(existingAncestor(cacheDir)).getUsableSpace();
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
      long previous = TileInventoryStore.sizeOrMissing(path);
      Files.write(path, result.data());
      inventory.recordWrite(
          layerId, previous < 0 ? 1 : 0, result.data().length - Math.max(previous, 0));
      LOGGER.debug("Cached remote vector tile {}/{}/{}", z, x, y);
    } catch (IOException e) {
      LOGGER.debug("Failed to cache vector tile {}/{}/{}: {}", z, x, y, e.getMessage());
    }
  }

  public Path cachePath(int z, int x, int y) {
    return cacheDir.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".pbf");
  }

  private static Path existingAncestor(Path path) {
    Path p = path.toAbsolutePath();
    while (p != null && !Files.exists(p)) {
      p = p.getParent();
    }
    return p != null ? p : path.getRoot();
  }

  private boolean isGzip(byte[] data) {
    return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
  }
}
