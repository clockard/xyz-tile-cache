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
import java.util.Map;
import java.util.Set;
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
   * files are {@code <layerId>/<z>/<x>/<y>.png}. For VECTOR_PMTILES layers, the pmtiles file(s)
   * from {@code <baseTileDir>/<layerId>/} are included as {@code <layerId>/<name>.pmtiles}. If
   * {@code bbox} is non-null, raster tile selection is filtered to that bbox; VECTOR_PMTILES files
   * are always included in full.
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
          addAllPmtiles(zos, layerId, layerDir);
        } else if (bbox == null) {
          addAllTiles(zos, layerId, layerDir);
        } else {
          int effectiveMax = Math.min(layer.getMaxZoom(), bbox.getMaxZoom());
          if (maxZoom != null) {
            effectiveMax = Math.min(effectiveMax, maxZoom);
          }
          int start = minZoom != null ? Math.max(0, minZoom) : 0;
          for (int z = start; z <= effectiveMax; z++) {
            Set<Point> tiles = XyzUtil.calculateXyTilesForBBox(bbox, z);
            for (Point p : tiles) {
              Path tile =
                  layerDir
                      .resolve(Integer.toString(z))
                      .resolve(Integer.toString(p.x))
                      .resolve(p.y + ".png");
              if (Files.isRegularFile(tile)) {
                writeEntry(zos, String.format("%s/%d/%d/%d.png", layerId, z, p.x, p.y), tile);
              }
            }
          }
        }
      }
    }
  }

  private void addAllPmtiles(ZipOutputStream zos, String layerId, Path layerDir)
      throws IOException {
    try (Stream<Path> files = Files.list(layerDir)) {
      List<Path> pmtilesFiles =
          files.filter(p -> p.getFileName().toString().endsWith(".pmtiles")).toList();
      for (Path p : pmtilesFiles) {
        writeEntry(zos, layerId + "/" + p.getFileName().toString(), p);
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
