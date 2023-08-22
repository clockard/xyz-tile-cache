package org.lockard.xyztilecache;

import java.util.HashMap;
import java.util.Map;

public class Layer {
  private String name;
  private String urlTemplate;
  private long expiration = -1;
  private int type = 0;

  private long cachedTiles = 0;

  private long cachedTilesSize = 0;

  private boolean sourceAvailable = true;

  private long sourceLastChecked = -1;

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
    return cachedTiles;
  }

  public void setCachedTiles(long cachedTiles) {
    this.cachedTiles = cachedTiles;
  }

  public long getCachedTilesSize() {
    return cachedTilesSize;
  }

  public void setCachedTilesSize(long cachedTilesSize) {
    this.cachedTilesSize = cachedTilesSize;
  }

  public void addTileStats(long tileSize) {
    this.cachedTiles++;
    this.cachedTilesSize += tileSize;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }
}
