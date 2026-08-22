package org.lockard.xyztilecache.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.TileResult;
import org.lockard.xyztilecache.pmtiles.PmtilesReader;
import org.lockard.xyztilecache.pmtiles.PmtilesTileCache;
import org.lockard.xyztilecache.pmtiles.PmtilesTileType;
import org.lockard.xyztilecache.pmtiles.RemotePmtilesReader;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class PmtilesManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesManager.class);

  private final LayerStore layerStore;
  private final XyzConfiguration xyzConfig;

  private final ConcurrentHashMap<String, List<PmtilesReader>> localReaders =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RemotePmtilesReader> remoteReaders =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, PmtilesTileCache> caches = new ConcurrentHashMap<>();

  /**
   * The tile type each layer settled on, taken from the first archive opened for it. A layer serves
   * one kind of tile: clients get a single content type and a single tile extension per layer, and
   * TileJSON and WMTS advertise one format. Archives that disagree are refused rather than mixed in
   * — put a raster archive in its own layer.
   */
  private final ConcurrentHashMap<String, PmtilesTileType> layerTileTypes =
      new ConcurrentHashMap<>();

  private final ExecutorService cacheWriter = Executors.newVirtualThreadPerTaskExecutor();

  private final TileInventoryStore inventory;

  public PmtilesManager(
      LayerStore layerStore, XyzConfiguration xyzConfig, TileInventoryStore inventory) {
    this.layerStore = layerStore;
    this.xyzConfig = xyzConfig;
    this.inventory = inventory;
  }

  @PostConstruct
  void init() {
    layerStore.getLayers().values().stream()
        .filter(l -> l.sourceType() == Layer.SourceType.PMTILES)
        .forEach(this::initLayer);
  }

  public void initLayer(Layer layer) {
    String layerId = layer.effectiveId();
    String source = layer.urlTemplate();

    Path layerDir = layerDir(layerId);
    List<Path> pmtilesFiles = new ArrayList<>(findAllPmtiles(layerDir));
    caches.put(
        layerId,
        new PmtilesTileCache(
            layerDir,
            xyzConfig,
            layerId,
            inventory,
            () -> tileType(layerId).orElse(PmtilesTileType.MVT)));

    if (source == null || source.isBlank()) {
      openLocalReaders(layerId, pmtilesFiles, null);
      LOGGER.warn("VECTOR_PMTILES layer '{}' has no urlTemplate; no reader opened", layerId);
      return;
    }

    if (source.startsWith("http://") || source.startsWith("https://")) {
      openLocalReaders(layerId, pmtilesFiles, null);
      openRemoteReader(layerId, source);
    } else {
      Path sourcePath = Path.of(source).toAbsolutePath().normalize();
      pmtilesFiles.removeIf(p -> p.toAbsolutePath().normalize().equals(sourcePath));
      // Kept last so downloaded extracts are consulted before the full archive, but passed as the
      // authoritative path: the layer's declared source decides the layer's tile type.
      pmtilesFiles.add(sourcePath);
      openLocalReaders(layerId, pmtilesFiles, sourcePath);
    }
  }

  public void closeLayer(String layerId) {
    List<PmtilesReader> locals = localReaders.remove(layerId);
    if (locals != null) locals.forEach(this::closeReaderSilently);
    remoteReaders.remove(layerId);
    caches.remove(layerId);
    // Re-resolved on the next open: the layer's archives may have changed.
    layerTileTypes.remove(layerId);
  }

  public Optional<TileResult> getTile(String layerId, int z, int x, int y) throws IOException {
    List<PmtilesReader> locals = localReaders.get(layerId);
    if (locals != null) {
      for (PmtilesReader local : locals) {
        Optional<TileResult> localResult = local.getTile(z, x, y);
        if (localResult.isPresent() && localResult.get().data().length > 0) {
          return localResult;
        }
      }
    }
    PmtilesTileCache cache = caches.get(layerId);
    if (cache != null) {
      Optional<TileResult> cached = cache.get(z, x, y);
      if (cached.isPresent()) return cached;
    }
    if (!xyzConfig.isOffline()) {
      RemotePmtilesReader remote = remoteReaders.get(layerId);
      if (remote != null) {
        Optional<TileResult> result = remote.getTile(z, x, y);
        if (result.isPresent() && cache != null) {
          PmtilesTileCache target = cache;
          TileResult tile = result.get();
          CompletableFuture.runAsync(() -> target.store(z, x, y, tile), cacheWriter);
        }
        return result;
      }
    }

    return Optional.empty();
  }

  public void notifyFileAvailable(Path filePath) {
    String pathStr = filePath.toAbsolutePath().normalize().toString();
    layerStore.getLayers().values().stream()
        .filter(l -> l.sourceType() == Layer.SourceType.PMTILES)
        .filter(l -> pathStr.equals(l.urlTemplate()))
        .forEach(
            l -> {
              closeLayer(l.effectiveId());
              initLayer(l);
            });

    Path parent = filePath.getParent();
    if (parent != null) {
      String layerId = parent.getFileName().toString();
      Layer layer = layerStore.getLayers().get(layerId);
      if (layer != null && layer.sourceType() == Layer.SourceType.PMTILES) {
        closeLayer(layerId);
        initLayer(layer);
      }
    }
  }

  @EventListener
  void onLayerChanged(LayerChangedEvent event) {
    if (event.kind() == LayerChangedEvent.Kind.UPDATED_ACL) {
      return;
    }
    String layerId = event.layerName();
    closeLayer(layerId);
    Layer layer = layerStore.getLayers().get(layerId);
    if (layer != null && layer.sourceType() == Layer.SourceType.PMTILES) {
      initLayer(layer);
    }
  }

  @PreDestroy
  void destroy() {
    cacheWriter.shutdown();
    try {
      if (!cacheWriter.awaitTermination(5, TimeUnit.SECONDS)) {
        cacheWriter.shutdownNow();
      }
    } catch (InterruptedException e) {
      cacheWriter.shutdownNow();
      Thread.currentThread().interrupt();
    }
    localReaders.values().forEach(list -> list.forEach(this::closeReaderSilently));
    localReaders.clear();
    remoteReaders.clear();
    caches.clear();
  }

  /**
   * Opens every readable archive, settles the layer's tile type, and drops the ones that disagree.
   *
   * <p>{@code authoritative} is the layer's configured source archive when it is a local file. Its
   * type wins, so a stray archive left in the layer directory cannot redefine what the layer
   * serves. Without one — a remote-backed layer, or a layer with no source — the first archive
   * opened settles it.
   */
  private void openLocalReaders(String layerId, List<Path> paths, Path authoritative) {
    List<PmtilesReader> opened = new ArrayList<>();
    for (Path path : paths) {
      try {
        opened.add(new PmtilesReader(path));
      } catch (IOException | IllegalArgumentException e) {
        LOGGER.warn(
            "Could not open PMTiles for layer '{}' at {}: {}", layerId, path, e.getMessage());
      }
    }
    if (opened.isEmpty()) {
      return;
    }

    PmtilesTileType layerType = settleTileType(layerId, opened, authoritative);

    List<PmtilesReader> kept = new ArrayList<>();
    for (PmtilesReader reader : opened) {
      if (reader.tileType() == layerType) {
        kept.add(reader);
        LOGGER.info(
            "Opened local PMTiles reader for layer '{}' ({} tiles): {}",
            layerId,
            reader.tileType(),
            reader.getLocalFile());
      } else {
        LOGGER.error(
            "Refusing PMTiles archive {} for layer '{}': it holds {} tiles but the layer serves"
                + " {}. A layer carries one tile type; give this archive its own layer.",
            reader.getLocalFile(),
            layerId,
            reader.tileType(),
            layerType);
        closeReaderSilently(reader);
      }
    }
    if (!kept.isEmpty()) {
      localReaders.put(layerId, kept);
    }
  }

  /**
   * The type the layer will serve: the configured source's if it opened, else the first archive's.
   */
  private PmtilesTileType settleTileType(
      String layerId, List<PmtilesReader> opened, Path authoritative) {
    PmtilesTileType layerType =
        opened.stream()
            .filter(r -> authoritative != null && sameFile(r.getLocalFile(), authoritative))
            .findFirst()
            .orElse(opened.get(0))
            .tileType();
    layerTileTypes.put(layerId, layerType);
    return layerType;
  }

  private static boolean sameFile(Path a, Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  /**
   * The tile type this layer serves, if known. Local archives settle it when they open; a remote
   * archive only once its header has been fetched. Empty means nothing has reported yet, and
   * callers should fall back rather than guess.
   */
  public Optional<PmtilesTileType> tileType(String layerId) {
    PmtilesTileType known = layerTileTypes.get(layerId);
    if (known != null) {
      return Optional.of(known);
    }
    RemotePmtilesReader remote = remoteReaders.get(layerId);
    if (remote == null) {
      return Optional.empty();
    }
    Optional<PmtilesTileType> remoteType = remote.tileType();
    // Remember it so later callers skip the lookup, and so a downloaded extract from this same
    // source is checked against it.
    remoteType.ifPresent(type -> layerTileTypes.putIfAbsent(layerId, type));
    return remoteType;
  }

  /** Extension for individually cached tiles of {@code layerId}, defaulting to vector. */
  public String cachedTileExtension(String layerId) {
    return tileType(layerId).orElse(PmtilesTileType.MVT).extension();
  }

  private void openRemoteReader(String layerId, String sourceUrl) {
    String resolvedUrl = PmtilesDownloader.resolveSourceUrl(sourceUrl);
    if (resolvedUrl == null || resolvedUrl.isBlank()) return;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.of(xyzConfig.getTileTimeoutSeconds(), ChronoUnit.SECONDS))
            .build();
    RemotePmtilesReader reader =
        new RemotePmtilesReader(resolvedUrl, httpClient, xyzConfig.getTileTimeoutSeconds());
    remoteReaders.put(layerId, reader);
    LOGGER.info("Opened remote PMTiles reader for layer '{}': {}", layerId, resolvedUrl);
  }

  private List<Path> findAllPmtiles(Path dir) {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.toString().endsWith(".pmtiles")).collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }

  private Path layerDir(String layerId) {
    return Path.of(xyzConfig.getBaseTileDirectory(), layerId);
  }

  private void closeReaderSilently(PmtilesReader reader) {
    try {
      reader.close();
    } catch (IOException e) {
      LOGGER.debug("Error closing PmtilesReader", e);
    }
  }
}
