package org.lockard.xyztilecache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Point;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.lockard.xyztilecache.XyzUtil;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.ImportSummary;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Streams layer tiles into a zip for download and ingests an uploaded zip back into the cache. Each
 * layer is laid out as {@code <layerId>/layer.json} plus tile files. Raster layers use {@code
 * <layerId>/<z>/<x>/<y>.png}; VECTOR_PMTILES layers use {@code <layerId>/<name>.pmtiles}.
 */
@Service
public class ImportExportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportExportService.class);
  private static final Pattern SAFE_LAYER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern TILE_TAIL = Pattern.compile("\\d+/\\d+/\\d+\\.png");
  private static final Pattern SAFE_PMTILES_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.pmtiles");
  private static final Pattern CACHED_TILE_TAIL =
      Pattern.compile("remote-cache/\\d+/\\d+/\\d+\\.pbf");

  private final XyzConfiguration configuration;
  private final VectorPmtilesManager vectorPmtilesManager;
  private final LayerStore layerStore;
  private final ObjectMapper objectMapper;
  private final LayerAccessService layerAccessService;

  public ImportExportService(
      XyzConfiguration configuration,
      VectorPmtilesManager vectorPmtilesManager,
      LayerStore layerStore,
      ObjectMapper objectMapper,
      LayerAccessService layerAccessService) {
    this.configuration = configuration;
    this.vectorPmtilesManager = vectorPmtilesManager;
    this.layerStore = layerStore;
    this.objectMapper = objectMapper;
    this.layerAccessService = layerAccessService;
  }

  /**
   * Writes a zip containing each layer's {@code layer.json} and tile files. For raster layers, tile
   * files are {@code <layerId>/<z>/<x>/<y>.png}. For VECTOR_PMTILES layers, locally cached tiles
   * from {@code remote-cache/} are included as {@code <layerId>/remote-cache/<z>/<x>/<y>.pbf}. If
   * {@code bbox} is null, local pmtiles file(s) are also included in full and all cached tiles are
   * included; if {@code bbox} is non-null, the {@code pmtiles extract} CLI is used to produce a
   * bbox-cropped pmtiles file (skipped with a warning if the CLI is unavailable or fails), and
   * cached tile selection is filtered to the bbox.
   */
  public void streamExport(
      List<Layer> layers, BoundingBox bbox, Integer minZoom, Integer maxZoom, OutputStream out)
      throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
      for (Layer layer : layers) {
        String layerId = layer.getEffectiveId();
        Path layerDir = baseDir.resolve(layerId).normalize();

        ZipEntry meta = new ZipEntry(layerId + "/layer.json");
        zos.putNextEntry(meta);
        zos.write(objectMapper.writeValueAsBytes(layer));
        zos.closeEntry();

        if (!Files.isDirectory(layerDir)) {
          continue;
        }

        if (layer.getSourceType() == Layer.SourceType.VECTOR_PMTILES) {
          addPmtilesLayer(zos, layerId, layerDir, bbox, minZoom, maxZoom, layer);
        } else if (bbox == null) {
          addAllTiles(zos, layerId, layerDir);
        } else {
          int effectiveMax = Math.min(layer.getMaxZoom(), bbox.getMaxZoom());
          if (maxZoom != null) {
            effectiveMax = Math.min(effectiveMax, maxZoom);
          }
          int start = minZoom != null ? Math.max(0, minZoom) : 0;
          addBboxTilesFromDir(zos, layerDir, layerId + "/", ".png", bbox, start, effectiveMax);
        }
      }
    }
  }

  private void addPmtilesLayer(
      ZipOutputStream zos,
      String layerId,
      Path layerDir,
      BoundingBox bbox,
      Integer minZoom,
      Integer maxZoom,
      Layer layer)
      throws IOException {
    List<Path> pmtilesFiles;
    try (Stream<Path> files = Files.list(layerDir)) {
      pmtilesFiles = files.filter(p -> p.getFileName().toString().endsWith(".pmtiles")).toList();
    }

    Path remoteCacheDir = layerDir.resolve("remote-cache");

    if (bbox == null) {
      for (Path p : pmtilesFiles) {
        writeEntry(zos, layerId + "/" + p.getFileName().toString(), p);
      }
      if (Files.isDirectory(remoteCacheDir)) {
        try (var paths = Files.walk(remoteCacheDir)) {
          paths
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".pbf"))
              .forEach(
                  p -> {
                    String rel = layerDir.relativize(p).toString().replace('\\', '/');
                    try {
                      writeEntry(zos, layerId + "/" + rel, p);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
        } catch (UncheckedIOException e) {
          throw e.getCause();
        }
      }
    } else {
      int effectiveMax = Math.min(layer.getMaxZoom(), bbox.getMaxZoom());
      if (maxZoom != null) effectiveMax = Math.min(effectiveMax, maxZoom);
      int start = minZoom != null ? Math.max(0, minZoom) : 0;

      for (Path pmtilesPath : pmtilesFiles) {
        extractAndAddPmtiles(zos, layerId, pmtilesPath, bbox, start, effectiveMax);
      }

      if (Files.isDirectory(remoteCacheDir)) {
        addBboxTilesFromDir(
            zos, remoteCacheDir, layerId + "/remote-cache/", ".pbf", bbox, start, effectiveMax);
      }
    }
  }

  private void extractAndAddPmtiles(
      ZipOutputStream zos,
      String layerId,
      Path pmtilesPath,
      BoundingBox bbox,
      int minZoom,
      int maxZoom)
      throws IOException {
    Path tmpDir = Files.createTempDirectory("xyz-pmtiles-export-");
    Path tmpFile = tmpDir.resolve(pmtilesPath.getFileName());
    try {
      String bboxArg =
          String.format(
              Locale.US,
              "%f,%f,%f,%f",
              bbox.getWest(),
              bbox.getSouth(),
              bbox.getEast(),
              bbox.getNorth());
      List<String> cmd = new ArrayList<>();
      cmd.add("pmtiles");
      cmd.add("extract");
      cmd.add(pmtilesPath.toAbsolutePath().toString());
      cmd.add(tmpFile.toAbsolutePath().toString());
      cmd.add("--bbox=" + bboxArg);
      cmd.add("--maxzoom=" + maxZoom);
      if (minZoom > 0) cmd.add("--minzoom=" + minZoom);

      Process proc;
      try {
        proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      } catch (IOException e) {
        LOGGER.warn(
            "Could not start pmtiles extract for '{}': {}",
            pmtilesPath.getFileName(),
            e.getMessage());
        return;
      }
      String output = new String(proc.getInputStream().readAllBytes());
      int exit;
      try {
        exit = proc.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        proc.destroy();
        return;
      }
      if (exit == 0 && Files.exists(tmpFile)) {
        writeEntry(zos, layerId + "/" + pmtilesPath.getFileName().toString(), tmpFile);
      } else {
        LOGGER.warn(
            "pmtiles extract for '{}' failed (exit {}): {}",
            pmtilesPath.getFileName(),
            exit,
            output.trim());
      }
    } finally {
      try {
        Files.deleteIfExists(tmpFile);
      } catch (IOException ignored) {
      }
      try {
        Files.deleteIfExists(tmpDir);
      } catch (IOException ignored) {
      }
    }
  }

  private void addAllTiles(ZipOutputStream zos, String layerId, Path layerDir) throws IOException {
    try (var paths = Files.walk(layerDir)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".png"))
          .forEach(
              p -> {
                String rel = layerDir.relativize(p).toString().replace('\\', '/');
                try {
                  writeEntry(zos, layerId + "/" + rel, p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private void addBboxTilesFromDir(
      ZipOutputStream zos,
      Path tileDir,
      String entryPrefix,
      String ext,
      BoundingBox bbox,
      int startZ,
      int endZ)
      throws IOException {
    for (int z = startZ; z <= endZ; z++) {
      Path zDir = tileDir.resolve(Integer.toString(z));
      if (!Files.isDirectory(zDir)) continue;
      Point ul = XyzUtil.getTileNumber(bbox.getNorth(), bbox.getWest(), z);
      int xMin = ul.x;
      int xMax = XyzUtil.getTileNumber(bbox.getNorth(), bbox.getEast(), z).x;
      int yMin = ul.y;
      int yMax = XyzUtil.getTileNumber(bbox.getSouth(), bbox.getWest(), z).y;
      List<Path> xDirs;
      try (Stream<Path> s = Files.list(zDir)) {
        xDirs = s.filter(Files::isDirectory).toList();
      }
      for (Path xDir : xDirs) {
        int x;
        try {
          x = Integer.parseInt(xDir.getFileName().toString());
        } catch (NumberFormatException e) {
          continue;
        }
        if (x < xMin || x > xMax) continue;
        List<Path> yFiles;
        try (Stream<Path> s = Files.list(xDir)) {
          yFiles =
              s.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(ext))
                  .toList();
        }
        for (Path yFile : yFiles) {
          String fname = yFile.getFileName().toString();
          int y;
          try {
            y = Integer.parseInt(fname.substring(0, fname.length() - ext.length()));
          } catch (NumberFormatException e) {
            continue;
          }
          if (y < yMin || y > yMax) continue;
          writeEntry(zos, entryPrefix + z + "/" + x + "/" + y + ext, yFile);
        }
      }
    }
  }

  private static void writeEntry(ZipOutputStream zos, String entryName, Path file)
      throws IOException {
    zos.putNextEntry(new ZipEntry(entryName));
    Files.copy(file, zos);
    zos.closeEntry();
  }

  /**
   * Ingests a zip in the same shape produced by {@link #streamExport}. For each top-level layer
   * directory: {@code layer.json} is registered with the {@link LayerStore} only if no layer with
   * that id exists; raster tile files ({@code .png}) and pmtiles files ({@code .pmtiles}) overwrite
   * any existing files on disk. Path traversal attempts are rejected.
   */
  public ImportSummary importZip(InputStream in, Authentication auth) throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    Files.createDirectories(baseDir);

    List<String> added = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    long tilesWritten = 0L;
    long pmtilesImported = 0L;
    Map<String, Boolean> layerAccess = new HashMap<>();

    try (ZipInputStream zis = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
          throw new IllegalArgumentException("Illegal entry name: " + name);
        }

        int slash = name.indexOf('/');
        if (slash <= 0) {
          continue; // ignore stray top-level files
        }
        String layerId = name.substring(0, slash);
        if (!SAFE_LAYER_ID.matcher(layerId).matches()) {
          throw new IllegalArgumentException("Invalid layer id in entry: " + layerId);
        }
        Path layerDir = baseDir.resolve(layerId).normalize();
        if (!layerDir.startsWith(baseDir)) {
          throw new IllegalArgumentException("Entry escapes base directory: " + name);
        }
        Path target = baseDir.resolve(name).normalize();
        if (!target.startsWith(layerDir)) {
          throw new IllegalArgumentException("Entry escapes layer directory: " + name);
        }

        if (!layerAccess.computeIfAbsent(layerId, id -> checkLayerAccess(id, auth))) {
          throw new AccessDeniedException("Access denied to layer: " + layerId);
        }

        String tail = name.substring(slash + 1);
        if ("layer.json".equals(tail)) {
          handleLayerJson(zis, layerId, added, skipped);
        } else if (TILE_TAIL.matcher(tail).matches()) {
          Files.createDirectories(target.getParent());
          Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
          tilesWritten++;
        } else if (CACHED_TILE_TAIL.matcher(tail).matches()) {
          Files.createDirectories(target.getParent());
          Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
          tilesWritten++;
        } else if (tail.endsWith(".pmtiles")) {
          if (!SAFE_PMTILES_NAME.matcher(tail).matches()) {
            LOGGER.warn("Skipping PMTiles entry with unsafe filename: {}", name);
          } else {
            Files.createDirectories(layerDir);
            Path pmtilesTarget = layerDir.resolve(tail).normalize();
            if (!pmtilesTarget.startsWith(layerDir)) {
              LOGGER.warn("Skipping PMTiles entry that escapes layer directory: {}", name);
            } else {
              Files.copy(zis, pmtilesTarget, StandardCopyOption.REPLACE_EXISTING);
              vectorPmtilesManager.notifyFileAvailable(pmtilesTarget);
              pmtilesImported++;
            }
          }
        }
      }
    }

    return new ImportSummary(added, skipped, tilesWritten, pmtilesImported);
  }

  private boolean checkLayerAccess(String layerId, Authentication auth) {
    return layerStore
        .getLayer(layerId)
        .map(l -> layerAccessService.canRead(l, auth))
        .orElse(layerAccessService.isAdmin(auth));
  }

  private void handleLayerJson(
      ZipInputStream zis, String layerId, List<String> added, List<String> skipped)
      throws IOException {
    Layer layer = objectMapper.readValue(zis.readAllBytes(), Layer.class);
    if (layer.getEffectiveId() == null
        || layer.getEffectiveId().isBlank()
        || !layerId.equals(layer.getEffectiveId())) {
      layer.setId(layerId);
      if (layer.getName() == null || layer.getName().isBlank()) {
        layer.setName(layerId);
      }
    }
    if (layerStore.getLayer(layerId).isPresent()) {
      skipped.add(layerId);
      return;
    }
    try {
      layerStore.addLayer(layer);
      added.add(layerId);
    } catch (IllegalArgumentException e) {
      LOGGER.warn("Skipped layer '{}' on import: {}", layerId, e.getMessage());
      skipped.add(layerId);
    }
  }
}
