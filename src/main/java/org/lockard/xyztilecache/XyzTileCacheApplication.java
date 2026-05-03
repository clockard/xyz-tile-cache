package org.lockard.xyztilecache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
@EnableConfigurationProperties({XyzConfiguration.class, VectorConfiguration.class})
@EnableAsync
@EnableScheduling
public class XyzTileCacheApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(XyzTileCacheApplication.class);

  private final XyzConfiguration configuration;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final PreloadService preloadService;
  private final LayerAccessService layerAccessService;

  public XyzTileCacheApplication(
      final XyzConfiguration configuration,
      final LoadingCache<Tile, byte[]> tileCache,
      final PreloadService preloadService,
      final LayerAccessService layerAccessService) {
    this.configuration = configuration;
    this.tileCache = tileCache;
    this.preloadService = preloadService;
    this.layerAccessService = layerAccessService;
  }

  public static void main(String[] args) {
    SpringApplication.run(XyzTileCacheApplication.class, args);
  }

  @Bean
  static LoadingCache<Tile, byte[]> tileCache(CacheLoader<Tile, byte[]> cacheLoader) {
    return CacheBuilder.newBuilder().maximumSize(500).build(cacheLoader);
  }

  // ── Tile endpoints ────────────────────────────────────────────────────────

  @GetMapping("/tilesZYX/{layer}/{z}/{y}/{x}.png")
  public ResponseEntity<byte[]> requestTileZYX(
      @PathVariable("layer") String layerName,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    return serveTile(layerName, x, y, z);
  }

  @GetMapping("/tilesZXY/{layer}/{z}/{x}/{y}.png")
  public ResponseEntity<byte[]> requestTileZXY(
      @PathVariable("layer") String layerName,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    return serveTile(layerName, x, y, z);
  }

  private ResponseEntity<byte[]> serveTile(String layerName, int x, int y, int z) {
    Layer layer = configuration.getLayers().get(layerName);
    if (layer == null) {
      return ResponseEntity.badRequest()
          .body(("Layer " + layerName + " not configured").getBytes());
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!layerAccessService.canRead(layer, auth)) {
      if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
      }
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    if (z > layer.getMaxZoom()) {
      LOGGER.debug("Zoom {} exceeds maxZoom {} for layer {}", z, layer.getMaxZoom(), layerName);
      return ResponseEntity.notFound().build();
    }

    Tile tile = new Tile(layer, x, y, z);
    byte[] data;
    try {
      data = tileCache.get(tile);
    } catch (ExecutionException e) {
      LOGGER.debug("Failed to retrieve tile {}.", tile, e.getCause());
      return ResponseEntity.notFound().build();
    }

    layer.incrementTilesServed();
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", "image/png");
    return new ResponseEntity<>(data, headers, HttpStatus.OK);
  }

  // ── Legacy preload endpoint ───────────────────────────────────────────────

  @PostMapping(value = "/preload", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> preload(@RequestBody PreloadRequest preloadRequest) {
    if (preloadRequest.getBoundingBox() == null) {
      return ResponseEntity.badRequest().body("boundingBox is required");
    }
    try {
      preloadService.submit(
          null,
          preloadRequest.getBoundingBox(),
          preloadRequest.getBoundingBox().getMaxZoom(),
          preloadRequest.getLayers(),
          false,
          null,
          null);
    } catch (java.io.IOException e) {
      LOGGER.error("Failed to persist preload", e);
      return ResponseEntity.internalServerError().body("Failed to persist preload.");
    } catch (IllegalArgumentException | IllegalStateException e) {
      // No vector for this endpoint, so these shouldn't happen — but return safely.
      return ResponseEntity.badRequest().body(e.getMessage());
    }
    return ResponseEntity.ok().build();
  }

  // ── Lifecycle / events ────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    if (configuration.getLayers().isEmpty()) {
      LOGGER.warn("No layers are configured. No tiles will be returned");
      return;
    }
    LOGGER.info(
        "The following layers are configured: {}",
        String.join(",", configuration.getLayers().keySet()));
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
    ExecutorService pool = Executors.newFixedThreadPool(configuration.getBoundingBoxes().size());
    for (BoundingBox bbox : configuration.getBoundingBoxes()) {
      for (String layer : configuration.getLayers().keySet()) {
        pool.submit(() -> preloadService.preloadXyzTiles(Collections.singleton(layer), bbox));
      }
    }
    pool.shutdown();
  }
}
