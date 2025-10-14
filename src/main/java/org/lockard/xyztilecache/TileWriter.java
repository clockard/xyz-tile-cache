package org.lockard.xyztilecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class TileWriter {
  private static final Logger LOGGER = LoggerFactory.getLogger(TileWriter.class);

  private final XyzConfiguration configuration;

  private final AtomicLong totalStorageBytes = new AtomicLong();

  private final AtomicLong totalTiles = new AtomicLong();

  public TileWriter(final XyzConfiguration configuration) {
    this.configuration = configuration;
    initializeTotalStorageUsed();
    LOGGER.info("Total tile storage used: {} bytes", totalStorageBytes);
    LOGGER.info("Total number of tiles: {}", totalTiles);
  }

  /**
   * Traverse the base directory and add up all the tiles to get the current total tiles used
   * storage. This could take a while.
   */
  private void initializeTotalStorageUsed() {
    configuration
        .getLayers()
        .values()
        .forEach(
            layer -> {
              try (final var paths =
                  Files.walk(Paths.get(configuration.getBaseTileDirectory(), layer.getName()))) {
                paths
                    .filter(Files::isRegularFile)
                    .forEach(
                        f -> {
                          final var size = f.toFile().length();
                          totalStorageBytes.addAndGet(size);
                          totalTiles.incrementAndGet();
                          layer.addTileStats(size);
                        });
              } catch (IOException e) {
                LOGGER.error("Failed to calculate the tile store size for {}.", layer.getName(), e);
              }
            });
  }

  @Async
  void storeTile(final Tile tile, final byte[] data) {
    if (totalStorageBytes.get() + data.length > configuration.getMaxTileStorage()) {
      LOGGER.warn(
          "Total tile storage of {} MB filled. No more tiles will be stored.",
          totalStorageBytes.get() / (1024 * 1024));
      return;
    }
    final var output = toPath(tile);
    final var parent = output.toFile().getParentFile();
    if (!parent.exists() && !parent.mkdirs()) {
      LOGGER.warn("Failed to create parent directory: {}", parent);
      return;
    }
    LOGGER.debug("Writing tile {} to local file cache: {}.", tile, output);
    try {
      Files.write(output, data);
      totalStorageBytes.addAndGet(data.length);
      totalTiles.incrementAndGet();
      tile.layer().addTileStats(data.length);
    } catch (IOException e) {
      LOGGER.debug("Failed to write tile {} to {}.", tile, output, e);
    }
  }

  protected Path toPath(final Tile tile) {
    return Paths.get(
        configuration.getBaseTileDirectory(),
        tile.layer().getName(),
        String.valueOf(tile.z()),
        String.valueOf(tile.x()),
        tile.y() + ".png");
  }
}
