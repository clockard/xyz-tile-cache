package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.lockard.xyztilecache.service.GeoTiffTiler;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class GeoTiffController {

  private static final Logger LOGGER = LoggerFactory.getLogger(GeoTiffController.class);
  private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}");

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;
  private final GeoTiffTiler tiler;

  GeoTiffController(XyzConfiguration configuration, LayerStore layerStore, GeoTiffTiler tiler) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.tiler = tiler;
  }

  @PostMapping(value = "/layers/geotiff", consumes = "multipart/form-data")
  ResponseEntity<?> uploadGeoTiff(
      @RequestParam("name") String rawName,
      @RequestParam("file") MultipartFile file,
      @Nullable @RequestParam(value = "allowedUsers", required = false) String allowedUsersRaw,
      @Nullable @RequestParam(value = "allowedGroups", required = false) String allowedGroupsRaw) {
    String name = rawName == null ? "" : rawName.trim();
    if (!SAFE_NAME.matcher(name).matches()) {
      return ResponseEntity.badRequest()
          .body(
              "Layer name must be 1-64 chars of letters, digits, '.', '-' or '_' "
                  + "(starting with a letter or digit).");
    }
    if (file == null || file.isEmpty()) {
      return ResponseEntity.badRequest().body("file is required");
    }
    if (layerStore.getLayers().containsKey(name)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body("Layer '" + name + "' already exists.");
    }

    Path baseDir = Paths.get(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    Path outputDir = baseDir.resolve(name).normalize();
    if (!outputDir.startsWith(baseDir)) {
      return ResponseEntity.badRequest().body("Invalid layer name.");
    }
    if (Files.exists(outputDir)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body("Tile directory for '" + name + "' already exists. Remove it first.");
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile("upload-", ".tif");
      file.transferTo(tempFile.toFile());
    } catch (IOException e) {
      LOGGER.error("Failed to save uploaded GeoTIFF.", e);
      return ResponseEntity.internalServerError().body("Failed to save uploaded file.");
    }

    GeoTiffTiler.Result tilingResult;
    try {
      tilingResult = tiler.tile(tempFile, outputDir);
    } catch (IOException e) {
      LOGGER.error("gdal2tiles failed for layer '{}'.", name, e);
      deleteRecursively(outputDir);
      return ResponseEntity.unprocessableEntity().body("Tiling failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deleteRecursively(outputDir);
      return ResponseEntity.internalServerError().body("Tiling was interrupted.");
    } finally {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException e) {
        LOGGER.warn("Failed to delete temp upload {}.", tempFile, e);
      }
    }

    Layer layer =
        new org.lockard.xyztilecache.model.LocalLayer(
            name,
            name,
            null,
            tilingResult.maxZoom(),
            0,
            0,
            parseCommaSeparated(allowedUsersRaw),
            parseCommaSeparated(allowedGroupsRaw));

    try {
      layerStore.addLayer(layer);
    } catch (IllegalArgumentException e) {
      deleteRecursively(outputDir);
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist layer '{}'.", name, e);
      deleteRecursively(outputDir);
      return ResponseEntity.internalServerError().body("Failed to persist layer.");
    }

    Layer registered = layerStore.getLayers().get(name);
    if (registered != null) {
      LayerRuntimeState state = layerStore.getRuntimeState(name);
      state.setCachedTiles(tilingResult.tileCount());
      state.setCachedTilesSize(tilingResult.totalBytes());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(registered);
  }

  private static List<String> parseCommaSeparated(@Nullable String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  LOGGER.warn("Failed to delete {}.", p, e);
                }
              });
    } catch (IOException e) {
      LOGGER.warn("Failed to walk {} for cleanup.", root, e);
    }
  }
}
