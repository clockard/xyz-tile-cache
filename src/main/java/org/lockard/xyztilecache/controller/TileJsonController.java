package org.lockard.xyztilecache.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.store.LayerStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
class TileJsonController {

  private static final double WEB_MERCATOR_LAT = 85.0511287798066;

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;

  TileJsonController(LayerStore layerStore, LayerAccessService layerAccessService) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
  }

  @GetMapping(value = "/layers/{id}/tilejson", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> tilejson(@PathVariable("id") String id) {
    Layer layer = layerStore.getLayers().get(id);
    if (layer == null) {
      return ResponseEntity.notFound().build();
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!layerAccessService.canRead(layer, auth)) {
      if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
      }
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    boolean vector = layer.sourceType() == Layer.SourceType.VECTOR_PMTILES;
    String ext = vector ? "pbf" : layer.tileFileExtension();
    String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    String tileUrl = base + "/tilesZXY/" + id + "/{z}/{x}/{y}." + ext;

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("tilejson", "3.0.0");
    doc.put("tiles", List.of(tileUrl));
    doc.put("name", layer.name() != null ? layer.name() : id);
    if (layer.attribution() != null && !layer.attribution().isBlank()) {
      doc.put("attribution", layer.attribution());
    }
    doc.put("scheme", "xyz");
    doc.put("minzoom", 0);
    doc.put("maxzoom", layer.maxZoom());
    doc.put("bounds", List.of(-180.0, -WEB_MERCATOR_LAT, 180.0, WEB_MERCATOR_LAT));
    doc.put("center", List.of(0.0, 0.0, 0));
    doc.put("format", ext);
    if (vector) {
      // TileJSON 3.0 requires vector_layers for vector sources, but the PMTiles
      // metadata may not be readable for remote sources. Emit an empty array so
      // clients that strictly validate still accept the doc.
      doc.put("vector_layers", List.of());
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(doc, headers, HttpStatus.OK);
  }
}
