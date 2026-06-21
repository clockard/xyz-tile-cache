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
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatsController.class);
  private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;

  StatsController(XyzConfiguration configuration, LayerStore layerStore) {
    this.configuration = configuration;
    this.layerStore = layerStore;
  }

  @GetMapping("/stats")
  ResponseEntity<StatsResponse> get() {
    Collection<Layer> layers = layerStore.getLayers().values();
    List<StatsResponse.LayerStats> layerStats =
        layers.stream()
            .map(
                l ->
                    new StatsResponse.LayerStats(
                        l.effectiveId(),
                        layerStore.getRuntimeState(l.effectiveId()).getTilesServed()))
            .toList();
    long totalServed =
        layers.stream()
            .mapToLong(l -> layerStore.getRuntimeState(l.effectiveId()).getTilesServed())
            .sum();

    long diskFreeBytes = 0;
    try {
      diskFreeBytes =
          Files.getFileStore(Paths.get(configuration.getBaseTileDirectory())).getUsableSpace();
    } catch (IOException e) {
      LOGGER.warn("Could not determine disk free space for stats.", e);
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(
        new StatsResponse(INSTANCE_ID, totalServed, diskFreeBytes, layerStats),
        headers,
        HttpStatus.OK);
  }
}
