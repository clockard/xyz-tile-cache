package org.lockard.xyztilecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@DependsOn("layerStore")
public class TileWriter {
  private static final Logger LOGGER = LoggerFactory.getLogger(TileWriter.class);

  private final XyzConfiguration configuration;

  public TileWriter(final XyzConfiguration configuration) {
    this.configuration = configuration;
    createLayerDirectories();
  }

  private void createLayerDirectories() {
    configuration
        .getLayers()
        .values()
        .forEach(
            layer -> {
              Path tileDir = Paths.get(configuration.getBaseTileDirectory(), layer.getName());
              if (!tileDir.toFile().exists()) {
                tileDir.toFile().mkdir();
              }
              try (final var paths = Files.walk(tileDir)) {
                paths
                    .filter(Files::isRegularFile)
                    .forEach(f -> layer.addTileStats(f.toFile().length()));
              } catch (IOException e) {
                LOGGER.error("Failed to scan tile directory for {}.", layer.getName(), e);
              }
            });
  }

  @Async
  void storeTile(final Tile tile, final byte[] data) {
    try {
      long freeBytes =
          Files.getFileStore(Paths.get(configuration.getBaseTileDirectory())).getUsableSpace();
      if (freeBytes < configuration.getMinFreeDiskBytes()) {
        LOGGER.warn(
            "Free disk space ({} MB) is below minimum ({} MB). Tile will not be stored.",
            freeBytes / (1024 * 1024),
            configuration.getMinFreeDiskBytes() / (1024 * 1024));
        return;
      }
    } catch (IOException e) {
      LOGGER.warn("Could not check disk free space — proceeding with tile write.", e);
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
      tile.layer().addTileStats(data.length);
    } catch (IOException e) {
      LOGGER.debug("Failed to write tile {} to {}.", tile, output, e);
    }
  }

  public void deleteLayerDirectory(final String layerName) {
    Path layerDir = Paths.get(configuration.getBaseTileDirectory(), layerName);
    if (!layerDir.toFile().exists()) {
      return;
    }
    if(!layerDir.toFile().delete()){
      LOGGER.warn("Failed to delete layer tile dir for {}", layerName);
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
