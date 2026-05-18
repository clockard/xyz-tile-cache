package org.lockard.xyztilecache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lockard.xyztilecache.config.VectorConfiguration;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.Tile;
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
@EnableConfigurationProperties({XyzConfiguration.class, VectorConfiguration.class})
@EnableAsync
@EnableScheduling
public class XyzTileCacheApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(XyzTileCacheApplication.class);

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final PreloadService preloadService;

  public XyzTileCacheApplication(
      final XyzConfiguration configuration,
      final LayerStore layerStore,
      final LoadingCache<Tile, byte[]> tileCache,
      final PreloadService preloadService) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.tileCache = tileCache;
    this.preloadService = preloadService;
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
  }

  @EventListener
  void onLayerChanged(LayerChangedEvent event) {
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
}
