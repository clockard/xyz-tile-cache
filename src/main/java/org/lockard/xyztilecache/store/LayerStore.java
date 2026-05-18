package org.lockard.xyztilecache.store;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists the layer list to {@code layers.json} and keeps it in sync across instances sharing the
 * same tile directory. All mutations are serialized by an OS-level file lock; in-process callers
 * are serialized by the same lock since {@link FileChannel#lock()} blocks across threads.
 */
@Component
public class LayerStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(LayerStore.class);
  private static final String LAYERS_FILE = "layers.json";
  private static final String LOCK_FILE = "layers.lock";

  private final XyzConfiguration configuration;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;

  private final Map<String, Layer> layers = new ConcurrentHashMap<>();
  private final Map<String, LayerRuntimeState> runtimeStates = new ConcurrentHashMap<>();

  private Path jsonPath;
  private FileChannel lockChannel;
  private volatile FileTime lastKnownMtime = FileTime.fromMillis(0);

  public LayerStore(
      XyzConfiguration configuration,
      ObjectMapper objectMapper,
      ApplicationEventPublisher eventPublisher) {
    this.configuration = configuration;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
  }

  @PostConstruct
  public void init() throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory());
    Files.createDirectories(baseDir);
    jsonPath = baseDir.resolve(LAYERS_FILE);
    lockChannel =
        FileChannel.open(
            baseDir.resolve(LOCK_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);

    try (FileLock ignored = lockChannel.lock()) {
      if (Files.exists(jsonPath)) {
        LOGGER.info("Loading layers from {}.", jsonPath);
        loadFromFile();
      } else {
        LOGGER.info("No layers.json found — writing seeded layers to {}.", jsonPath);
        this.layers.putAll(configuration.getLayers());
        writeFile();
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
  public void syncLayers() {
    if (jsonPath == null) return;
    try {
      if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;

      try (FileLock ignored = lockChannel.lock()) {
        if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;

        Map<String, Layer> before = new HashMap<>(this.layers);
        loadFromFile();
        publishChanges(before, this.layers);
      }
    } catch (IOException e) {
      LOGGER.error("Failed to sync layers from {}.", jsonPath, e);
    }
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  public Map<String, Layer> getLayers() {
    return layers;
  }

  public LayerRuntimeState getRuntimeState(String id) {
    return runtimeStates.computeIfAbsent(id, k -> new LayerRuntimeState());
  }

  public Optional<Layer> getLayer(String id) {
    if (id == null) return Optional.empty();
    return Optional.ofNullable(this.layers.get(id));
  }

  public void addLayer(Layer layer) throws IOException {
    validateLayer(layer);
    String id = layer.getEffectiveId();
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (this.layers.containsKey(id)) {
        throw new IllegalArgumentException("Layer '" + id + "' already exists.");
      }
      this.layers.put(id, layer);
      writeFile();
    }
    LOGGER.info("Added layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id));
  }

  public void updateLayer(String id, Layer layer) throws IOException {
    validateLayer(layer);
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (!this.layers.containsKey(id)) {
        throw new NoSuchElementException("Layer '" + id + "' not found.");
      }
      layer.setId(id);
      this.layers.put(id, layer);
      writeFile();
    }
    LOGGER.info("Updated layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id));
  }

  public void removeLayer(String id) throws IOException {
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (this.layers.remove(id) == null) {
        throw new NoSuchElementException("Layer '" + id + "' not found.");
      }
      this.runtimeStates.remove(id);
      writeFile();
    }
    LOGGER.info("Removed layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void loadFromFile() throws IOException {
    List<Layer> loaded = objectMapper.readValue(jsonPath.toFile(), new TypeReference<>() {});
    this.layers.clear();
    loaded.forEach(l -> this.layers.put(l.getEffectiveId(), l));
    this.runtimeStates.keySet().retainAll(this.layers.keySet());
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void writeFile() throws IOException {
    List<Layer> layerList = new ArrayList<>(this.layers.values());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), layerList);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void publishChanges(Map<String, Layer> before, Map<String, Layer> after) {
    before.forEach(
        (name, old) -> {
          Layer updated = after.get(name);
          if (updated == null || !sameSource(old, updated)) {
            eventPublisher.publishEvent(new LayerChangedEvent(name));
          }
        });
  }

  private static boolean sameSource(Layer a, Layer b) {
    return Objects.equals(a.getUrlTemplate(), b.getUrlTemplate())
        && a.getSourceType() == b.getSourceType();
  }

  private static void validateLayer(Layer layer) {
    if (layer.getEffectiveId() == null || layer.getEffectiveId().isBlank()) {
      throw new IllegalArgumentException("Layer id must not be blank.");
    }
    if (layer.getSourceType() != Layer.SourceType.LOCAL
        && (layer.getUrlTemplate() == null || layer.getUrlTemplate().isBlank())) {
      throw new IllegalArgumentException("Layer urlTemplate must not be blank.");
    }
  }
}
