package org.lockard.xyztilecache.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persists the layer list to {@code layers.json} alongside the tile cache. */
@Component
public class LayerStore extends JsonFileStore<Layer> {

  private static final String LAYERS_FILE = "layers.json";
  private static final String LOCK_FILE = "layers.lock";

  private final XyzConfiguration configuration;
  private final ApplicationEventPublisher eventPublisher;

  private final Map<String, Layer> layers = new ConcurrentHashMap<>();
  private final Map<String, LayerRuntimeState> runtimeStates = new ConcurrentHashMap<>();

  public LayerStore(
      XyzConfiguration configuration,
      ObjectMapper objectMapper,
      ApplicationEventPublisher eventPublisher) {
    super(configuration, objectMapper);
    this.configuration = configuration;
    this.eventPublisher = eventPublisher;
  }

  // ── Cross-instance sync ───────────────────────────────────────────────────

  @Scheduled(fixedDelayString = "${xyz.layerSyncSeconds:10}000")
  public void syncLayers() {
    syncIfChanged();
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
    String id = layer.effectiveId();
    withLockedReloadAndWrite(
        () -> {
          if (this.layers.containsKey(id)) {
            throw new LayerAlreadyExistsException("Layer '" + id + "' already exists.");
          }
          this.layers.put(id, layer);
        });
    logger.info("Added layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id, LayerChangedEvent.Kind.ADDED));
  }

  public void updateLayer(String id, Layer layer) throws IOException {
    Layer reidentified = layer.withId(id);
    validateLayer(reidentified);
    LayerChangedEvent.Kind[] kindHolder = new LayerChangedEvent.Kind[1];
    withLockedReloadAndWrite(
        () -> {
          Layer existing = this.layers.get(id);
          if (existing == null) {
            throw new NoSuchElementException("Layer '" + id + "' not found.");
          }
          this.layers.put(id, reidentified);
          kindHolder[0] =
              sameSource(existing, reidentified)
                  ? LayerChangedEvent.Kind.UPDATED_ACL
                  : LayerChangedEvent.Kind.UPDATED_SOURCE;
        });
    logger.info("Updated layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id, kindHolder[0]));
  }

  public void removeLayer(String id) throws IOException {
    withLockedReloadAndWrite(
        () -> {
          if (this.layers.remove(id) == null) {
            throw new NoSuchElementException("Layer '" + id + "' not found.");
          }
          this.runtimeStates.remove(id);
        });
    logger.info("Removed layer '{}'.", id);
    eventPublisher.publishEvent(new LayerChangedEvent(id, LayerChangedEvent.Kind.REMOVED));
  }

  // ── JsonFileStore hooks ───────────────────────────────────────────────────

  @Override
  protected String fileName() {
    return LAYERS_FILE;
  }

  @Override
  protected String lockFileName() {
    return LOCK_FILE;
  }

  @Override
  protected TypeReference<List<Layer>> listTypeRef() {
    return new TypeReference<>() {};
  }

  @Override
  protected List<Layer> snapshot() {
    return new ArrayList<>(this.layers.values());
  }

  @Override
  protected void applyLoaded(List<Layer> loaded) {
    this.layers.clear();
    loaded.forEach(l -> this.layers.put(l.effectiveId(), l));
    this.runtimeStates.keySet().retainAll(this.layers.keySet());
  }

  @Override
  protected void seed() {
    this.layers.putAll(configuration.getLayers());
  }

  @Override
  protected void onReloaded(List<Layer> previousSnapshot) {
    Map<String, Layer> before = new HashMap<>();
    previousSnapshot.forEach(l -> before.put(l.effectiveId(), l));
    before.forEach(
        (name, old) -> {
          Layer updated = this.layers.get(name);
          if (updated == null) {
            eventPublisher.publishEvent(
                new LayerChangedEvent(name, LayerChangedEvent.Kind.REMOVED));
          } else if (!sameSource(old, updated)) {
            eventPublisher.publishEvent(
                new LayerChangedEvent(name, LayerChangedEvent.Kind.UPDATED_SOURCE));
          }
        });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static boolean sameSource(Layer a, Layer b) {
    return Objects.equals(a.urlTemplate(), b.urlTemplate()) && a.sourceType() == b.sourceType();
  }

  private static void validateLayer(Layer layer) {
    if (layer.effectiveId() == null || layer.effectiveId().isBlank()) {
      throw new IllegalArgumentException("Layer id must not be blank.");
    }
    if (layer.sourceType() != Layer.SourceType.LOCAL
        && layer.sourceType() != Layer.SourceType.VECTOR_PMTILES
        && (layer.urlTemplate() == null || layer.urlTemplate().isBlank())) {
      throw new IllegalArgumentException("Layer urlTemplate must not be blank.");
    }
  }
}
