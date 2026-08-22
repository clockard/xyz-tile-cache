package org.lockard.xyztilecache.store;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds {@link TileInventoryStore} totals by walking the tile directory.
 *
 * <p>This is the slow path and runs only when the persisted totals cannot be trusted — the first
 * start after upgrading, after an unclean shutdown, or on an explicit request. A normal restart
 * loads the totals from {@code tile-inventory.json} and never touches the tile files. Scans always
 * run on a background thread, so a cache with millions of tiles never delays startup or an HTTP
 * response.
 */
@Component
public class TileInventoryScanner {

  private static final Logger LOGGER = LoggerFactory.getLogger(TileInventoryScanner.class);

  private final XyzConfiguration configuration;
  private final TileInventoryStore inventory;
  private final AtomicBoolean scanning = new AtomicBoolean();

  public TileInventoryScanner(XyzConfiguration configuration, TileInventoryStore inventory) {
    this.configuration = configuration;
    this.inventory = inventory;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void scanIfInventoryIsUntrusted() {
    if (!inventory.needsBootstrapScan()) {
      LOGGER.info("Tile inventory loaded from disk; no scan needed.");
      return;
    }
    requestScan("no usable inventory at startup");
  }

  /**
   * Starts a scan unless one is already running.
   *
   * @return false if a scan was already in progress, which is left to finish rather than restarted
   */
  public boolean requestScan(String reason) {
    if (!scanning.compareAndSet(false, true)) {
      LOGGER.info("Tile inventory scan already in progress; ignoring request ({}).", reason);
      return false;
    }
    Thread.ofVirtual()
        .name("tile-inventory-scan")
        .start(
            () -> {
              try {
                scanAll(reason);
              } finally {
                scanning.set(false);
              }
            });
    return true;
  }

  /** True while a scan is running; the totals are still readable and are being corrected. */
  public boolean isScanning() {
    return scanning.get();
  }

  private void scanAll(String reason) {
    Path baseDir = Path.of(configuration.getBaseTileDirectory());
    LOGGER.info("Scanning tile directory {} to rebuild the inventory ({}).", baseDir, reason);
    long startedAt = System.currentTimeMillis();

    List<Path> layerDirs;
    try (var entries = Files.list(baseDir)) {
      // Enumerate directories rather than configured layers: a layer added after startup, or one
      // whose files arrived by import, has a directory here either way.
      layerDirs =
          entries
              .filter(Files::isDirectory)
              .filter(p -> LayerStore.SAFE_LAYER_ID.matcher(p.getFileName().toString()).matches())
              .toList();
    } catch (IOException e) {
      LOGGER.error("Could not list the tile directory {}; inventory not rebuilt.", baseDir, e);
      return;
    }

    long totalTiles = 0;
    for (Path layerDir : layerDirs) {
      totalTiles += scanLayer(layerDir);
    }

    try {
      inventory.flush();
    } catch (IOException e) {
      LOGGER.error("Rebuilt the inventory but could not persist it.", e);
    }
    LOGGER.info(
        "Tile inventory scan finished: {} layer(s), {} tile(s), {} ms.",
        layerDirs.size(),
        totalTiles,
        System.currentTimeMillis() - startedAt);
  }

  /** Walks one layer directory and replaces its totals. Returns the tile count it found. */
  private long scanLayer(Path layerDir) {
    String layerId = layerDir.getFileName().toString();
    TileInventoryStore.ScanHandle handle = inventory.beginScan(layerId);
    Totals totals = new Totals();
    try {
      Files.walkFileTree(
          layerDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (attrs.isRegularFile()) {
                // Size comes from the attributes the walk already read, so each file is stat'ed
                // once rather than once to list and again to measure.
                totals.bytes += attrs.size();
                if (!file.getFileName().toString().endsWith(".pmtiles")) {
                  // A pmtiles archive holds many tiles in one file: it counts toward bytes only,
                  // matching how the write paths report it.
                  totals.tiles++;
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              LOGGER.debug("Skipping unreadable file {} during inventory scan.", file);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      LOGGER.warn("Could not scan layer directory {}; leaving its totals unchanged.", layerDir, e);
      return 0;
    }
    inventory.completeScan(handle, totals.tiles, totals.bytes);
    return totals.tiles;
  }

  private static final class Totals {
    private long tiles;
    private long bytes;
  }
}
