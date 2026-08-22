package org.lockard.xyztilecache.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.PmtilesLayer;
import org.lockard.xyztilecache.pmtiles.PmtilesTileType;
import org.lockard.xyztilecache.service.PmtilesManager;
import org.lockard.xyztilecache.service.PmtilesUploadService;
import org.lockard.xyztilecache.store.LayerAlreadyExistsException;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creates PMTiles layers from uploaded archives, and adds archives to existing ones.
 *
 * <p>Uploads are accepted as {@code .pmtiles} or {@code .mbtiles}; MBTiles are converted on the way
 * in. Admin-only, via the catch-all write rule in SecurityConfig.
 *
 * <p>Both endpoints are synchronous, matching the GeoTIFF upload: the response arrives once the
 * archives are installed and serving. Converting a very large MBTiles holds the request open for
 * the duration.
 */
@RestController
class PmtilesUploadController {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesUploadController.class);
  private static final int DEFAULT_MAX_ZOOM = 15;

  private final LayerStore layerStore;
  private final PmtilesManager pmtilesManager;
  private final PmtilesUploadService uploadService;

  PmtilesUploadController(
      LayerStore layerStore, PmtilesManager pmtilesManager, PmtilesUploadService uploadService) {
    this.layerStore = layerStore;
    this.pmtilesManager = pmtilesManager;
    this.uploadService = uploadService;
  }

  /** Creates a PMTiles layer whose archives are the uploaded files. */
  @PostMapping(value = "/layers/pmtiles", consumes = "multipart/form-data")
  ResponseEntity<?> createFromUpload(
      @RequestParam("id") String rawId,
      @RequestParam("files") List<MultipartFile> files,
      @Nullable @RequestParam(value = "name", required = false) String displayName,
      @Nullable @RequestParam(value = "maxZoom", required = false) Integer maxZoom,
      @Nullable @RequestParam(value = "attribution", required = false) String attribution,
      @Nullable @RequestParam(value = "allowedUsers", required = false) String allowedUsersRaw,
      @Nullable @RequestParam(value = "allowedGroups", required = false) String allowedGroupsRaw) {
    String id = rawId == null ? "" : rawId.trim();
    if (!LayerStore.SAFE_LAYER_ID.matcher(id).matches()) {
      return ResponseEntity.badRequest()
          .body(
              "Layer id must be 1-64 chars of letters, digits, '.', '-' or '_'"
                  + " (starting with a letter or digit).");
    }
    if (layerStore.getLayer(id).isPresent()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body("Layer '" + id + "' already exists.");
    }

    List<PmtilesUploadService.StagedArchive> installed;
    try {
      // Installed before the layer is registered so a rejected upload leaves nothing behind: an
      // empty layer pointing at no archives would serve nothing but would still have to be
      // cleaned up by hand.
      installed = uploadService.install(id, files, Optional.empty());
    } catch (PmtilesUploadService.UploadRejectedException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (PmtilesUploadService.ConverterUnavailableException e) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to install uploaded archives for layer '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to store the uploaded archives.");
    }

    // Defaulted from the archives once they are readable: an uploader should not have to know how
    // deep their own file goes, and a layer claiming more zoom than it holds just serves blanks.
    int effectiveMaxZoom =
        maxZoom != null && maxZoom > 0
            ? maxZoom
            : installed.stream()
                .mapToInt(PmtilesUploadService.StagedArchive::maxZoom)
                .filter(z -> z > 0)
                .max()
                .orElse(DEFAULT_MAX_ZOOM);

    Layer layer =
        new PmtilesLayer(
            id,
            displayName == null || displayName.isBlank() ? id : displayName.trim(),
            // No urlTemplate: the layer is served entirely from the archives in its directory.
            null,
            attribution == null || attribution.isBlank() ? null : attribution.trim(),
            effectiveMaxZoom,
            0,
            0,
            parseCommaSeparated(allowedUsersRaw),
            parseCommaSeparated(allowedGroupsRaw));

    try {
      layerStore.addLayer(layer);
    } catch (LayerAlreadyExistsException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to persist layer '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to persist the layer.");
    }
    pmtilesManager.initLayer(layer);

    return ResponseEntity.status(HttpStatus.CREATED).body(summary(id, installed));
  }

  /** Adds archives to an existing PMTiles layer. */
  @PostMapping(value = "/layers/{id}/pmtiles", consumes = "multipart/form-data")
  ResponseEntity<?> addToExisting(
      @PathVariable("id") String id, @RequestParam("files") List<MultipartFile> files) {
    Optional<Layer> existing = layerStore.getLayer(id);
    if (existing.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Layer '" + id + "' not found.");
    }
    if (existing.get().sourceType() != Layer.SourceType.PMTILES) {
      return ResponseEntity.badRequest()
          .body("Layer '" + id + "' is not a PMTiles layer; archives cannot be added to it.");
    }

    List<PmtilesUploadService.StagedArchive> installed;
    try {
      // The layer's established type gates the upload: mixing kinds in one layer is refused.
      installed = uploadService.install(id, files, pmtilesManager.tileType(id));
    } catch (PmtilesUploadService.UploadRejectedException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (PmtilesUploadService.ConverterUnavailableException e) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to add archives to layer '{}'.", id, e);
      return ResponseEntity.internalServerError().body("Failed to store the uploaded archives.");
    }

    return ResponseEntity.ok(summary(id, installed));
  }

  private Map<String, Object> summary(
      String layerId, List<PmtilesUploadService.StagedArchive> installed) {
    PmtilesTileType type = pmtilesManager.tileType(layerId).orElse(installed.getFirst().tileType());
    return Map.of(
        "id", layerId,
        "tileType", type.name(),
        "tileExtension", type.extension(),
        "maxZoom", layerStore.getLayer(layerId).map(Layer::maxZoom).orElse(0),
        "archives",
            installed.stream().map(a -> Map.of("name", a.fileName(), "bytes", a.bytes())).toList());
  }

  private static List<String> parseCommaSeparated(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }
}
