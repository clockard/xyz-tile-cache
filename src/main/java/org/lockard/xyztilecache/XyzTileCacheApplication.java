package org.lockard.xyztilecache;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.service.PmtilesDownloader;
import org.lockard.xyztilecache.service.PreloadService;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(XyzConfiguration.class)
@EnableAsync
@EnableScheduling
public class XyzTileCacheApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(XyzTileCacheApplication.class);

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final PreloadService preloadService;
  private final PmtilesDownloader pmtilesDownloader;

  public XyzTileCacheApplication(
      final XyzConfiguration configuration,
      final LayerStore layerStore,
      final LoadingCache<Tile, byte[]> tileCache,
      final PreloadService preloadService,
      final PmtilesDownloader pmtilesDownloader) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.tileCache = tileCache;
    this.preloadService = preloadService;
    this.pmtilesDownloader = pmtilesDownloader;
  }

  public static void main(String[] args) {
    SpringApplication.run(XyzTileCacheApplication.class, args);
  }

  @Bean
  static LoadingCache<Tile, byte[]> tileCache(
      CacheLoader<Tile, byte[]> cacheLoader,
      XyzConfiguration configuration,
      MeterRegistry meterRegistry) {
    LoadingCache<Tile, byte[]> cache =
        Caffeine.newBuilder()
            .maximumWeight(configuration.getTileCacheBytes())
            .<Tile, byte[]>weigher((k, v) -> v.length)
            .recordStats()
            .build(cacheLoader);
    CaffeineCacheMetrics.monitor(meterRegistry, cache, "xyz_tile_cache");
    return cache;
  }

  // ── Lifecycle / events ────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    if (layerStore.getLayers().isEmpty()) {
      LOGGER.warn("No layers are configured. No tiles will be returned");
      return;
    }
    LOGGER.info(
        "The following layers are configured: {}",
        String.join(",", layerStore.getLayers().keySet()));
    initializeBoundingBoxes();
    initializeLayerDownloads();
  }

  @EventListener
  void onLayerChanged(LayerChangedEvent event) {
    if (event.kind() == LayerChangedEvent.Kind.UPDATED_ACL) {
      return;
    }
    tileCache.asMap().keySet().removeIf(t -> t.layerId().equals(event.layerName()));
  }

  // ── Bounding-box preload ──────────────────────────────────────────────────

  void initializeBoundingBoxes() {
    if (configuration.getBoundingBoxes().isEmpty()) {
      return;
    }
    LOGGER.info("Initializing bounding boxes...");
    for (BoundingBox bbox : configuration.getBoundingBoxes()) {
      for (String layer : layerStore.getLayers().keySet()) {
        Thread.ofVirtual()
            .name("bbox-init-" + layer)
            .start(() -> preloadService.preloadXyzTiles(Collections.singleton(layer), bbox));
      }
    }
  }

  // ── Per-layer init downloads ──────────────────────────────────────────────

  void initializeLayerDownloads() {
    List<Layer> initLayers =
        layerStore.getLayers().values().stream().filter(l -> l.initZoom() > 0).toList();
    if (initLayers.isEmpty()) {
      return;
    }
    LOGGER.info("Starting init downloads for {} layer(s)...", initLayers.size());
    for (Layer layer : initLayers) {
      String layerId = layer.effectiveId();
      if (layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) {
        Thread.ofVirtual().name("init-vec-" + layerId).start(() -> initVectorLayerDownload(layer));
      } else {
        BoundingBox world = worldBbox(layer.initZoom());
        Thread.ofVirtual()
            .name("init-xyz-" + layerId)
            .start(() -> preloadService.preloadXyzTiles(Collections.singleton(layerId), world));
      }
    }
  }

  private void initVectorLayerDownload(Layer layer) {
    String url = layer.urlTemplate();
    if (url == null || url.isBlank()) {
      LOGGER.warn(
          "Layer '{}' has initZoom > 0 but no urlTemplate; skipping init download",
          layer.effectiveId());
      return;
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      LOGGER.info(
          "Layer '{}' has local urlTemplate; skipping init download (file already present)",
          layer.effectiveId());
      return;
    }
    int zoom = layer.initZoom();
    String layerId = layer.effectiveId();
    String preloadName = "init-world-" + layerId + "-z" + zoom;
    Path outputPath =
        Path.of(configuration.getBaseTileDirectory(), layerId)
            .toAbsolutePath()
            .normalize()
            .resolve(PmtilesDownloader.outputFilename(preloadName));
    if (Files.exists(outputPath)) {
      LOGGER.info(
          "Init file already exists for vector layer '{}' at zoom {}; skipping download",
          layerId,
          zoom);
      return;
    }
    Preload preload = new Preload();
    preload.setId(UUID.randomUUID().toString());
    preload.setName(preloadName);
    preload.setBoundingBox(worldBbox(zoom));
    preload.setMaxZoom(zoom);
    try {
      pmtilesDownloader.startDownload(preload, layer);
      LOGGER.info("Started init download for vector layer '{}' up to zoom {}", layerId, zoom);
    } catch (Exception e) {
      LOGGER.error("Failed to start init download for vector layer '{}'", layerId, e);
    }
  }

  private static BoundingBox worldBbox(int maxZoom) {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(-180);
    bbox.setSouth(-85.051129);
    bbox.setEast(180);
    bbox.setNorth(85.051129);
    bbox.setMaxZoom(maxZoom);
    return bbox;
  }
}
