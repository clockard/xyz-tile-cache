package org.lockard.xyztilecache.model;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds in-memory-only runtime state for a layer: statistics and circuit-breaker. */
public class LayerRuntimeState {

  private static final Logger LOGGER = LoggerFactory.getLogger(LayerRuntimeState.class);

  private final AtomicLong cachedTiles = new AtomicLong();
  private final AtomicLong cachedTilesSize = new AtomicLong();
  private final AtomicLong tilesServed = new AtomicLong();
  private final AtomicReference<Block> sourceBlock = new AtomicReference<>();

  // ── Stats ─────────────────────────────────────────────────────────────────

  public long getCachedTiles() {
    return cachedTiles.get();
  }

  public void setCachedTiles(long value) {
    cachedTiles.set(value);
  }

  public long getCachedTilesSize() {
    return cachedTilesSize.get();
  }

  public void setCachedTilesSize(long value) {
    cachedTilesSize.set(value);
  }

  public long getTilesServed() {
    return tilesServed.get();
  }

  public void incrementTilesServed() {
    tilesServed.incrementAndGet();
  }

  public void addTileStats(long tileSize) {
    cachedTiles.incrementAndGet();
    cachedTilesSize.addAndGet(tileSize);
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
