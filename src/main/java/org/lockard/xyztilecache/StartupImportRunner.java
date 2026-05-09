package org.lockard.xyztilecache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
class StartupImportRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(StartupImportRunner.class);
  private static final String TRACKING_FILE = ".imported";

  private final XyzConfiguration configuration;
  private final ImportExportService importExportService;

  StartupImportRunner(XyzConfiguration configuration, ImportExportService importExportService) {
    this.configuration = configuration;
    this.importExportService = importExportService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runStartupImports() {
    String importDirStr = configuration.getImportDirectory();
    if (importDirStr == null || importDirStr.isBlank()) {
      return;
    }
    Path importDir = Paths.get(importDirStr);
    if (!Files.isDirectory(importDir)) {
      LOGGER.info("Import directory does not exist, skipping startup imports: {}", importDir);
      return;
    }

    Path trackingFile = importDir.resolve(TRACKING_FILE);
    Set<String> alreadyImported = loadTracked(trackingFile);
    Authentication adminAuth = buildAdminAuth();

    try (var paths = Files.list(importDir)) {
      paths
          .filter(p -> p.getFileName().toString().endsWith(".zip"))
          .filter(p -> !alreadyImported.contains(p.getFileName().toString()))
          .sorted()
          .forEach(p -> processZip(p, adminAuth, alreadyImported, trackingFile));
    } catch (IOException e) {
      LOGGER.error("Failed to list import directory: {}", importDir, e);
    }
  }

  private void processZip(
      Path zip, Authentication auth, Set<String> alreadyImported, Path trackingFile) {
    String filename = zip.getFileName().toString();
    LOGGER.info("Processing startup import: {}", filename);
    try (InputStream in = Files.newInputStream(zip)) {
      ImportSummary summary = importExportService.importZip(in, auth);
      LOGGER.info(
          "Imported {}: {} layer(s) added, {} skipped, {} tile(s) written",
          filename,
          summary.layersAdded().size(),
          summary.layersSkipped().size(),
          summary.tilesWritten());
      alreadyImported.add(filename);
      saveTracked(trackingFile, alreadyImported);
    } catch (Exception e) {
      LOGGER.error("Failed to import {}, skipping", filename, e);
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
