package org.lockard.xyztilecache.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Preload;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persists the preload list to {@code preloads.json} alongside {@code layers.json}. */
@Component
public class PreloadStore extends JsonFileStore<Preload> {

  private static final String PRELOADS_FILE = "preloads.json";
  private static final String LOCK_FILE = "preloads.lock";

  private final List<Preload> preloads = new ArrayList<>();

  public PreloadStore(XyzConfiguration configuration, ObjectMapper objectMapper) {
    super(configuration, objectMapper);
  }

  // ── Cross-instance sync ───────────────────────────────────────────────────

  @Scheduled(fixedDelayString = "${xyz.layerSyncSeconds:10}000")
  void syncPreloads() {
    syncIfChanged();
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  public void addPreload(Preload preload) throws IOException {
    if (preload.getId() == null || preload.getId().isBlank()) {
      throw new IllegalArgumentException("Preload id must not be blank.");
    }
    withLockedReloadAndWrite(
        () -> {
          if (preloads.stream().anyMatch(p -> preload.getId().equals(p.getId()))) {
            throw new IllegalArgumentException("Preload '" + preload.getId() + "' already exists.");
          }
          preloads.add(preload);
        });
    logger.info("Added preload '{}'.", preload.getId());
  }

  public void removePreload(String id) throws IOException {
    withLockedReloadAndWrite(
        () -> {
          boolean removed = preloads.removeIf(p -> id.equals(p.getId()));
          if (!removed) {
            throw new NoSuchElementException("Preload '" + id + "' not found.");
          }
        });
    logger.info("Removed preload '{}'.", id);
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

  // ── JsonFileStore hooks ───────────────────────────────────────────────────

  @Override
  protected String fileName() {
    return PRELOADS_FILE;
  }

  @Override
  protected String lockFileName() {
    return LOCK_FILE;
  }

  @Override
  protected TypeReference<List<Preload>> listTypeRef() {
    return new TypeReference<>() {};
  }

  @Override
  protected List<Preload> snapshot() {
    synchronized (preloads) {
      return new ArrayList<>(preloads);
    }
  }

  @Override
  protected void applyLoaded(List<Preload> loaded) {
    synchronized (preloads) {
      preloads.clear();
      preloads.addAll(loaded);
    }
  }
}
