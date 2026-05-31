package org.lockard.xyztilecache.cache;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@DependsOn("layerStore")
public class TileWriter {
  private static final Logger LOGGER = LoggerFactory.getLogger(TileWriter.class);

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;

  public TileWriter(final XyzConfiguration configuration, final LayerStore layerStore) {
    this.configuration = configuration;
    this.layerStore = layerStore;
  }

  @PostConstruct
  void inventoryExistingTiles() {
    layerStore
        .getLayers()
        .values()
        .forEach(
            layer -> {
              Path tileDir =
                  Paths.get(configuration.getBaseTileDirectory(), layer.getEffectiveId());
              try {
                Files.createDirectories(tileDir);
                try (var paths = Files.walk(tileDir)) {
                  paths
                      .filter(Files::isRegularFile)
                      .forEach(
                          f ->
                              layerStore
                                  .getRuntimeState(layer.getEffectiveId())
                                  .addTileStats(f.toFile().length()));
                }
              } catch (IOException e) {
                LOGGER.error(
                    "Failed to inventory tile directory for {}.", layer.getEffectiveId(), e);
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

    Path output = toPath(tile);
    try {
      Files.createDirectories(output.getParent());
      Files.write(output, data);
      layerStore.getRuntimeState(tile.layer().getEffectiveId()).addTileStats(data.length);
      LOGGER.debug("Wrote tile {} to {}.", tile, output);
    } catch (IOException e) {
      LOGGER.debug("Failed to write tile {} to {}.", tile, output, e);
    }
  }

  /**
   * Removes the on-disk tile directory for a layer that has been removed from the config or whose
   * source has changed (which makes previously-cached tiles stale).
   */
  @EventListener
  void onLayerChanged(LayerChangedEvent event) {
    if (event.kind() != LayerChangedEvent.Kind.REMOVED
        && event.kind() != LayerChangedEvent.Kind.UPDATED_SOURCE) {
      return;
    }
    Path layerDir = Paths.get(configuration.getBaseTileDirectory(), event.layerName());
    if (Files.exists(layerDir)) {
      try (var paths = Files.walk(layerDir)) {
        paths
            .sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    LOGGER.warn("Failed to delete {}", p, e);
                  }
                });
      } catch (IOException e) {
        LOGGER.warn("Failed to delete layer tile dir for {}", event.layerName(), e);
      }
    }
    if (event.kind() == LayerChangedEvent.Kind.UPDATED_SOURCE) {
      var state = layerStore.getRuntimeState(event.layerName());
      state.setCachedTiles(0);
      state.setCachedTilesSize(0);
    }
  }

  protected Path toPath(final Tile tile) {
    return Paths.get(
        configuration.getBaseTileDirectory(),
        tile.layer().getEffectiveId(),
        String.valueOf(tile.z()),
        String.valueOf(tile.x()),
        tile.y() + ".png");
  }
}
