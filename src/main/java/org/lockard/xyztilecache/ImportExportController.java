package org.lockard.xyztilecache;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ImportExportController {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportExportController.class);
  private static final DateTimeFormatter FILENAME_TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final ImportExportService importExportService;

  ImportExportController(
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      ImportExportService importExportService) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.importExportService = importExportService;
  }

  @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  public void export(@RequestBody ExportRequest request, HttpServletResponse response)
      throws IOException {
    if (request == null || request.getLayers() == null || request.getLayers().isEmpty()) {
      writeError(response, HttpStatus.BAD_REQUEST, "layers must not be empty");
      return;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<Layer> resolved = new ArrayList<>(request.getLayers().size());
    for (String id : request.getLayers()) {
      if (id == null || id.isBlank()) {
        writeError(response, HttpStatus.BAD_REQUEST, "Layer id must not be blank.");
        return;
      }
      Optional<Layer> opt = layerStore.getLayer(id);
      if (opt.isEmpty()) {
        writeError(response, HttpStatus.NOT_FOUND, "Layer not found: " + id);
        return;
      }
      Layer layer = opt.get();
      if (isVectorLayer(layer)) {
        writeError(response, HttpStatus.BAD_REQUEST, "Vector layers cannot be exported: " + id);
        return;
      }
      if (!layerAccessService.canRead(layer, auth)) {
        writeError(response, HttpStatus.FORBIDDEN, "Access denied to layer: " + id);
        return;
      }
      resolved.add(layer);
    }

    String filename = "tile-export-" + FILENAME_TS.format(Instant.now()) + ".zip";
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/zip");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
    importExportService.streamExport(
        resolved,
        request.getBoundingBox(),
        request.getMinZoom(),
        request.getMaxZoom(),
        response.getOutputStream());
  }

  @PostMapping(value = "/import", consumes = "multipart/form-data")
  ResponseEntity<?> importZip(@RequestParam("file") MultipartFile file) {
    if (file == null || file.isEmpty()) {
      return ResponseEntity.badRequest().body("file is required");
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    try (var in = file.getInputStream()) {
      ImportSummary summary = importExportService.importZip(in, auth);
      return ResponseEntity.ok(summary);
    } catch (AccessDeniedException e) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (IOException e) {
      LOGGER.error("Failed to import zip.", e);
      return ResponseEntity.internalServerError().body("Failed to import: " + e.getMessage());
    }
  }

  private static void writeError(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE);
    response.getWriter().write(message);
  }

  private static boolean isVectorLayer(Layer layer) {
    String url = layer.getUrlTemplate();
    if (url == null) return false;
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("pmtiles://") || lower.endsWith(".pbf") || lower.endsWith(".mvt");
  }
}
