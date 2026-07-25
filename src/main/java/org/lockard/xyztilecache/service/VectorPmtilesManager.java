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
import org.lockard.xyztilecache.pmtiles.RemotePmtilesReader;
import org.lockard.xyztilecache.pmtiles.VectorTileCache;
import org.lockard.xyztilecache.store.LayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class VectorPmtilesManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorPmtilesManager.class);

  private final LayerStore layerStore;
  private final XyzConfiguration xyzConfig;

  private final ConcurrentHashMap<String, List<PmtilesReader>> localReaders =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RemotePmtilesReader> remoteReaders =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, VectorTileCache> caches = new ConcurrentHashMap<>();

  private final ExecutorService cacheWriter = Executors.newVirtualThreadPerTaskExecutor();

  public VectorPmtilesManager(LayerStore layerStore, XyzConfiguration xyzConfig) {
    this.layerStore = layerStore;
    this.xyzConfig = xyzConfig;
  }

  @PostConstruct
  void init() {
    layerStore.getLayers().values().stream()
        .filter(l -> l.sourceType() == Layer.SourceType.VECTOR_PMTILES)
        .forEach(this::initLayer);
  }

  public void initLayer(Layer layer) {
    String layerId = layer.effectiveId();
    String source = layer.urlTemplate();

    Path layerDir = layerDir(layerId);
    List<Path> pmtilesFiles = new ArrayList<>(findAllPmtiles(layerDir));
    caches.put(layerId, new VectorTileCache(layerDir, xyzConfig));

    if (source == null || source.isBlank()) {
      openLocalReaders(layerId, pmtilesFiles);
      LOGGER.warn("VECTOR_PMTILES layer '{}' has no urlTemplate; no reader opened", layerId);
      return;
    }

    if (source.startsWith("http://") || source.startsWith("https://")) {
      openLocalReaders(layerId, pmtilesFiles);
      openRemoteReader(layerId, source);
    } else {
      Path sourcePath = Path.of(source).toAbsolutePath().normalize();
      pmtilesFiles.removeIf(p -> p.toAbsolutePath().normalize().equals(sourcePath));
      pmtilesFiles.add(sourcePath);
      openLocalReaders(layerId, pmtilesFiles);
    }
  }

  public void closeLayer(String layerId) {
    List<PmtilesReader> locals = localReaders.remove(layerId);
    if (locals != null) locals.forEach(this::closeReaderSilently);
    remoteReaders.remove(layerId);
    caches.remove(layerId);
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
    VectorTileCache cache = caches.get(layerId);
    if (cache != null) {
      Optional<TileResult> cached = cache.get(z, x, y);
      if (cached.isPresent()) return cached;
    }
    if (!xyzConfig.isOffline()) {
      RemotePmtilesReader remote = remoteReaders.get(layerId);
      if (remote != null) {
        Optional<TileResult> result = remote.getTile(z, x, y);
        if (result.isPresent() && cache != null) {
          VectorTileCache target = cache;
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
        .filter(l -> l.sourceType() == Layer.SourceType.VECTOR_PMTILES)
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
      if (layer != null && layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) {
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
    if (layer != null && layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) {
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

  private void openLocalReaders(String layerId, List<Path> paths) {
    List<PmtilesReader> readers = new ArrayList<>();
    for (Path path : paths) {
      try {
        readers.add(new PmtilesReader(path));
        LOGGER.info("Opened local PMTiles reader for layer '{}': {}", layerId, path);
      } catch (IOException | IllegalArgumentException e) {
        LOGGER.warn(
            "Could not open PMTiles for layer '{}' at {}: {}", layerId, path, e.getMessage());
      }
    }
    if (!readers.isEmpty()) {
      localReaders.put(layerId, readers);
    }
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
