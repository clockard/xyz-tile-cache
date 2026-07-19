package org.lockard.xyztilecache.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.lockard.xyztilecache.store.LayerStore;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Per-layer cache and circuit-breaker gauges, exported through Micrometer.
 *
 * <p>All gauges read from {@link LayerRuntimeState} AtomicLongs that are already maintained on the
 * write path — Prometheus scrapes are O(1), independent of how many tile files exist on disk.
 */
@Component
@DependsOn("layerStore")
public class TileCacheMetrics {

  static final String CACHED_TILES = "xyz_layer_cached_tiles";
  static final String CACHED_BYTES = "xyz_layer_cached_bytes";
  static final String TILES_SERVED = "xyz_layer_tiles_served";
  static final String BREAKER_STATE = "xyz_layer_breaker_state";
  static final String LAYER_TAG = "layer";

  private final MeterRegistry registry;
  private final LayerStore layerStore;
  private final Map<String, List<Meter.Id>> perLayerMeters = new ConcurrentHashMap<>();

  public TileCacheMetrics(MeterRegistry registry, LayerStore layerStore) {
    this.registry = registry;
    this.layerStore = layerStore;
  }

  @PostConstruct
  void registerExistingLayers() {
    layerStore.getLayers().keySet().forEach(this::registerLayer);
  }

  @EventListener
  void onLayerChanged(LayerChangedEvent event) {
    switch (event.kind()) {
      case ADDED -> registerLayer(event.layerName());
      case REMOVED -> unregisterLayer(event.layerName());
      default -> {
        // UPDATED_SOURCE / UPDATED_ACL: meters keep referencing the same LayerRuntimeState.
      }
    }
  }

  private void registerLayer(String layerId) {
    perLayerMeters.computeIfAbsent(
        layerId,
        id -> {
          LayerRuntimeState state = layerStore.getRuntimeState(id);
          List<Meter.Id> ids = new ArrayList<>(4);
          ids.add(
              Gauge.builder(CACHED_TILES, state, LayerRuntimeState::getCachedTiles)
                  .description("Number of tiles cached on disk for this layer.")
                  .tag(LAYER_TAG, id)
                  .register(registry)
                  .getId());
          ids.add(
              Gauge.builder(CACHED_BYTES, state, LayerRuntimeState::getCachedTilesSize)
                  .description("Total bytes occupied on disk by cached tiles for this layer.")
                  .baseUnit("bytes")
                  .tag(LAYER_TAG, id)
                  .register(registry)
                  .getId());
          ids.add(
              Gauge.builder(TILES_SERVED, state, s -> (double) s.getTilesServed())
                  .description("Cumulative tile-response count for this layer since startup.")
                  .tag(LAYER_TAG, id)
                  .register(registry)
                  .getId());
          ids.add(
              Gauge.builder(BREAKER_STATE, state, TileCacheMetrics::encodeBreakerState)
                  .description("Circuit-breaker state: 0=PROCEED, 1=RETRY, 2=BLOCK.")
                  .tag(LAYER_TAG, id)
                  .register(registry)
                  .getId());
          return ids;
        });
  }

  private void unregisterLayer(String layerId) {
    List<Meter.Id> ids = perLayerMeters.remove(layerId);
    if (ids != null) {
      ids.forEach(registry::remove);
    }
  }

  private static double encodeBreakerState(LayerRuntimeState state) {
    return switch (state.requestStrategy()) {
      case PROCEED -> 0.0;
      case RETRY -> 1.0;
      case BLOCK -> 2.0;
    };
  }
}
