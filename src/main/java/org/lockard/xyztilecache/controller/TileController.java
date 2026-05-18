package org.lockard.xyztilecache.controller;

import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PreloadRequest;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.service.PreloadService;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TileController {

  private static final Logger LOGGER = LoggerFactory.getLogger(TileController.class);

  private final LayerStore layerStore;
  private final LoadingCache<Tile, byte[]> tileCache;
  private final LayerAccessService layerAccessService;
  private final PreloadService preloadService;

  TileController(
      LayerStore layerStore,
      LoadingCache<Tile, byte[]> tileCache,
      LayerAccessService layerAccessService,
      PreloadService preloadService) {
    this.layerStore = layerStore;
    this.tileCache = tileCache;
    this.layerAccessService = layerAccessService;
    this.preloadService = preloadService;
  }

  /** Legacy endpoint — prefer POST /preloads for new integrations. */
  @PostMapping(value = "/preload", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> preloadLegacy(@RequestBody PreloadRequest preloadRequest) {
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
    } catch (IOException e) {
      LOGGER.error("Failed to persist preload", e);
      return ResponseEntity.internalServerError().body("Failed to persist preload.");
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
    return ResponseEntity.ok().build();
  }

  @GetMapping("/tilesZYX/{layer}/{z}/{y}/{x}.png")
  ResponseEntity<byte[]> requestTileZYX(
      @PathVariable("layer") String layerName,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    return serveTile(layerName, x, y, z);
  }

  @GetMapping("/tilesZXY/{layer}/{z}/{x}/{y}.png")
  ResponseEntity<byte[]> requestTileZXY(
      @PathVariable("layer") String layerName,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("z") int z) {
    return serveTile(layerName, x, y, z);
  }

  private ResponseEntity<byte[]> serveTile(String layerName, int x, int y, int z) {
    Layer layer = layerStore.getLayers().get(layerName);
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

    layerStore.getRuntimeState(layer.getEffectiveId()).incrementTilesServed();
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", "image/png");
    return new ResponseEntity<>(data, headers, HttpStatus.OK);
  }
}
