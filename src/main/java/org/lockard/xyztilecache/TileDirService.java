package org.lockard.xyztilecache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.collections4.map.LRUMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TileDirService {
  private static final Logger LOGGER = LoggerFactory.getLogger(TileDirService.class);

  private Map<String, byte[]> tileMemoryCache = Collections.synchronizedMap(new LRUMap<>(500, 500));

  private XyzConfiguration configuration;
  private File baseFile;
  private long totalStorageBytes = 0;
  private long totalTiles = 0;

  public TileDirService(XyzConfiguration configuration) {
    this.configuration = configuration;
    this.baseFile = new File(configuration.getBaseTileDirectory());
    initializeTotalStorageUsed();
    LOGGER.info("Total tiles storage used: {}", totalStorageBytes);
    LOGGER.info("Total number of tiles: {}", totalTiles);
  }

  /**
   * Traverse the base directory and add up all the tiles to get the current total tiles used
   * storage. This could take a while.
   */
  public void initializeTotalStorageUsed() {
    configuration
        .getLayers()
        .values()
        .forEach(
            (layer) -> {
              try {
                Files.walk(Paths.get(new File(baseFile, layer.getName()).toURI()))
                    .filter(Files::isRegularFile)
                    .forEach(
                        (f) -> {
                          totalStorageBytes += f.toFile().length();
                          totalTiles++;
                          layer.addTileStats(f.toFile().length());
                        });
              } catch (IOException e) {
                LOGGER.error("Failed to calculate the tile store size.", e);
              }
            });
  }

  public boolean addTitle(byte[] data, Layer layer, int x, int y, int z) {
    if (totalStorageBytes + data.length > configuration.getMaxTileStorage()) {
      LOGGER.warn(
          "Total tile storage of {}MB filled. No more tiles will be stored",
          totalStorageBytes / (1024 * 1024));
      return false;
    }
    File dir = new File(baseFile, layer.getName() + "/" + z + "/" + x);
    dir.mkdirs();
    File tileFile = new File(dir, y + ".png");
    String tileName = layer.getName() + "/" + z + "/" + x + "/" + y + ".png";
    tileMemoryCache.put(tileName, data);
    try (FileOutputStream outputStream = new FileOutputStream(tileFile)) {
      outputStream.write(data);
      outputStream.flush();
      totalStorageBytes += data.length;
      totalTiles++;
      return true;
    } catch (IOException e) {
      LOGGER.error("Failed to save tile to storage at {}", dir.getAbsolutePath(), e);
      return false;
    }
  }

  public byte[] getCachedTile(Layer layer, int x, int y, int z) {
    String tileName = layer.getName() + "/" + z + "/" + x + "/" + y + ".png";
    File tile = new File(baseFile, tileName);
    if (tileMemoryCache.containsKey(tileName)) {
      return tileMemoryCache.get(tileName);
    }
    if (!tile.exists()) {
      return null;
    }
    if (layer.getExpiration() > 0
        && System.currentTimeMillis()
                - (tile.lastModified() + TimeUnit.MINUTES.toMillis(layer.getExpiration()))
            < 0) {
      return null;
    }
    try {
      tileMemoryCache.put(tileName, Files.readAllBytes(tile.toPath()));
      return tileMemoryCache.get(tileName);
    } catch (IOException e) {
      LOGGER.error("Error reading tile data: " + e);
    }
    return null;
  }

  public File getBaseFile() {
    return baseFile;
  }

  public long getTotalTiles() {
    return totalTiles;
  }

  public long getMaxTileStorage() {
    return configuration.getMaxTileStorage();
  }

  public long getTotalTileStorageUsed() {
    return totalStorageBytes;
  }
}
