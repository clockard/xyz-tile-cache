package org.lockard.xyztilecache.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LayerTest {

  // ── Circuit-breaker tests (now on LayerRuntimeState) ─────────────────────

  @Test
  void sourceNotBlocked() {
    final var state = new LayerRuntimeState();
    assertThat(state.requestStrategy()).isEqualTo(Layer.RequestStrategy.PROCEED);
  }

  @Test
  void sourceBlocked() {
    final var state = new LayerRuntimeState();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    state.sourceFailed(now);
    assertThat(state.requestStrategy(now)).isEqualTo(Layer.RequestStrategy.BLOCK);
  }

  @Test
  void sourceUnblockedAfterSuccess() {
    final var state = new LayerRuntimeState();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    state.sourceFailed(now);
    state.sourceSucceeded();
    assertThat(state.requestStrategy(now)).isEqualTo(Layer.RequestStrategy.PROCEED);
  }

  @Test
  void retrySourceAfterBlockExpires() {
    final var state = new LayerRuntimeState();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    state.sourceFailed(now);
    final Clock later = Clock.fixed(Instant.ofEpochMilli(1200), ZoneOffset.UTC);
    assertThat(state.requestStrategy(later)).isEqualTo(Layer.RequestStrategy.RETRY);
  }

  @Test
  void consecutiveFailuresIncreaseBlockDuration() {
    final var state = new LayerRuntimeState();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    state.sourceFailed(now);
    state.sourceFailed(now);
    state.sourceFailed(now);
    final Clock later = Clock.fixed(Instant.ofEpochMilli(1250), ZoneOffset.UTC);
    assertThat(state.requestStrategy(later)).isEqualTo(Layer.RequestStrategy.BLOCK);
  }

  @Test
  void addTileStats_accumulatesCountAndSize() {
    final var state = new LayerRuntimeState();
    state.addTileStats(100L);
    state.addTileStats(200L);
    assertThat(state.getCachedTiles()).isEqualTo(2);
    assertThat(state.getCachedTilesSize()).isEqualTo(300L);
  }

  // ── Layer data model tests ────────────────────────────────────────────────

  @Test
  void doesUrlHaveTime_trueWhenUrlTemplateContainsTimePlaceholder() {
    Layer layer =
        new XyzLayer(
            "t",
            "t",
            "https://example.com/{time}/{z}/{x}/{y}.png",
            null,
            22,
            0,
            0,
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            null);
    assertThat(layer.doesUrlHaveTime()).isTrue();
  }

  @Test
  void doesUrlHaveTime_trueWhenWmtsTimeEnabled() {
    Layer layer =
        new WmtsKvpLayer(
            "t",
            "t",
            "https://example.com/wmts",
            null,
            22,
            0,
            0,
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            "L",
            "EPSG:3857",
            "default",
            "image/png",
            true,
            null);
    assertThat(layer.doesUrlHaveTime()).isTrue();
  }

  @Test
  void doesUrlHaveTime_falseWhenNoTimePlaceholderAndWmtsTimeDisabled() {
    Layer layer =
        new XyzLayer(
            "t",
            "t",
            "https://example.com/{z}/{x}/{y}.png",
            null,
            22,
            0,
            0,
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            null);
    assertThat(layer.doesUrlHaveTime()).isFalse();
  }
}
