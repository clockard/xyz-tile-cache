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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * <layerId>/<z>/<x>/<y>.png} entries, matching the on-disk format. Vector tile data is exported
 * under {@code vector/tiles/<z>/<x>/<y>.pbf} and {@code vector/pmtiles/<name>.pmtiles}.
 */
@Service
public class ImportExportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportExportService.class);
  private static final Pattern SAFE_LAYER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern TILE_TAIL = Pattern.compile("\\d+/\\d+/\\d+\\.png");
  private static final Pattern VECTOR_TILE_TAIL = Pattern.compile("\\d+/\\d+/\\d+\\.pbf");
  private static final Pattern SAFE_PMTILES_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.pmtiles");

  private final XyzConfiguration configuration;
  private final VectorConfiguration vectorConfiguration;
  private final VectorTileService vectorTileService;
  private final LayerStore layerStore;
  private final ObjectMapper objectMapper;
  private final LayerAccessService layerAccessService;

  public ImportExportService(
      XyzConfiguration configuration,
      VectorConfiguration vectorConfiguration,
      VectorTileService vectorTileService,
      LayerStore layerStore,
      ObjectMapper objectMapper,
      LayerAccessService layerAccessService) {
    this.configuration = configuration;
    this.vectorConfiguration = vectorConfiguration;
    this.vectorTileService = vectorTileService;
    this.layerStore = layerStore;
    this.objectMapper = objectMapper;
    this.layerAccessService = layerAccessService;
  }

  /**
   * Writes a zip containing each layer's {@code layer.json} and tile files. If {@code
   * includeVector} is true, also exports cached individual vector PBF tiles and downloaded PMTiles
   * files. If {@code bbox} is null, every cached tile under the layer directory is included;
   * otherwise only tiles within the bbox are included.
   */
  public void streamExport(
      List<Layer> layers,
      BoundingBox bbox,
      Integer minZoom,
      Integer maxZoom,
      boolean includeVector,
      OutputStream out)
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

      if (includeVector) {
        addVectorData(zos, bbox, minZoom, maxZoom);
      }
    }
  }

  private void addVectorData(
      ZipOutputStream zos, BoundingBox bbox, Integer minZoom, Integer maxZoom) throws IOException {
    String downloadDirStr = vectorConfiguration.getDownloadDirectory();
    if (downloadDirStr == null || downloadDirStr.isBlank()) {
      return;
    }
    Path downloadDir = Path.of(downloadDirStr).toAbsolutePath().normalize();
    if (!Files.isDirectory(downloadDir)) {
      return;
    }

    Set<String> writtenTileEntries = new HashSet<>();
    Path remoteCacheDir = downloadDir.resolve("remote-cache");
    if (Files.isDirectory(remoteCacheDir)) {
      Map<Integer, Set<Point>> bboxTilesByZoom = new HashMap<>();
      try (Stream<Path> files = Files.walk(remoteCacheDir)) {
        files
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".pbf"))
            .forEach(
                p -> {
                  try {
                    Path rel = remoteCacheDir.relativize(p);
                    if (rel.getNameCount() != 3) {
                      return;
                    }
                    int z, x, y;
                    try {
                      z = Integer.parseInt(rel.getName(0).toString());
                      x = Integer.parseInt(rel.getName(1).toString());
                      y = Integer.parseInt(rel.getName(2).toString().replace(".pbf", ""));
                    } catch (NumberFormatException e) {
                      return;
                    }
                    if (minZoom != null && z < minZoom) {
                      return;
                    }
                    if (maxZoom != null && z > maxZoom) {
                      return;
                    }
                    if (bbox != null) {
                      Set<Point> pts =
                          bboxTilesByZoom.computeIfAbsent(
                              z, lvl -> XyzUtil.calculateXyTilesForBBox(bbox, lvl));
                      if (!pts.contains(new Point(x, y))) {
                        return;
                      }
                    }
                    String entryName = String.format("vector/tiles/%d/%d/%d.pbf", z, x, y);
                    writeEntry(zos, entryName, p);
                    writtenTileEntries.add(entryName);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }

    List<Path> pmtilesFiles;
    try (Stream<Path> stream = Files.list(downloadDir)) {
      pmtilesFiles = stream.filter(p -> p.getFileName().toString().endsWith(".pmtiles")).toList();
    }
    for (Path p : pmtilesFiles) {
      if (bbox == null) {
        writeEntry(zos, "vector/pmtiles/" + p.getFileName().toString(), p);
      } else {
        addPmtilesInBbox(zos, p, bbox, minZoom, maxZoom, writtenTileEntries);
      }
    }
  }

  private void addPmtilesInBbox(
      ZipOutputStream zos,
      Path pmtilesPath,
      BoundingBox bbox,
      Integer minZoom,
      Integer maxZoom,
      Set<String> writtenTileEntries)
      throws IOException {
    PmtilesReader reader;
    try {
      reader = new PmtilesReader(pmtilesPath);
    } catch (IOException | IllegalArgumentException e) {
      LOGGER.warn(
          "Skipping unreadable PMTiles file {}: {}", pmtilesPath.getFileName(), e.getMessage());
      return;
    }
    try (reader) {
      PmtilesHeader header = reader.getHeader();

      if (header.maxLon() < bbox.getWest()
          || header.minLon() > bbox.getEast()
          || header.maxLat() < bbox.getSouth()
          || header.minLat() > bbox.getNorth()) {
        return;
      }

      if (header.minLon() >= bbox.getWest()
          && header.maxLon() <= bbox.getEast()
          && header.minLat() >= bbox.getSouth()
          && header.maxLat() <= bbox.getNorth()) {
        writeEntry(zos, "vector/pmtiles/" + pmtilesPath.getFileName().toString(), pmtilesPath);
        return;
      }

      int zStart = Math.max(header.minZoom(), minZoom != null ? minZoom : 0);
      int zEnd = Math.min(header.maxZoom(), bbox.getMaxZoom());
      if (maxZoom != null) {
        zEnd = Math.min(zEnd, maxZoom);
      }
      for (int z = zStart; z <= zEnd; z++) {
        Set<Point> tiles = XyzUtil.calculateXyTilesForBBox(bbox, z);
        for (Point pt : tiles) {
          Optional<TileResult> tile = reader.getTile(z, pt.x, pt.y);
          if (tile.isPresent()) {
            String entryName = String.format("vector/tiles/%d/%d/%d.pbf", z, pt.x, pt.y);
            if (writtenTileEntries.add(entryName)) {
              zos.putNextEntry(new ZipEntry(entryName));
              zos.write(tile.get().data());
              zos.closeEntry();
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
   * that id exists; tile files overwrite any existing files on disk. Vector tile entries under
   * {@code vector/tiles/} and {@code vector/pmtiles/} are restored to the vector download
   * directory. Path traversal attempts are rejected.
   */
  public ImportSummary importZip(InputStream in, Authentication auth) throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    Files.createDirectories(baseDir);

    String downloadDirStr = vectorConfiguration.getDownloadDirectory();
    Path vectorDownloadDir =
        (downloadDirStr != null && !downloadDirStr.isBlank())
            ? Path.of(downloadDirStr).toAbsolutePath().normalize()
            : null;

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

        // Handle vector tile entries
        if (name.startsWith("vector/tiles/") || name.startsWith("vector/pmtiles/")) {
          if (!layerAccessService.isAdmin(auth)) {
            throw new AccessDeniedException("Admin access required to import vector tiles");
          }
          if (vectorDownloadDir == null) {
            LOGGER.warn(
                "Skipping vector entry {}: xyz.vector.downloadDirectory not configured", name);
            continue;
          }
          if (name.startsWith("vector/tiles/")) {
            String tail = name.substring("vector/tiles/".length());
            if (!VECTOR_TILE_TAIL.matcher(tail).matches()) {
              LOGGER.warn("Skipping vector tile entry with unexpected path: {}", name);
              continue;
            }
            String[] parts = tail.replace(".pbf", "").split("/");
            int z = Integer.parseInt(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            Path cacheDir =
                vectorDownloadDir
                    .resolve("remote-cache")
                    .resolve(String.valueOf(z))
                    .resolve(String.valueOf(x));
            Files.createDirectories(cacheDir);
            Path tilePath = cacheDir.resolve(y + ".pbf").normalize();
            if (!tilePath.startsWith(vectorDownloadDir)) {
              LOGGER.warn("Skipping vector tile entry that escapes download directory: {}", name);
              continue;
            }
            Files.copy(zis, tilePath, StandardCopyOption.REPLACE_EXISTING);
            tilesWritten++;
          } else {
            String filename = name.substring("vector/pmtiles/".length());
            if (!SAFE_PMTILES_NAME.matcher(filename).matches()) {
              LOGGER.warn("Skipping PMTiles entry with unsafe name: {}", filename);
              continue;
            }
            Files.createDirectories(vectorDownloadDir);
            Path target = vectorDownloadDir.resolve(filename).normalize();
            if (!target.startsWith(vectorDownloadDir)) {
              LOGGER.warn("Skipping PMTiles entry that escapes download directory: {}", filename);
              continue;
            }
            Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            try {
              vectorTileService.registerDownload(target);
              pmtilesImported++;
            } catch (IOException e) {
              LOGGER.warn("Imported PMTiles file could not be opened: {}", e.getMessage());
            }
          }
          continue;
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
