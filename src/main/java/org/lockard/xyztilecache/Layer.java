package org.lockard.xyztilecache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Layer {
  private static final Logger LOGGER = LoggerFactory.getLogger(Layer.class);

  public enum SourceType {
    XYZ,
    WMTS_REST,
    WMTS_KVP,
    LOCAL
  }

  private SourceType sourceType = SourceType.XYZ;

  // WMTS-specific (used when sourceType = WMTS_KVP)
  private String wmtsLayerName;
  private String wmtsTileMatrixSet = "EPSG:3857";
  private String wmtsStyle = "default";
  private String wmtsFormat = "image/png";

  private boolean wmtsTime = false;

  private int tileExpirationMinutes = 0; // 0 = never expire

  private int maxZoom = 22;

  private String timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";

  private Boolean urlHasTime = null;

  private String attribution;

  private String id;

  private String name;

  private String urlTemplate;

  private final AtomicLong cachedTiles = new AtomicLong();

  private final AtomicLong cachedTilesSize = new AtomicLong();

  private final AtomicLong tilesServed = new AtomicLong();

  private final AtomicReference<Block> sourceBlock = new AtomicReference<>();

  private Map<String, String> headers = new HashMap<>();

  private List<String> allowedUsers = new ArrayList<>();

  private List<String> allowedGroups = new ArrayList<>();

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  /** Returns the identifier used in URLs and as map key. Falls back to name for backward compat. */
  @JsonIgnore
  public String getEffectiveId() {
    return (id != null && !id.isBlank()) ? id : name;
  }

  public String getUrlTemplate() {
    return urlTemplate;
  }

  public void setUrlTemplate(String urlTemplate) {
    this.urlTemplate = urlTemplate;
  }

  @JsonIgnore
  public long getCachedTiles() {
    return cachedTiles.get();
  }

  @JsonIgnore
  public void setCachedTiles(long cachedTiles) {
    this.cachedTiles.set(cachedTiles);
  }

  @JsonIgnore
  public long getCachedTilesSize() {
    return cachedTilesSize.get();
  }

  @JsonIgnore
  public void setCachedTilesSize(long cachedTilesSize) {
    this.cachedTilesSize.set(cachedTilesSize);
  }

  @JsonIgnore
  public long getTilesServed() {
    return tilesServed.get();
  }

  public void incrementTilesServed() {
    tilesServed.incrementAndGet();
  }

  public SourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(SourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getWmtsFormat() {
    return wmtsFormat;
  }

  public void setWmtsFormat(String wmtsFormat) {
    this.wmtsFormat = wmtsFormat;
  }

  public String getWmtsStyle() {
    return wmtsStyle;
  }

  public void setWmtsStyle(String wmtsStyle) {
    this.wmtsStyle = wmtsStyle;
  }

  public String getWmtsTileMatrixSet() {
    return wmtsTileMatrixSet;
  }

  public void setWmtsTileMatrixSet(String wmtsTileMatrixSet) {
    this.wmtsTileMatrixSet = wmtsTileMatrixSet;
  }

  public String getWmtsLayerName() {
    return wmtsLayerName;
  }

  public void setWmtsLayerName(String wmtsLayerName) {
    this.wmtsLayerName = wmtsLayerName;
  }

  public int getTileExpirationMinutes() {
    return tileExpirationMinutes;
  }

  public void setTileExpirationMinutes(int tileExpirationMinutes) {
    this.tileExpirationMinutes = tileExpirationMinutes;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public String getTimeFormat() {
    return timeFormat;
  }

  public void setTimeFormat(String timeFormat) {
    this.timeFormat = timeFormat;
  }

  public boolean isWmtsTime() {
    return wmtsTime;
  }

  public void setWmtsTime(boolean wmtsTime) {
    this.wmtsTime = wmtsTime;
  }

  public boolean doesUrlHaveTime() {
    if (urlHasTime == null) {
      urlHasTime = (urlTemplate != null && urlTemplate.contains("{time}")) || wmtsTime;
    }
    return urlHasTime;
  }

  public String getAttribution() {
    return attribution;
  }

  public void setAttribution(String attribution) {
    this.attribution = attribution;
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

  public List<String> getAllowedUsers() {
    return allowedUsers;
  }

  public void setAllowedUsers(List<String> allowedUsers) {
    this.allowedUsers = allowedUsers == null ? new ArrayList<>() : allowedUsers;
  }

  public List<String> getAllowedGroups() {
    return allowedGroups;
  }

  public void setAllowedGroups(List<String> allowedGroups) {
    this.allowedGroups = allowedGroups == null ? new ArrayList<>() : allowedGroups;
  }

  @JsonIgnore
  public boolean isPublic() {
    return allowedUsers.isEmpty() && allowedGroups.isEmpty();
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
    return getEffectiveId();
  }
}
