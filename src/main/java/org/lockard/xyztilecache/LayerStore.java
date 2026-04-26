package org.lockard.xyztilecache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LayerStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(LayerStore.class);
  private static final String LAYERS_FILE = "layers.json";
  private static final String LOCK_FILE = "layers.lock";

  private final XyzConfiguration configuration;
  private final TileWriter tileWriter;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;

  private Path jsonPath;
  private FileChannel lockChannel;
  private volatile FileTime lastKnownMtime = FileTime.fromMillis(0);

  public LayerStore(
      XyzConfiguration configuration,
      @Lazy TileWriter tileWriter,
      ObjectMapper objectMapper,
      ApplicationEventPublisher eventPublisher) {
    this.configuration = configuration;
    this.tileWriter = tileWriter;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
  }

  @PostConstruct
  void init() throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory());
    Files.createDirectories(baseDir);
    jsonPath = baseDir.resolve(LAYERS_FILE);
    lockChannel =
        FileChannel.open(
            baseDir.resolve(LOCK_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);

    try (FileLock lock = acquireLock()) {
      if (Files.exists(jsonPath)) {
        LOGGER.info("Loading layers from {}.", jsonPath);
        reloadFromFileUnlocked();
      } else {
        LOGGER.info("No layers.json found — writing current layers to {}.", jsonPath);
        writeLayersUnlocked();
        lastKnownMtime = Files.getLastModifiedTime(jsonPath);
      }
    }
  }

  @PreDestroy
  public void close() throws IOException {
    if (lockChannel != null && lockChannel.isOpen()) {
      lockChannel.close();
    }
  }

  // ── Cross-instance sync ───────────────────────────────────────────────────

  @Scheduled(fixedDelayString = "${xyz.layerSyncSeconds:10}000")
  void syncLayers() {
    if (jsonPath == null) return;
    try {
      FileTime current = Files.getLastModifiedTime(jsonPath);
      if (current.equals(lastKnownMtime)) return;

      try (FileLock lock = acquireLock()) {
        FileTime afterLock = Files.getLastModifiedTime(jsonPath);
        if (afterLock.equals(lastKnownMtime)) return;

        Map<String, Layer> before = new HashMap<>(configuration.getLayers());
        reloadFromFileUnlocked();

        before.forEach(
            (name, old) -> {
              Layer updated = configuration.getLayers().get(name);
              if (updated == null || layerChanged(old, updated)) {
                eventPublisher.publishEvent(new LayerChangedEvent(name));
              }
            });
        LOGGER.debug(
            "Synced layers from disk — {} layer(s) loaded.", configuration.getLayers().size());
      }
    } catch (IOException e) {
      LOGGER.error("Failed to sync layers from {}.", jsonPath, e);
    }
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  public synchronized void addLayer(Layer layer) throws IOException {
    validateLayer(layer);
    try (FileLock lock = acquireLock()) {
      reloadFromFileUnlocked();
      if (configuration.getLayers().containsKey(layer.getName())) {
        throw new IllegalArgumentException("Layer '" + layer.getName() + "' already exists.");
      }
      configuration.getLayers().put(layer.getName(), layer);
      writeLayersUnlocked();
    }
    LOGGER.info("Added layer '{}'.", layer.getName());
  }

  public synchronized void updateLayer(String name, Layer layer) throws IOException {
    validateLayer(layer);
    try (FileLock lock = acquireLock()) {
      reloadFromFileUnlocked();
      if (!configuration.getLayers().containsKey(name)) {
        throw new NoSuchElementException("Layer '" + name + "' not found.");
      }
      layer.setName(name);
      configuration.getLayers().put(name, layer);
      writeLayersUnlocked();
    }
    LOGGER.info("Updated layer '{}'.", name);
  }

  public synchronized void removeLayer(String name) throws IOException {
    try (FileLock lock = acquireLock()) {
      reloadFromFileUnlocked();
      if (!configuration.getLayers().containsKey(name)) {
        throw new NoSuchElementException("Layer '" + name + "' not found.");
      }
      configuration.getLayers().remove(name);
      tileWriter.deleteLayerDirectory(name);
      writeLayersUnlocked();
    }
    LOGGER.info("Removed layer '{}'.", name);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private FileLock acquireLock() throws IOException {
    return lockChannel.lock();
  }

  private void reloadFromFileUnlocked() throws IOException {
    List<Layer> layers = objectMapper.readValue(jsonPath.toFile(), new TypeReference<>() {});
    configuration.setLayers(layers);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void writeLayersUnlocked() throws IOException {
    List<Layer> layers = new ArrayList<>(configuration.getLayers().values());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), layers);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void validateLayer(Layer layer) {
    if (layer.getName() == null || layer.getName().isBlank()) {
      throw new IllegalArgumentException("Layer name must not be blank.");
    }
    if (layer.getUrlTemplate() == null || layer.getUrlTemplate().isBlank()) {
      throw new IllegalArgumentException("Layer urlTemplate must not be blank.");
    }
  }

  private boolean layerChanged(Layer a, Layer b) {
    return !Objects.equals(a.getUrlTemplate(), b.getUrlTemplate())
        || a.getSourceType() != b.getSourceType();
  }
}
