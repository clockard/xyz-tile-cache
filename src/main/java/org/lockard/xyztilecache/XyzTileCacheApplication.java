package org.lockard.xyztilecache;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
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
        configuration.getLayers().keySet().stream().collect(Collectors.joining(",")));
    initializeBoundingBoxes();
  }

  public void initializeBoundingBoxes() {
    if (configuration.getBoundingBoxes().isEmpty()) {
      return;
    }
    LOGGER.info("Initializing bounding boxes...");
    ExecutorService executorService =
        Executors.newFixedThreadPool(configuration.getBoundingBoxes().size());
    for (BoundingBox bbox : configuration.getBoundingBoxes()) {
      executorService.submit(() -> initBoundingBox(bbox));
    }
    // all submitted tasks will complete before the executor will actually shutdown
    executorService.shutdown();
  }

  public void initBoundingBox(BoundingBox bbox) {
    List<Set<Point>> allPoints = XyzUtil.calculateAllBboxTiles(bbox);
    for (Layer layer : configuration.getLayers().values()) {
      for (int i = 0; i < allPoints.size(); i++) {
        Set<Point> points = allPoints.get(i);
        for (Point p : points) {
          try {
            this.requestTileZYX(layer.getName(), p.x, p.y, i);
          } catch (Exception e) {
            LOGGER.error("Error pre-loading bounding box tiles for {}", layer.getName(), e);
          }
        }
      }
    }
  }
}
