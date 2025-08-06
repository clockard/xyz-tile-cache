package org.lockard.xyztilecache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
@EnableConfigurationProperties({
  XyzConfiguration.class,
})
@EnableAsync
public class XyzTileCacheApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(XyzTileCacheApplication.class);

  private final XyzConfiguration configuration;

  private final LoadingCache<Tile, byte[]> tileCache;

  private final ExecutorService executorService = Executors.newSingleThreadExecutor();

  private Future<?> preloadFuture;

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(XyzTileCacheApplication.class);
    app.run(args);
  }

  public XyzTileCacheApplication(
      final XyzConfiguration configuration, final CacheLoader<Tile, byte[]> cacheLoader) {
    this.configuration = configuration;
    tileCache = CacheBuilder.newBuilder().maximumSize(500).build(cacheLoader);
  }

  @GetMapping(value = "/layers")
  public ResponseEntity<Collection<Layer>> getLayers() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(configuration.getLayers().values(), headers, HttpStatus.OK);
  }

  @GetMapping(value = "/tilesZYX/{layer}/{z}/{y}/{x}.png")
  public ResponseEntity<byte[]> requestTileZYX(
      @PathVariable("layer") String layerStr,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    Layer layer = configuration.getLayers().get(layerStr);
    if (layer == null) {
      return new ResponseEntity("Layer " + layerStr + " not configured", HttpStatus.BAD_REQUEST);
    }

    Tile tile = new Tile(layer, x, y, z);
    byte[] tileData;
    try {
      tileData = tileCache.get(tile);
    } catch (ExecutionException e) {
      LOGGER.info("Failed to retrieve tile {}.", tile);
      LOGGER.debug("Failed to retrieve tile {}.", tile, e.getCause());
      return new ResponseEntity(
          "Couldn't retrieve tile data for layer " + layerStr, HttpStatus.NOT_FOUND);
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", "image/png");
    return new ResponseEntity<>(tileData, headers, HttpStatus.OK);
  }

  @GetMapping(value = "/tilesZXY/{layer}/{z}/{x}/{y}.png")
  public ResponseEntity<byte[]> requestTileZXY(
      @PathVariable("layer") String layerStr,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    return requestTileZYX(layerStr, x, y, z);
  }

  @PostMapping(value = "/preload", consumes = MediaType.APPLICATION_JSON_VALUE)
  public void preLoadBoundingBox(@RequestBody PreloadRequest preloadRequest) {
    LOGGER.debug("Request: {}", preloadRequest);
    Set<String> filteredLayers =
        preloadRequest.getLayers().stream()
            .filter(layer -> configuration.getLayers().containsKey(layer))
            .collect(Collectors.toSet());
    if (filteredLayers.isEmpty() || (preloadFuture != null && !preloadFuture.isDone())) {
      return;
    }
    LOGGER.debug("Preloading bounding box for layers {}", filteredLayers);
    preloadFuture = executorService.submit(() -> initBoundingBox(preloadRequest));
  }

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

  public void initializeBoundingBoxes() {
    if (configuration.getBoundingBoxes().isEmpty()) {
      return;
    }
    LOGGER.info("Initializing bounding boxes...");
    ExecutorService executorPool =
        Executors.newFixedThreadPool(configuration.getBoundingBoxes().size());
    Set<String> layers = configuration.getLayers().keySet();
    for (BoundingBox bbox : configuration.getBoundingBoxes()) {
      for (String layer : layers) {
        executorPool.submit(
            () -> initBoundingBox(new PreloadRequest(Collections.singleton(layer), bbox)));
      }
    }
    // all submitted tasks will complete before the executor will actually shutdown
    executorPool.shutdown();
  }

  public void initBoundingBox(PreloadRequest request) {
    List<Set<Point>> allPoints = XyzUtil.calculateAllBboxTiles(request.getBoundingBox());
    for (String layerName : request.getLayers()) {
      Layer layer = configuration.getLayers().get(layerName);
      if (layer == null) {
        continue;
      }
      for (int i = 0; i < allPoints.size(); i++) {
        Set<Point> points = allPoints.get(i);
        for (Point p : points) {
          Tile tile = new Tile(layer, p.x, p.y, i);
          try {
            tileCache.get(tile);
          } catch (ExecutionException e) {
            LOGGER.error("Error pre-loading bounding box tile: {}.", tile, e.getCause());
          }
        }
      }
    }
    LOGGER.info("Finished preload.");
  }
}
