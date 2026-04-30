package org.lockard.xyztilecache;

import java.io.IOException;
import java.util.Collection;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;

  LayerController(XyzConfiguration configuration, LayerStore layerStore) {
    this.configuration = configuration;
    this.layerStore = layerStore;
  }

  @GetMapping
  ResponseEntity<Collection<Layer>> list() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(configuration.getLayers().values(), headers, HttpStatus.OK);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> add(@RequestBody Layer layer) {
    try {
      layerStore.addLayer(layer);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist layer '{}'.", layer.getName(), e);
      return ResponseEntity.internalServerError().body("Failed to persist layer.");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(configuration.getLayers().get(layer.getName()));
  }

  @PutMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> update(@PathVariable("name") String name, @RequestBody Layer layer) {
    try {
      layerStore.updateLayer(name, layer);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.error("Failed to persist update for layer '{}'.", name, e);
      return ResponseEntity.internalServerError().body("Failed to persist layer.");
    }
    return ResponseEntity.ok(configuration.getLayers().get(name));
  }

  @DeleteMapping("/{name}")
  ResponseEntity<?> delete(@PathVariable("name") String name) {
    try {
      layerStore.removeLayer(name);
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOGGER.error("Failed to persist removal of layer '{}'.", name, e);
      return ResponseEntity.internalServerError().body("Failed to persist layer removal.");
    }
    return ResponseEntity.noContent().build();
  }
}
