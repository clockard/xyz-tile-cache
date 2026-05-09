package org.lockard.xyztilecache;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Streams raster-layer tiles into a zip for download and ingests an uploaded zip back into the
 * cache. Each layer is laid out as {@code <layerId>/layer.json} plus {@code
 * <layerId>/<z>/<x>/<y>.png} entries, matching the on-disk format.
 */
@Service
public class ImportExportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportExportService.class);
  private static final Pattern SAFE_LAYER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern TILE_TAIL = Pattern.compile("\\d+/\\d+/\\d+\\.png");

  private final XyzConfiguration configuration;
  private final LayerStore layerStore;
  private final ObjectMapper objectMapper;
  private final LayerAccessService layerAccessService;

  public ImportExportService(
      XyzConfiguration configuration,
      LayerStore layerStore,
      ObjectMapper objectMapper,
      LayerAccessService layerAccessService) {
    this.configuration = configuration;
    this.layerStore = layerStore;
    this.objectMapper = objectMapper;
    this.layerAccessService = layerAccessService;
  }

  /**
   * Writes a zip containing each layer's {@code layer.json} and tile files. If {@code bbox} is
   * null, every cached tile under the layer directory is included; otherwise only tiles whose (x,y)
   * coordinates fall inside the bbox at each zoom level (clamped by layer.maxZoom and the optional
   * zoom overrides) are included. Tiles that don't exist on disk are skipped silently.
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

        if (bbox == null) {
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
   * that id exists; tile files overwrite any existing files on disk. Path traversal attempts are
   * rejected.
   */
  public ImportSummary importZip(InputStream in, Authentication auth) throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    Files.createDirectories(baseDir);
    List<String> added = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    long tilesWritten = 0L;
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
        }
      }
    }

    return new ImportSummary(added, skipped, tilesWritten);
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
