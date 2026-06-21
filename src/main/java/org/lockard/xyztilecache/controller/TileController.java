package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.util.Optional;
import org.lockard.xyztilecache.cache.UpstreamUnavailableException;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.handler.TileNotFoundException;
import org.lockard.xyztilecache.handler.TileSourceHandlerRegistry;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PreloadRequest;
import org.lockard.xyztilecache.model.TileResult;
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
  private static final int COMPRESSION_GZIP = 2;
  private static final long IMMUTABLE_MAX_AGE_SECONDS = 31_536_000L;

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final PreloadService preloadService;
  private final TileSourceHandlerRegistry handlerRegistry;
  private final XyzConfiguration configuration;

  TileController(
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      PreloadService preloadService,
      TileSourceHandlerRegistry handlerRegistry,
      XyzConfiguration configuration) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.preloadService = preloadService;
    this.handlerRegistry = handlerRegistry;
    this.configuration = configuration;
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

  @GetMapping("/tilesZYX/{layer}/{z}/{y}/{x}.{ext}")
  ResponseEntity<byte[]> tilesZYX(
      @PathVariable("layer") String layerName,
      @PathVariable("z") int z,
      @PathVariable("y") int y,
      @PathVariable("x") int x,
      @PathVariable("ext") String ext) {
    return serveTile(layerName, z, x, y);
  }

  @GetMapping("/tilesZXY/{layer}/{z}/{x}/{y}.{ext}")
  ResponseEntity<byte[]> tilesZXY(
      @PathVariable("layer") String layerName,
      @PathVariable("z") int z,
      @PathVariable("x") int x,
      @PathVariable("y") int y,
      @PathVariable("ext") String ext) {
    return tilesZYX(layerName, z, y, x, ext);
  }

  private ResponseEntity<byte[]> serveTile(String layerName, int z, int x, int y) {
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

    if (z > layer.maxZoom()) {
      LOGGER.debug("Zoom {} exceeds maxZoom {} for layer {}", z, layer.maxZoom(), layerName);
      return ResponseEntity.notFound().build();
    }

    var handler = handlerRegistry.getHandler(layer.sourceType());
    if (handler.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(("No handler for layer type " + layer.sourceType()).getBytes());
    }

    Optional<TileResult> result;
    try {
      result = handler.get().getTile(layer, z, x, y);
    } catch (UpstreamUnavailableException e) {
      LOGGER.debug("Upstream blocked for layer {}: {}", layerName, e.getMessage());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    } catch (TileNotFoundException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.warn(
          "Error reading tile for layer {} {}/{}/{}: {}", layerName, z, x, y, e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    if (result.isEmpty()) {
      return ResponseEntity.noContent().build();
    }

    layerStore.getRuntimeState(layer.effectiveId()).incrementTilesServed();
    TileResult tile = result.get();
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    headers.add("Content-Type", tile.contentType());
    headers.add(HttpHeaders.CACHE_CONTROL, cacheControlFor(layer));
    if (tile.tileCompression() == COMPRESSION_GZIP) {
      headers.add("Content-Encoding", "gzip");
    }
    return new ResponseEntity<>(tile.data(), headers, HttpStatus.OK);
  }

  private String cacheControlFor(Layer layer) {
    String visibility = layer.isPublic() ? "public" : "private";
    Layer.SourceType type = layer.sourceType();
    int expirationMinutes = layer.tileExpirationMinutes();
    if (expirationMinutes == 0
        && (type == Layer.SourceType.LOCAL || type == Layer.SourceType.VECTOR_PMTILES)) {
      return visibility + ", immutable, max-age=" + IMMUTABLE_MAX_AGE_SECONDS;
    }
    long maxAge =
        expirationMinutes > 0
            ? expirationMinutes * 60L
            : configuration.getDefaultCacheMaxAgeSeconds();
    return visibility + ", max-age=" + maxAge;
  }
}
