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
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists the preload list to {@code preloads.json} alongside {@code layers.json} and keeps it in
 * sync across instances sharing the same tile directory. All mutations are serialized by an
 * OS-level file lock; in-process callers are serialized by the same lock since {@link
 * FileChannel#lock()} blocks across threads.
 */
@Component
public class PreloadStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(PreloadStore.class);
  private static final String PRELOADS_FILE = "preloads.json";
  private static final String LOCK_FILE = "preloads.lock";

  private final XyzConfiguration configuration;
  private final ObjectMapper objectMapper;

  private Path jsonPath;
  private FileChannel lockChannel;
  private final List<Preload> preloads = new ArrayList<>();
  private volatile FileTime lastKnownMtime = FileTime.fromMillis(0);

  public PreloadStore(XyzConfiguration configuration, ObjectMapper objectMapper) {
    this.configuration = configuration;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void init() throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory());
    Files.createDirectories(baseDir);
    jsonPath = baseDir.resolve(PRELOADS_FILE);
    lockChannel =
        FileChannel.open(
            baseDir.resolve(LOCK_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);

    try (FileLock ignored = lockChannel.lock()) {
      if (Files.exists(jsonPath)) {
        LOGGER.info("Loading preloads from {}.", jsonPath);
        loadFromFile();
      } else {
        LOGGER.info("No preloads.json found — initializing empty list at {}.", jsonPath);
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
  void syncPreloads() {
    if (jsonPath == null) return;
    try {
      if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;
      try (FileLock ignored = lockChannel.lock()) {
        if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;
        loadFromFile();
      }
    } catch (IOException e) {
      LOGGER.error("Failed to sync preloads from {}.", jsonPath, e);
    }
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  public void addPreload(Preload preload) throws IOException {
    if (preload.getId() == null || preload.getId().isBlank()) {
      throw new IllegalArgumentException("Preload id must not be blank.");
    }
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      if (preloads.stream().anyMatch(p -> preload.getId().equals(p.getId()))) {
        throw new IllegalArgumentException("Preload '" + preload.getId() + "' already exists.");
      }
      preloads.add(preload);
      writeFile();
    }
    LOGGER.info("Added preload '{}'.", preload.getId());
  }

  public void removePreload(String id) throws IOException {
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      boolean removed = preloads.removeIf(p -> id.equals(p.getId()));
      if (!removed) {
        throw new NoSuchElementException("Preload '" + id + "' not found.");
      }
      writeFile();
    }
    LOGGER.info("Removed preload '{}'.", id);
  }

  public List<Preload> listPreloads() {
    synchronized (preloads) {
      return Collections.unmodifiableList(new ArrayList<>(preloads));
    }
  }

  public Optional<Preload> findById(String id) {
    synchronized (preloads) {
      return preloads.stream().filter(p -> id.equals(p.getId())).findFirst();
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void loadFromFile() throws IOException {
    List<Preload> loaded = objectMapper.readValue(jsonPath.toFile(), new TypeReference<>() {});
    synchronized (preloads) {
      preloads.clear();
      preloads.addAll(loaded);
    }
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void writeFile() throws IOException {
    List<Preload> snapshot;
    synchronized (preloads) {
      snapshot = new ArrayList<>(preloads);
    }
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), snapshot);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }
}
