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
import org.lockard.xyztilecache.store.TileInventoryStore;
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
  private final TileInventoryStore inventory;

  public TileWriter(
      final XyzConfiguration configuration,
      final LayerStore layerStore,
      final TileInventoryStore inventory) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.inventory = inventory;
  }

  /**
   * Creates each configured layer's tile directory. This used to also walk every file to count it;
   * the totals now come from {@link TileInventoryStore}, which is maintained as tiles are written
   * and persisted across restarts, so startup no longer costs a pass over the whole cache.
   */
  @PostConstruct
  void createLayerDirectories() {
    layerStore
        .getLayers()
        .values()
        .forEach(
            layer -> {
              Path tileDir = Paths.get(configuration.getBaseTileDirectory(), layer.effectiveId());
              try {
                Files.createDirectories(tileDir);
              } catch (IOException e) {
                LOGGER.error("Failed to create tile directory for {}.", layer.effectiveId(), e);
              }
            });
  }

  @Async
  void storeTile(final Tile tile, final byte[] data) {
    var layer = layerStore.getLayers().get(tile.layerId());
    if (layer == null) {
      LOGGER.debug("Layer {} no longer configured; not persisting tile {}.", tile.layerId(), tile);
      return;
    }
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
      // Refreshing an expired tile overwrites a file that is already counted. Recording it as a
      // second tile would inflate the count on every refresh, which for a layer with a short
      // tileExpirationMinutes (weather radar) grows without bound.
      long previousSize = TileInventoryStore.sizeOrMissing(output);
      Files.write(output, data);
      inventory.recordWrite(
          tile.layerId(), previousSize < 0 ? 1 : 0, data.length - Math.max(previousSize, 0));
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
      inventory.recordReset(event.layerName());
    } else {
      inventory.recordRemoved(event.layerName());
    }
  }

  protected Path toPath(final Tile tile) {
    var layer = layerStore.getLayers().get(tile.layerId());
    String ext = layer != null ? layer.tileFileExtension() : "png";
    return Paths.get(
        configuration.getBaseTileDirectory(),
        tile.layerId(),
        String.valueOf(tile.z()),
        String.valueOf(tile.x()),
        tile.y() + "." + ext);
  }
}
