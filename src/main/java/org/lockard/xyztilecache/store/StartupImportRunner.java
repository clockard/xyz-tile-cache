package org.lockard.xyztilecache.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.lockard.xyztilecache.config.VectorConfiguration;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.ImportSummary;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.service.ImportExportService;
import org.lockard.xyztilecache.service.PmtilesDownloader;
import org.lockard.xyztilecache.service.VectorTileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class StartupImportRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(StartupImportRunner.class);
  private static final String TRACKING_FILE = ".imported";
  private static final Pattern SAFE_PMTILES_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.pmtiles");

  private final XyzConfiguration configuration;
  private final ImportExportService importExportService;
  private final VectorConfiguration vectorConfiguration;
  private final VectorTileService vectorTileService;
  private final PmtilesDownloader pmtilesDownloader;

  StartupImportRunner(
      XyzConfiguration configuration,
      ImportExportService importExportService,
      VectorConfiguration vectorConfiguration,
      VectorTileService vectorTileService,
      PmtilesDownloader pmtilesDownloader) {
    this.configuration = configuration;
    this.importExportService = importExportService;
    this.vectorConfiguration = vectorConfiguration;
    this.vectorTileService = vectorTileService;
    this.pmtilesDownloader = pmtilesDownloader;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runStartupImports() {
    String importDirStr = configuration.getImportDirectory();
    if (importDirStr != null && !importDirStr.isBlank()) {
      Path importDir = Paths.get(importDirStr);
      if (!Files.isDirectory(importDir)) {
        LOGGER.info("Import directory does not exist, skipping startup imports: {}", importDir);
      } else {
        processImportDirectory(importDir);
      }
    }

    triggerWorldVectorDownload();
  }

  private void processImportDirectory(Path importDir) {
    Path trackingFile = importDir.resolve(TRACKING_FILE);
    Set<String> alreadyImported = loadTracked(trackingFile);
    Authentication adminAuth = buildAdminAuth();

    try (var paths = Files.list(importDir)) {
      paths
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".zip") || name.endsWith(".pmtiles");
              })
          .filter(p -> !alreadyImported.contains(p.getFileName().toString()))
          .sorted()
          .forEach(
              p -> {
                if (p.getFileName().toString().endsWith(".zip")) {
                  processZip(p, adminAuth, alreadyImported, trackingFile);
                } else {
                  processPmtilesFile(p, alreadyImported, trackingFile);
                }
              });
    } catch (IOException e) {
      LOGGER.error("Failed to list import directory: {}", importDir, e);
    }
  }

  private void processZip(
      Path zip, Authentication auth, Set<String> alreadyImported, Path trackingFile) {
    String filename = zip.getFileName().toString();
    LOGGER.info("Processing startup import: {}", filename);
    try {
      ImportSummary summary;
      try (InputStream in = Files.newInputStream(zip)) {
        summary = importExportService.importZip(in, auth);
      }
      int pmtilesWritten = extractPmtilesFromZip(zip);
      LOGGER.info(
          "Imported {}: {} layer(s) added, {} skipped, {} tile(s) written, {} pmtiles extracted",
          filename,
          summary.layersAdded().size(),
          summary.layersSkipped().size(),
          summary.tilesWritten(),
          pmtilesWritten);
      alreadyImported.add(filename);
      saveTracked(trackingFile, alreadyImported);
    } catch (Exception e) {
      LOGGER.error("Failed to import {}, skipping", filename, e);
    }
  }

  private void processPmtilesFile(
      Path pmtilesFile, Set<String> alreadyImported, Path trackingFile) {
    String filename = pmtilesFile.getFileName().toString();
    LOGGER.info("Processing startup PMTiles import: {}", filename);
    try {
      String downloadDirStr = vectorConfiguration.getDownloadDirectory();
      if (downloadDirStr == null || downloadDirStr.isBlank()) {
        LOGGER.warn("Skipping {}: xyz.vector.downloadDirectory is not configured", filename);
        return;
      }
      if (!SAFE_PMTILES_NAME.matcher(filename).matches()) {
        LOGGER.warn("Skipping PMTiles file with unsafe name: {}", filename);
        return;
      }
      Path downloadDir = Path.of(downloadDirStr).toAbsolutePath().normalize();
      Files.createDirectories(downloadDir);
      Path target = downloadDir.resolve(filename).normalize();
      if (!target.startsWith(downloadDir)) {
        LOGGER.warn("Skipping PMTiles file that escapes download directory: {}", filename);
        return;
      }
      Files.copy(pmtilesFile, target, StandardCopyOption.REPLACE_EXISTING);
      vectorTileService.registerDownload(target);
      LOGGER.info("Imported {}: 1 pmtiles extracted", filename);
      alreadyImported.add(filename);
      saveTracked(trackingFile, alreadyImported);
    } catch (Exception e) {
      LOGGER.error("Failed to import {}, skipping", filename, e);
    }
  }

  private int extractPmtilesFromZip(Path zip) throws IOException {
    String downloadDirStr = vectorConfiguration.getDownloadDirectory();
    if (downloadDirStr == null || downloadDirStr.isBlank()) {
      return 0;
    }
    Path downloadDir = Path.of(downloadDirStr).toAbsolutePath().normalize();
    Files.createDirectories(downloadDir);

    int count = 0;
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream zis = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String entryName = entry.getName();
        String basename = Path.of(entryName).getFileName().toString();
        if (!basename.endsWith(".pmtiles")) {
          continue;
        }
        if (!SAFE_PMTILES_NAME.matcher(basename).matches()) {
          LOGGER.warn("Skipping PMTiles entry with unsafe name: {}", entryName);
          continue;
        }
        Path target = downloadDir.resolve(basename).normalize();
        if (!target.startsWith(downloadDir)) {
          LOGGER.warn("Skipping PMTiles entry that escapes download directory: {}", entryName);
          continue;
        }
        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
        vectorTileService.registerDownload(target);
        count++;
      }
    }
    return count;
  }

  private void triggerWorldVectorDownload() {
    int initZoom = vectorConfiguration.getInitZoom();
    if (initZoom <= 0 || !vectorConfiguration.isEnabled()) {
      return;
    }
    String downloadDirStr = vectorConfiguration.getDownloadDirectory();
    if (downloadDirStr == null || downloadDirStr.isBlank()) {
      LOGGER.warn(
          "XYZ_VECTOR_INIT_ZOOM={} set but xyz.vector.downloadDirectory not configured;"
              + " skipping world vector download",
          initZoom);
      return;
    }
    String sourceUrl = vectorConfiguration.getSourceUrl();
    if (sourceUrl == null || sourceUrl.isBlank()) {
      LOGGER.warn(
          "XYZ_VECTOR_INIT_ZOOM={} set but xyz.vector.sourceUrl not configured;"
              + " skipping world vector download",
          initZoom);
      return;
    }

    String filename = "world_z0-" + initZoom + ".pmtiles";
    Path target = Path.of(downloadDirStr).resolve(filename);
    if (Files.exists(target)) {
      LOGGER.info("World vector PMTiles already exists: {}; skipping init download", target);
      return;
    }

    BoundingBox world = new BoundingBox();
    world.setWest(-180);
    world.setSouth(-90);
    world.setEast(180);
    world.setNorth(90);

    Preload preload = new Preload();
    preload.setId("world-init-" + initZoom);
    preload.setName("World z0-" + initZoom);
    preload.setBoundingBox(world);
    preload.setMaxZoom(initZoom);
    preload.setIncludesVector(true);
    preload.setPmtilesFilename(filename);
    preload.setCreatedAt(Instant.now());

    LOGGER.info("Starting world vector tile download to zoom {} ({})", initZoom, filename);
    try {
      pmtilesDownloader.startDownload(preload);
    } catch (Exception e) {
      LOGGER.error("Failed to start world vector download: {}", e.getMessage(), e);
    }
  }

  private Set<String> loadTracked(Path trackingFile) {
    if (!Files.exists(trackingFile)) {
      return new HashSet<>();
    }
    try {
      return new HashSet<>(Files.readAllLines(trackingFile));
    } catch (IOException e) {
      LOGGER.warn("Could not read tracking file, treating all imports as new: {}", trackingFile);
      return new HashSet<>();
    }
  }

  private void saveTracked(Path trackingFile, Set<String> imported) {
    try {
      Files.write(trackingFile, imported);
    } catch (IOException e) {
      LOGGER.error("Failed to update tracking file: {}", trackingFile, e);
    }
  }

  private Authentication buildAdminAuth() {
    String role = "ROLE_" + configuration.getAdminRole().toUpperCase(Locale.ROOT);
    return new UsernamePasswordAuthenticationToken(
        "startup-import", null, List.of(new SimpleGrantedAuthority(role)));
  }
}
