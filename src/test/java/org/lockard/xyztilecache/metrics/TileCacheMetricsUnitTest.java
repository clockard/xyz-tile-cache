package org.lockard.xyztilecache.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LayerChangedEvent;
import org.lockard.xyztilecache.model.LayerRuntimeState;
import org.lockard.xyztilecache.model.XyzLayer;
import org.lockard.xyztilecache.store.LayerStore;

class TileCacheMetricsUnitTest {

  private MeterRegistry registry;
  private LayerStore layerStore;
  private LayerRuntimeState osmState;
  private TileCacheMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    layerStore = mock(LayerStore.class);

    Layer osm =
        new XyzLayer(
            "osm",
            "osm",
            "https://example.com/{z}/{x}/{y}.png",
            null,
            19,
            0,
            0,
            java.util.List.of(),
            java.util.List.of(),
            Map.of(),
            null);

    when(layerStore.getLayers()).thenReturn(Map.of("osm", osm));
    osmState = new LayerRuntimeState();
    when(layerStore.getRuntimeState("osm")).thenReturn(osmState);

    metrics = new TileCacheMetrics(registry, layerStore);
    metrics.registerExistingLayers();
  }

  @Test
  void cachedTilesGauge_reflectsRuntimeState() {
    osmState.addTileStats(1024);
    osmState.addTileStats(2048);

    assertThat(registry.find(TileCacheMetrics.CACHED_TILES).tag("layer", "osm").gauge().value())
        .isEqualTo(2.0);
    assertThat(registry.find(TileCacheMetrics.CACHED_BYTES).tag("layer", "osm").gauge().value())
        .isEqualTo(3072.0);
  }

  @Test
  void breakerStateEncoding_proceedAtBaseline() {
    assertThat(breakerValue()).isEqualTo(0.0);
  }

  @Test
  void breakerStateEncoding_blockAfterFailure() {
    osmState.sourceFailed();
    // BLOCK or RETRY depending on clock skew; both must be non-zero (i.e. degraded).
    assertThat(breakerValue()).isGreaterThan(0.0);
  }

  @Test
  void onLayerChanged_addedRegistersGauges_removedUnregisters() {
    LayerRuntimeState newState = new LayerRuntimeState();
    when(layerStore.getRuntimeState("new")).thenReturn(newState);

    metrics.onLayerChanged(new LayerChangedEvent("new", LayerChangedEvent.Kind.ADDED));
    assertThat(registry.find(TileCacheMetrics.CACHED_TILES).tag("layer", "new").gauge())
        .isNotNull();

    metrics.onLayerChanged(new LayerChangedEvent("new", LayerChangedEvent.Kind.REMOVED));
    assertThat(registry.find(TileCacheMetrics.CACHED_TILES).tag("layer", "new").gauge()).isNull();
  }

  @Test
  void onLayerChanged_updateKeepsExistingGauges() {
    metrics.onLayerChanged(new LayerChangedEvent("osm", LayerChangedEvent.Kind.UPDATED_SOURCE));
    metrics.onLayerChanged(new LayerChangedEvent("osm", LayerChangedEvent.Kind.UPDATED_ACL));

    assertThat(registry.find(TileCacheMetrics.CACHED_TILES).tag("layer", "osm").gauge())
        .isNotNull();
  }

  private double breakerValue() {
    return registry.find(TileCacheMetrics.BREAKER_STATE).tag("layer", "osm").gauge().value();
  }
}
