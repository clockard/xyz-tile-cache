package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.PreloadCreateRequest;
import org.lockard.xyztilecache.model.PreloadInfo;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.service.PreloadService;
import org.lockard.xyztilecache.store.PreloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/preloads")
public class PreloadController {

  private static final Logger LOGGER = LoggerFactory.getLogger(PreloadController.class);

  private final PreloadService preloadService;
  private final PreloadStore preloadStore;
  private final XyzConfiguration xyzConfiguration;
  private final LayerAccessService layerAccessService;

  public PreloadController(
      PreloadService preloadService,
      PreloadStore preloadStore,
      XyzConfiguration xyzConfiguration,
      LayerAccessService layerAccessService) {
    this.preloadService = preloadService;
    this.preloadStore = preloadStore;
    this.xyzConfiguration = xyzConfiguration;
    this.layerAccessService = layerAccessService;
  }

  @GetMapping
  public ResponseEntity<List<PreloadInfo>> list() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<PreloadInfo> infos = new ArrayList<>();
    for (Preload p : preloadStore.listPreloads()) {
      if (layerAccessService.canViewPreload(p, auth)) {
        infos.add(toInfo(p));
      }
    }
    return new ResponseEntity<>(infos, headers, HttpStatus.OK);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> create(@RequestBody PreloadCreateRequest request) {
    if (request.getBoundingBox() == null) {
      return ResponseEntity.badRequest().body("boundingBox is required");
    }
    try {
      Preload preload =
          preloadService.submit(
              request.getName(),
              request.getBoundingBox(),
              request.getMaxZoom(),
              request.getLayers(),
              request.isIncludeVector(),
              request.getVectorLayerId(),
              request.getAllowedUsers(),
              request.getAllowedGroups());
      if (preload == null) {
        return ResponseEntity.badRequest().body("No valid layers selected.");
      }
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(toInfo(preload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist preload", e);
      return ResponseEntity.internalServerError().body("Failed to persist preload.");
    }
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(@PathVariable("id") String id) {
    try {
      preloadStore.removePreload(id);
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.error("Failed to remove preload '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to remove preload.");
    }
    return ResponseEntity.noContent().build();
  }

  private PreloadInfo toInfo(Preload p) {
    Long sizeBytes = null;
    if (p.getPmtilesFilename() != null && p.getVectorLayerId() != null) {
      Path path =
          Path.of(
              xyzConfiguration.getBaseTileDirectory(),
              p.getVectorLayerId(),
              p.getPmtilesFilename());
      if (Files.exists(path)) {
        try {
          sizeBytes = Files.size(path);
        } catch (IOException e) {
          LOGGER.debug("Could not size {}", path, e);
        }
      }
    }
    return new PreloadInfo(
        p.getId(),
        p.getName(),
        p.getBoundingBox(),
        p.getMaxZoom(),
        p.getLayers(),
        p.isIncludesVector(),
        p.getPmtilesFilename(),
        p.getCreatedAt(),
        sizeBytes,
        p.getAllowedUsers(),
        p.getAllowedGroups());
  }
}
