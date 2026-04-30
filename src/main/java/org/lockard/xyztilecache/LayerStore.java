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

    try (FileLock ignored = lockChannel.lock()) {
      if (Files.exists(jsonPath)) {
        LOGGER.info("Loading layers from {}.", jsonPath);
        loadFromFile();
      } else {
        LOGGER.info("No layers.json found — writing seeded layers to {}.", jsonPath);
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
  void syncLayers() {
    if (jsonPath == null) return;
    try {
      if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;

      try (FileLock ignored = lockChannel.lock()) {
        if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;

        Map<String, Layer> before = new HashMap<>(configuration.getLayers());
        loadFromFile();
        publishChanges(before, configuration.getLayers());
      }
    } catch (IOException e) {
      LOGGER.error("Failed to sync layers from {}.", jsonPath, e);
    }
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  public void addLayer(Layer layer) throws IOException {
    validateLayer(layer);
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (configuration.getLayers().containsKey(layer.getName())) {
        throw new IllegalArgumentException("Layer '" + layer.getName() + "' already exists.");
      }
      configuration.getLayers().put(layer.getName(), layer);
      writeFile();
    }
    LOGGER.info("Added layer '{}'.", layer.getName());
    eventPublisher.publishEvent(new LayerChangedEvent(layer.getName()));
  }

  public void updateLayer(String name, Layer layer) throws IOException {
    validateLayer(layer);
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (!configuration.getLayers().containsKey(name)) {
        throw new NoSuchElementException("Layer '" + name + "' not found.");
      }
      layer.setName(name);
      configuration.getLayers().put(name, layer);
      writeFile();
    }
    LOGGER.info("Updated layer '{}'.", name);
    eventPublisher.publishEvent(new LayerChangedEvent(name));
  }

  public void removeLayer(String name) throws IOException {
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (configuration.getLayers().remove(name) == null) {
        throw new NoSuchElementException("Layer '" + name + "' not found.");
      }
      writeFile();
    }
    LOGGER.info("Removed layer '{}'.", name);
    eventPublisher.publishEvent(new LayerChangedEvent(name));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void loadFromFile() throws IOException {
    List<Layer> layers = objectMapper.readValue(jsonPath.toFile(), new TypeReference<>() {});
    configuration.setLayers(layers);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void writeFile() throws IOException {
    List<Layer> layers = new ArrayList<>(configuration.getLayers().values());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), layers);
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
    if (layer.getName() == null || layer.getName().isBlank()) {
      throw new IllegalArgumentException("Layer name must not be blank.");
    }
    if (layer.getUrlTemplate() == null || layer.getUrlTemplate().isBlank()) {
      throw new IllegalArgumentException("Layer urlTemplate must not be blank.");
    }
  }
}
