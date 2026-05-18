package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.lockard.xyztilecache.model.PmtilesDownloadRequest;
import org.lockard.xyztilecache.model.TileResult;
import org.lockard.xyztilecache.service.PreloadService;
import org.lockard.xyztilecache.service.VectorTileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VectorTileController {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTileController.class);
  private static final int COMPRESSION_GZIP = 2;

  private final VectorTileService vectorTileService;
  private final PreloadService preloadService;

  public VectorTileController(VectorTileService vectorTileService, PreloadService preloadService) {
    this.vectorTileService = vectorTileService;
    this.preloadService = preloadService;
  }

  @GetMapping("/vector/{z}/{x}/{y}")
  public ResponseEntity<byte[]> getVectorTile(
      @PathVariable int z, @PathVariable int x, @PathVariable int y) {
    Optional<TileResult> result;
    try {
      result = vectorTileService.getTile(z, x, y);
    } catch (IOException e) {
      LOGGER.warn("Error reading vector tile {}/{}/{}: {}", z, x, y, e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    if (result.isEmpty()) {
      return ResponseEntity.noContent().build();
    }

    TileResult tile = result.get();
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", "application/x-protobuf");
    if (tile.tileCompression() == COMPRESSION_GZIP) {
      headers.add("Content-Encoding", "gzip");
    }
    return new ResponseEntity<>(tile.data(), headers, HttpStatus.OK);
  }

  @PostMapping(value = "/vector/preload", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> preload(@RequestBody PmtilesDownloadRequest request) {
    if (request.getBoundingBox() == null) {
      return ResponseEntity.badRequest().body("boundingBox is required");
    }
    try {
      preloadService.submit(
          request.getName(),
          request.getBoundingBox(),
          request.getMaxZoom(),
          Set.of(),
          true,
          null,
          null);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist preload", e);
      return ResponseEntity.internalServerError().body("Failed to persist preload.");
    }
    return ResponseEntity.accepted().build();
  }
}
