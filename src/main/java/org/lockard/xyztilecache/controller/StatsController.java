package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.StatsResponse;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.TileInventoryScanner;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatsController.class);
  private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final TileInventoryScanner inventoryScanner;
  private final TileInventoryStore inventory;

  StatsController(
      XyzConfiguration configuration,
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      TileInventoryScanner inventoryScanner,
      TileInventoryStore inventory) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.inventoryScanner = inventoryScanner;
    this.inventory = inventory;
  }

  /**
   * Rebuilds the cached-tile totals by walking the tile directory. Admin-only via the catch-all
   * write rule in SecurityConfig. The scan runs in the background and can take minutes on a large
   * cache, so this accepts the request rather than waiting for it.
   */
  @PostMapping("/stats/reconcile")
  ResponseEntity<Void> reconcileInventory() {
    inventoryScanner.requestScan("requested via POST /stats/reconcile");
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/stats")
  ResponseEntity<StatsResponse> get() {
    // Same per-layer read ACL as /layers: don't leak ACL'd layer ids to callers who can't
    // read them.
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Collection<Layer> layers =
        layerStore.getLayers().values().stream()
            .filter(l -> layerAccessService.canRead(l, auth))
            .toList();
    List<StatsResponse.LayerStats> layerStats =
        layers.stream()
            .map(
                l ->
                    new StatsResponse.LayerStats(
                        l.effectiveId(),
                        layerStore.getRuntimeState(l.effectiveId()).getTilesServed(),
                        inventory.tiles(l.effectiveId()),
                        inventory.bytes(l.effectiveId())))
            .toList();
    // Totals are summed from the visible layers so they agree with the array above rather than
    // reporting cache-wide figures a restricted caller cannot account for.
    long totalServed =
        layerStats.stream().mapToLong(StatsResponse.LayerStats::tilesServedByInstance).sum();
    long totalCachedTiles =
        layerStats.stream().mapToLong(StatsResponse.LayerStats::cachedTiles).sum();
    long totalCachedBytes =
        layerStats.stream().mapToLong(StatsResponse.LayerStats::cachedBytes).sum();

    long diskFreeBytes = 0;
    try {
      diskFreeBytes =
          Files.getFileStore(Paths.get(configuration.getBaseTileDirectory())).getUsableSpace();
    } catch (IOException e) {
      LOGGER.warn("Could not determine disk free space for stats.", e);
    }

    HttpHeaders headers = new HttpHeaders();
    return new ResponseEntity<>(
        new StatsResponse(
            INSTANCE_ID,
            totalServed,
            totalCachedTiles,
            totalCachedBytes,
            diskFreeBytes,
            layerStats),
        headers,
        HttpStatus.OK);
  }
}
