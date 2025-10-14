package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LayerTest {
  @Test
  void sourceNotBlocked() {
    final var layer = new Layer();
    assertThat(layer.requestStrategy()).isEqualTo(Layer.RequestStrategy.PROCEED);
  }

  @Test
  void sourceBlocked() {
    final var layer = new Layer();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    layer.sourceFailed(now);
    assertThat(layer.requestStrategy(now)).isEqualTo(Layer.RequestStrategy.BLOCK);
  }

  @Test
  void sourceUnblockedAfterSuccess() {
    final var layer = new Layer();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    layer.sourceFailed(now);
    layer.sourceSucceeded();
    assertThat(layer.requestStrategy(now)).isEqualTo(Layer.RequestStrategy.PROCEED);
  }

  @Test
  void retrySourceAfterBlockExpires() {
    final var layer = new Layer();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    layer.sourceFailed(now);
    final Clock later = Clock.fixed(Instant.ofEpochMilli(1200), ZoneOffset.UTC);
    assertThat(layer.requestStrategy(later)).isEqualTo(Layer.RequestStrategy.RETRY);
  }

  @Test
  void consecutiveFailuresIncreaseBlockDuration() {
    final var layer = new Layer();
    final Clock now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    layer.sourceFailed(now);
    layer.sourceFailed(now);
    layer.sourceFailed(now);
    final Clock later = Clock.fixed(Instant.ofEpochMilli(1250), ZoneOffset.UTC);
    assertThat(layer.requestStrategy(later)).isEqualTo(Layer.RequestStrategy.BLOCK);
  }
}
