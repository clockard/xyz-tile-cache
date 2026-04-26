package org.lockard.xyztilecache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xyz")
public class XyzConfiguration {

  private String baseTileDirectory;

  private long minFreeDiskBytes = 1_073_741_824L; // 1 GB default

  private boolean offline = false;

  private int tileTimeoutSeconds = 1;

  private int layerSyncSeconds = 10;

  private String adminKey = "";

  private final Map<String, Layer> layers = new ConcurrentHashMap<>();

  private List<BoundingBox> boundingBoxes = new ArrayList<>();

  public String getBaseTileDirectory() {
    return baseTileDirectory;
  }

  public void setBaseTileDirectory(String baseTileDirectory) {
    this.baseTileDirectory = baseTileDirectory;
  }

  public long getMinFreeDiskBytes() {
    return minFreeDiskBytes;
  }

  public void setMinFreeDiskBytes(long minFreeDiskBytes) {
    this.minFreeDiskBytes = minFreeDiskBytes;
  }

  public int getLayerSyncSeconds() {
    return layerSyncSeconds;
  }

  public void setLayerSyncSeconds(int layerSyncSeconds) {
    this.layerSyncSeconds = layerSyncSeconds;
  }

  public Map<String, Layer> getLayers() {
    return layers;
  }

  public void setLayers(List<Layer> layers) {
    this.layers.clear();
    layers.forEach(l -> this.layers.put(l.getName(), l));
  }

  public List<BoundingBox> getBoundingBoxes() {
    return boundingBoxes;
  }

  public void setBoundingBoxes(List<BoundingBox> boundingBoxes) {
    this.boundingBoxes = boundingBoxes;
  }

  public boolean isOffline() {
    return offline;
  }

  public void setOffline(boolean offline) {
    this.offline = offline;
  }

  public int getTileTimeoutSeconds() {
    return tileTimeoutSeconds;
  }

  public void setTileTimeoutSeconds(int tileTimeoutSeconds) {
    this.tileTimeoutSeconds = tileTimeoutSeconds;
  }

  public String getAdminKey() {
    return adminKey;
  }

  public void setAdminKey(String adminKey) {
    this.adminKey = adminKey;
  }
}
