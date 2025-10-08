package org.lockard.xyztilecache;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Layer {
  private static final Logger LOGGER = LoggerFactory.getLogger(Layer.class);

  private String name;

  private String urlTemplate;

  private final AtomicLong cachedTiles = new AtomicLong();

  private final AtomicLong cachedTilesSize = new AtomicLong();

  private final AtomicReference<Block> sourceBlock = new AtomicReference<>();

  private Map<String, String> headers = new HashMap<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getUrlTemplate() {
    return urlTemplate;
  }

  public void setUrlTemplate(String urlTemplate) {
    this.urlTemplate = urlTemplate;
  }

  public long getCachedTiles() {
    return cachedTiles.get();
  }

  public void setCachedTiles(long cachedTiles) {
    this.cachedTiles.set(cachedTiles);
  }

  public long getCachedTilesSize() {
    return cachedTilesSize.get();
  }

  public void setCachedTilesSize(long cachedTilesSize) {
    this.cachedTilesSize.set(cachedTilesSize);
  }

  public void addTileStats(long tileSize) {
    this.cachedTiles.incrementAndGet();
    this.cachedTilesSize.addAndGet(tileSize);
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }

  private record Block(Layer layer, long start, long duration) {
    private static final int DEFAULT_BLOCK_MS = 100;

    private static final int MAX_BLOCK_MS = 60_000;

    static Block defaultBlock(final Layer layer, final Clock clock) {
      LOGGER.debug("Default block for layer {}.", layer);
      return new Block(layer, clock.millis(), DEFAULT_BLOCK_MS);
    }

    Block increase(final Clock clock) {
      final var newDuration = Math.min(duration * 2, MAX_BLOCK_MS);
      LOGGER.debug("New block for layer {}: {} {}", layer, clock.millis(), newDuration);
      return new Block(layer, clock.millis(), newDuration);
    }

    long expiration() {
      return start + duration;
    }
  }

  void sourceFailed() {
    sourceFailed(Clock.systemUTC());
  }

  void sourceFailed(final Clock clock) {
    sourceBlock.updateAndGet(b -> b == null ? Block.defaultBlock(this, clock) : b.increase(clock));
  }

  void sourceSucceeded() {
    sourceBlock.set(null);
  }

  public enum RequestStrategy {
    PROCEED,
    RETRY,
    BLOCK
  }

  RequestStrategy requestStrategy() {
    return requestStrategy(Clock.systemUTC());
  }

  RequestStrategy requestStrategy(final Clock clock) {
    final var block = sourceBlock.get();
    if (block == null) {
      return RequestStrategy.PROCEED;
    } else if (clock.millis() < block.expiration()) {
      LOGGER.debug(
          "Source for layer {} is blocked for {} ms.", name, block.expiration() - clock.millis());
      return RequestStrategy.BLOCK;
    } else {
      return RequestStrategy.RETRY;
    }
  }

  @Override
  public String toString() {
    return name;
  }
}
