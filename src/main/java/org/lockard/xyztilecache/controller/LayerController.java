package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.store.LayerAlreadyExistsException;
import org.lockard.xyztilecache.store.LayerStore;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/layers")
class LayerController {

  private static final Logger LOGGER = LoggerFactory.getLogger(LayerController.class);

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;

  LayerController(LayerStore layerStore, LayerAccessService layerAccessService) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
  }

  @GetMapping
  ResponseEntity<Collection<Layer>> list() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<Layer> visible =
        layerStore.getLayers().values().stream()
            .filter(l -> layerAccessService.canRead(l, auth))
            .toList();
    return new ResponseEntity<>(visible, headers, HttpStatus.OK);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> add(@RequestBody Layer layer) {
    try {
      layerStore.addLayer(layer);
    } catch (LayerAlreadyExistsException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist layer '{}'.", layer.effectiveId(), e);
      return ResponseEntity.internalServerError().body("Failed to persist layer.");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(layerStore.getLayers().get(layer.effectiveId()));
  }

  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(@PathVariable("id") String id, @RequestBody Layer layer) {
    try {
      layerStore.updateLayer(id, layer);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.error("Failed to persist update for layer '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to persist layer.");
    }
    return ResponseEntity.ok(layerStore.getLayers().get(id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<?> delete(@PathVariable("id") String id) {
    try {
      layerStore.removeLayer(id);
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.error("Failed to persist removal of layer '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to persist layer removal.");
    }
    return ResponseEntity.noContent().build();
  }
}
