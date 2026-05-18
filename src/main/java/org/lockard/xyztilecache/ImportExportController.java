package org.lockard.xyztilecache;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private static final DateTimeFormatter FILENAME_TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final ImportExportService importExportService;

  private final ConcurrentHashMap<String, ExportJob> exportJobs = new ConcurrentHashMap<>();
  private final ExecutorService exportExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "export-worker");
            t.setDaemon(true);
            return t;
          });

  ImportExportController(
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      ImportExportService importExportService) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.importExportService = importExportService;
  }

  @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> submitExport(@RequestBody ExportRequest request) throws IOException {
    boolean hasLayers =
        request != null && request.getLayers() != null && !request.getLayers().isEmpty();
    boolean includeVector = request != null && request.isIncludeVector();

    if (!hasLayers && !includeVector) {
      return ResponseEntity.badRequest().body("layers or includeVector must be specified");
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<Layer> resolved = new ArrayList<>();

    if (hasLayers) {
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
    }

    String jobId = UUID.randomUUID().toString();
    String filename = "tile-export-" + FILENAME_TS.format(Instant.now()) + ".zip";
    Path tempFile = Files.createTempFile("tile-export-", ".zip");
    ExportJob job = new ExportJob(jobId, filename, tempFile, auth.getName());
    exportJobs.put(jobId, job);

    List<Layer> resolvedLayers = List.copyOf(resolved);
    BoundingBox bbox = request.getBoundingBox();
    Integer minZoom = request.getMinZoom();
    Integer maxZoom = request.getMaxZoom();

    exportExecutor.submit(
        () -> {
          job.setStatus(ExportStatus.RUNNING);
          try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
            importExportService.streamExport(
                resolvedLayers, bbox, minZoom, maxZoom, includeVector, out);
            job.setStatus(ExportStatus.DONE);
          } catch (Exception e) {
            LOGGER.error("Export job {} failed", jobId, e);
            job.setError(e.getMessage());
            job.setStatus(ExportStatus.FAILED);
            try {
              Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
              LOGGER.warn("Failed to delete temp file for failed export job {}", jobId);
            }
          }
        });

    return ResponseEntity.accepted().body(new ExportJobStatus(job));
  }

  @GetMapping("/exports")
  public ResponseEntity<?> listExports() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    List<ExportJobStatus> jobs =
        exportJobs.values().stream()
            .filter(j -> j.getOwnerName().equals(auth.getName()))
            .map(ExportJobStatus::new)
            .toList();
    return ResponseEntity.ok(jobs);
  }

  @GetMapping("/exports/{id}")
  public ResponseEntity<?> getExportStatus(@PathVariable("id") String id) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    ExportJob job = exportJobs.get(id);
    if (job == null) {
      return ResponseEntity.notFound().build();
    }
    if (!job.getOwnerName().equals(auth.getName())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return ResponseEntity.ok(new ExportJobStatus(job));
  }

  @GetMapping("/exports/{id}/download")
  public void downloadExport(@PathVariable("id") String id, HttpServletResponse response)
      throws IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!isRealUser(auth)) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required");
      return;
    }
    ExportJob job = exportJobs.get(id);
    if (job == null) {
      writeError(response, HttpStatus.NOT_FOUND, "Export job not found");
      return;
    }
    if (!job.getOwnerName().equals(auth.getName())) {
      writeError(response, HttpStatus.FORBIDDEN, "Access denied");
      return;
    }
    if (job.getStatus() != ExportStatus.DONE) {
      writeError(response, HttpStatus.CONFLICT, "Export not ready: " + job.getStatus());
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/zip");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.getFilename() + "\"");
    Files.copy(job.getTempFile(), response.getOutputStream());
    exportJobs.remove(id);
    Files.deleteIfExists(job.getTempFile());
  }

  private static boolean isRealUser(Authentication auth) {
    return auth != null
        && auth.isAuthenticated()
        && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
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
}
