package org.lockard.xyztilecache.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.lockard.xyztilecache.model.ExportJob;
import org.lockard.xyztilecache.model.ExportJobStatus;
import org.lockard.xyztilecache.model.ExportRequest;
import org.lockard.xyztilecache.model.ExportStatus;
import org.lockard.xyztilecache.model.ImportSummary;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.service.ExportService;
import org.lockard.xyztilecache.service.ImportExportService;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ImportExportController {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportExportController.class);

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final ImportExportService importExportService;
  private final ExportService exportService;

  ImportExportController(
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      ImportExportService importExportService,
      ExportService exportService) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.importExportService = importExportService;
    this.exportService = exportService;
  }

  @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> submitExport(@RequestBody ExportRequest request) throws IOException {
    boolean hasLayers =
        request != null && request.getLayers() != null && !request.getLayers().isEmpty();

    if (!hasLayers) {
      return ResponseEntity.badRequest().body("layers must be specified");
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<Layer> resolved = new ArrayList<>();

    for (String id : request.getLayers()) {
      if (id == null || id.isBlank()) {
        return ResponseEntity.badRequest().body("Layer id must not be blank.");
      }
      Optional<Layer> opt = layerStore.getLayer(id);
      if (opt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Layer not found");
      }
      Layer layer = opt.get();
      if (!layerAccessService.canRead(layer, auth)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied to layer");
      }
      resolved.add(layer);
    }

    ExportJobStatus status =
        exportService.submit(
            resolved,
            request.getBoundingBox(),
            request.getMinZoom(),
            request.getMaxZoom(),
            auth.getName());
    return ResponseEntity.accepted().body(status);
  }

  @GetMapping("/exports")
  ResponseEntity<?> listExports() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return ResponseEntity.ok(exportService.listForOwner(auth.getName()));
  }

  @GetMapping("/exports/{id}")
  ResponseEntity<?> getExportStatus(@PathVariable("id") String id) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return exportService
        .getJob(id)
        .map(
            job -> {
              if (!job.getOwnerName().equals(auth.getName())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).<ExportJobStatus>build();
              }
              return ResponseEntity.ok(exportService.statusFor(job));
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/exports/{id}/download")
  void downloadExport(@PathVariable("id") String id, HttpServletResponse response)
      throws IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required");
      return;
    }
    Optional<ExportJob> jobOpt = exportService.getJob(id);
    if (jobOpt.isEmpty()) {
      writeError(response, HttpStatus.NOT_FOUND, "Export job not found");
      return;
    }
    ExportJob job = jobOpt.get();
    if (!job.getOwnerName().equals(auth.getName())) {
      writeError(response, HttpStatus.FORBIDDEN, "Access denied");
      return;
    }
    if (job.getStatus() != ExportStatus.DONE) {
      writeError(response, HttpStatus.CONFLICT, "Export not ready: " + job.getStatus());
      return;
    }
    Path tempFile = exportService.claimDownload(id);
    if (tempFile == null) {
      writeError(response, HttpStatus.NOT_FOUND, "Export job not found");
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/zip");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.getFilename() + "\"");
    try {
      Files.copy(tempFile, response.getOutputStream());
    } finally {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException e) {
        LOGGER.warn("Failed to delete export temp file {}", tempFile, e);
      }
    }
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

  private static boolean isRealUser(Authentication auth) {
    return auth != null
        && auth.isAuthenticated()
        && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
  }

  private static void writeError(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE);
    response.getWriter().write(message);
  }
}
