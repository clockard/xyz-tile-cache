package org.lockard.xyztilecache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class Layer {
  private String name;
  private String urlTemplate;
  private long expiration = -1;
  private int type = 0;

  private final AtomicLong cachedTiles = new AtomicLong();

  private final AtomicLong cachedTilesSize = new AtomicLong();

  private volatile boolean sourceAvailable = true;

  private volatile long sourceLastChecked = -1;

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

  public long getExpiration() {
    return expiration;
  }

  public void setExpiration(long expiration) {
    this.expiration = expiration;
  }

  public int getType() {
    return type;
  }

  public void setType(int type) {
    this.type = type;
  }

  public boolean isSourceAvailable() {
    return sourceAvailable;
  }

  public void setSourceAvailable(boolean sourceAvailable) {
    this.sourceAvailable = sourceAvailable;
  }

  public long getSourceLastChecked() {
    return sourceLastChecked;
  }

  public void setSourceLastChecked(long sourceLastChecked) {
    this.sourceLastChecked = sourceLastChecked;
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

  @Override
  public String toString() {
    return name;
  }
}
