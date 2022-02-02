package org.lockard.xyztilecache;

import java.awt.Point;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

  public static void main(String[] args) throws Exception {

    SpringApplication app = new SpringApplication(XyzTileCacheApplication.class);
    app.run(args);
  }

  @GetMapping(value = "/tilesZYX/{layer}/{z}/{y}/{x}.png")
  public ResponseEntity<byte[]> requestTileZYX(
      @PathVariable("layer") String layerStr,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    Layer layer = configuration.getLayers().get(layerStr);
    if (layer == null) {
      new ResponseEntity("Layer " + layerStr + " not configured", HttpStatus.BAD_REQUEST);
    }
    byte[] tileData = tileDirService.getCachedTile(layer, x, y, z);

    if (tileData == null) {
      tileData = getTileFromSource(layer, x, y, z);
      if (tileData != null) {
        tileDirService.addTitle(tileData, layer, x, y, z);
      }
    }
    if (tileData == null) {
      return new ResponseEntity(
          "Couldn't retrieve tile data for layer " + layerStr, HttpStatus.NOT_FOUND);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(tileData, headers, HttpStatus.OK);
  }

  public byte[] getTileFromSource(Layer layer, int x, int y, int z) {
    String urlBase = layer.getUrlTemplate();
    RestTemplate template = new RestTemplate();
    String url = urlBase.replace("{x}", x + "").replace("{y}", y + "").replace("{z}", z + "");
    return template.getForObject(url, byte[].class);
  }

  @EventListener(ApplicationReadyEvent.class)
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
