package org.lockard.xyztilecache;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@RestController
@EnableConfigurationProperties({
  XyzConfiguration.class,
})
public class XyzTileCacheApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(XyzTileCacheApplication.class);

  @Autowired private TileDirService tileDirService;

  @Autowired private XyzConfiguration configuration;

  private ExecutorService executorService = Executors.newSingleThreadExecutor();

  private Future preloadFuture;

  public static void main(String[] args) {

    SpringApplication app = new SpringApplication(XyzTileCacheApplication.class);
    app.run(args);
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

    byte[] tileData = tileDirService.getCachedTile(layer, x, y, z);

    if (tileData == null) {
      long start = System.currentTimeMillis();
      tileData = getTileFromSource(layer, x, y, z);
      LOGGER.debug("Tile retrieval time: {}ms", System.currentTimeMillis() - start);
      if (tileData != null) {
        tileDirService.addTitle(tileData, layer, x, y, z);
        layer.setSourceAvailable(true);
      } else {
        layer.setSourceAvailable(false);
      }
    }
    if (tileData == null) {
      return new ResponseEntity(
          "Couldn't retrieve tile data for layer " + layerStr, HttpStatus.NOT_FOUND);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", "image/png");
    return new ResponseEntity<>(tileData, headers, HttpStatus.OK);
  }

  private void cacheTile(String layerStr, int x, int y, int z) {
    Layer layer = configuration.getLayers().get(layerStr);
    if (layer == null) {
      return;
    }
    if (!tileDirService.isTileCached(layer, x, y, z)) {
      long start = System.currentTimeMillis();
      byte[] tileData = getTileFromSource(layer, x, y, z);
      LOGGER.debug("Tile retrieval time: {}ms", System.currentTimeMillis() - start);
      if (tileData != null) {
        tileDirService.addTitle(tileData, layer, x, y, z);
        layer.setSourceAvailable(true);
      } else {
        layer.setSourceAvailable(false);
      }
    }
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

  public byte[] getTileFromSource(Layer layer, int x, int y, int z) {
    if (!layer.isSourceAvailable()
        && System.currentTimeMillis() - layer.getSourceLastChecked()
            < TimeUnit.MINUTES.toMillis(1)) {
      return null;
    }
    String urlBase = layer.getUrlTemplate();
    RestTemplate template = new RestTemplate();
    String url = urlBase.replace("{x}", x + "").replace("{y}", y + "").replace("{z}", z + "");
    LOGGER.debug("Tile url: {}", url);
    layer.setSourceLastChecked(System.currentTimeMillis());
    HttpHeaders headers = new HttpHeaders();
    Map<String, String> layerHeaders = layer.getHeaders();
    for (Map.Entry<String, String> entry : layerHeaders.entrySet()) {
      headers.set(entry.getKey(), entry.getValue());
    }
    // User-Agent needed for some sources to respond properly
    headers.set(
        "User-Agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");
    headers.set("Accept-Encoding", "gzip, deflate, br");
    HttpEntity entity = new HttpEntity(null, headers);

    try {
      return template.exchange(url, HttpMethod.GET, entity, byte[].class).getBody();
    } catch (Exception e) {
      LOGGER.warn("Error contacting tile source for {}", url);
      layer.setSourceAvailable(true);
    }
    return null;
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
      executorPool.submit(() -> initBoundingBox(new PreloadRequest(layers, bbox)));
    }
    // all submitted tasks will complete before the executor will actually shutdown
    executorPool.shutdown();
  }

  public void initBoundingBox(PreloadRequest request) {
    List<Set<Point>> allPoints = XyzUtil.calculateAllBboxTiles(request.getBoundingBox());
    for (String layer : request.getLayers()) {
      for (int i = 0; i < allPoints.size(); i++) {
        Set<Point> points = allPoints.get(i);
        for (Point p : points) {
          try {
            this.cacheTile(layer, p.x, p.y, i);
          } catch (Exception e) {
            LOGGER.error("Error pre-loading bounding box tiles for {}", layer, e);
          }
        }
      }
    }
    LOGGER.debug("Finished preload");
  }
}
