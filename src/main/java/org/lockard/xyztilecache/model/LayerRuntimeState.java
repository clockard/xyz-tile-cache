package org.lockard.xyztilecache.model;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds in-memory-only runtime state for a layer: request statistics and circuit-breaker.
 *
 * <p>What a layer holds <em>on disk</em> is not here — that outlives the process and belongs to
 * {@link org.lockard.xyztilecache.store.TileInventoryStore}.
 */
public class LayerRuntimeState {

  private static final Logger LOGGER = LoggerFactory.getLogger(LayerRuntimeState.class);

  private final AtomicLong tilesServed = new AtomicLong();
  private final AtomicReference<Block> sourceBlock = new AtomicReference<>();

  // ── Stats ─────────────────────────────────────────────────────────────────

  public long getTilesServed() {
    return tilesServed.get();
  }

  public void incrementTilesServed() {
    tilesServed.incrementAndGet();
  }

  // ── Circuit breaker ───────────────────────────────────────────────────────

  public void sourceFailed() {
    sourceFailed(Clock.systemUTC());
  }

  public void sourceFailed(Clock clock) {
    sourceBlock.updateAndGet(b -> b == null ? Block.defaultBlock(clock) : b.increase(clock));
  }

  public void sourceSucceeded() {
    sourceBlock.set(null);
  }

  public Layer.RequestStrategy requestStrategy() {
    return requestStrategy(Clock.systemUTC());
  }

  public Layer.RequestStrategy requestStrategy(Clock clock) {
    Block block = sourceBlock.get();
    if (block == null) {
      return Layer.RequestStrategy.PROCEED;
    } else if (clock.millis() < block.expiration()) {
      LOGGER.debug("Source is blocked for {} ms.", block.expiration() - clock.millis());
      return Layer.RequestStrategy.BLOCK;
    } else {
      return Layer.RequestStrategy.RETRY;
    }
  }

  private record Block(long start, long duration) {
    private static final int DEFAULT_BLOCK_MS = 100;
    private static final int MAX_BLOCK_MS = 60_000;

    static Block defaultBlock(Clock clock) {
      return new Block(clock.millis(), DEFAULT_BLOCK_MS);
    }

    Block increase(Clock clock) {
      return new Block(clock.millis(), Math.min(duration * 2, MAX_BLOCK_MS));
    }

    long expiration() {
      return start + duration;
    }
  }
}
