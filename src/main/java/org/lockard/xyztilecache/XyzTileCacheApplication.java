package org.lockard.xyztilecache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  static LoadingCache<Tile, byte[]> tileCache(CacheLoader<Tile, byte[]> cacheLoader) {
    return CacheBuilder.newBuilder().maximumSize(500).build(cacheLoader);
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
    tileCache.asMap().keySet().removeIf(t -> t.layer().getEffectiveId().equals(event.layerName()));
  }

  // ── Bounding-box preload ──────────────────────────────────────────────────

  void initializeBoundingBoxes() {
    if (configuration.getBoundingBoxes().isEmpty()) {
      return;
    }
    LOGGER.info("Initializing bounding boxes...");
    int taskCount = configuration.getBoundingBoxes().size() * layerStore.getLayers().size();
    int poolSize = Math.max(1, Math.min(taskCount, Runtime.getRuntime().availableProcessors()));
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    for (BoundingBox bbox : configuration.getBoundingBoxes()) {
      for (String layer : layerStore.getLayers().keySet()) {
        pool.submit(() -> preloadService.preloadXyzTiles(Collections.singleton(layer), bbox));
      }
    }
    pool.shutdown();
  }

  // ── Per-layer init downloads ──────────────────────────────────────────────

  void initializeLayerDownloads() {
    List<Layer> initLayers =
        layerStore.getLayers().values().stream().filter(l -> l.getInitZoom() > 0).toList();
    if (initLayers.isEmpty()) {
      return;
    }
    LOGGER.info("Starting init downloads for {} layer(s)...", initLayers.size());
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.max(1, Math.min(initLayers.size(), 4)));
    for (Layer layer : initLayers) {
      if (layer.getSourceType() == Layer.SourceType.VECTOR_PMTILES) {
        pool.submit(() -> initVectorLayerDownload(layer));
      } else {
        BoundingBox world = worldBbox(layer.getInitZoom());
        pool.submit(
            () ->
                preloadService.preloadXyzTiles(
                    Collections.singleton(layer.getEffectiveId()), world));
      }
    }
    pool.shutdown();
  }

  private void initVectorLayerDownload(Layer layer) {
    String url = layer.getUrlTemplate();
    if (url == null || url.isBlank()) {
      LOGGER.warn(
          "Layer '{}' has initZoom > 0 but no urlTemplate; skipping init download",
          layer.getEffectiveId());
      return;
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      LOGGER.info(
          "Layer '{}' has local urlTemplate; skipping init download (file already present)",
          layer.getEffectiveId());
      return;
    }
    int zoom = layer.getInitZoom();
    String layerId = layer.getEffectiveId();
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
