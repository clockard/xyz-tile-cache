package org.lockard.xyztilecache.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xyz")
public class XyzConfiguration {

  private String baseTileDirectory;

  private String importDirectory = "/app/imports";

  private long minFreeDiskBytes = 1_073_741_824L; // 1 GB default

  private boolean offline = false;

  private int tileTimeoutSeconds = 1;

  private int layerSyncSeconds = 10;

  private int exportRetentionMinutes = 60;

  private int exportSweepSeconds = 300;

  private int defaultCacheMaxAgeSeconds = 86_400;

  private long tileCacheBytes = 256L * 1024 * 1024;

  private int preloadConcurrency = 4;

  private boolean uiEnabled = true;

  private String adminRole = "admin";

  private final Auth auth = new Auth();

  private final Map<String, Layer> layers = new ConcurrentHashMap<>();

  private List<BoundingBox> boundingBoxes = new ArrayList<>();

  public String getBaseTileDirectory() {
    return baseTileDirectory;
  }

  public void setBaseTileDirectory(String baseTileDirectory) {
    this.baseTileDirectory = baseTileDirectory;
  }

  public String getImportDirectory() {
    return importDirectory;
  }

  public void setImportDirectory(String importDirectory) {
    this.importDirectory = importDirectory;
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

  public int getExportRetentionMinutes() {
    return exportRetentionMinutes;
  }

  public void setExportRetentionMinutes(int exportRetentionMinutes) {
    this.exportRetentionMinutes = exportRetentionMinutes;
  }

  public int getExportSweepSeconds() {
    return exportSweepSeconds;
  }

  public void setExportSweepSeconds(int exportSweepSeconds) {
    this.exportSweepSeconds = exportSweepSeconds;
  }

  public int getDefaultCacheMaxAgeSeconds() {
    return defaultCacheMaxAgeSeconds;
  }

  public void setDefaultCacheMaxAgeSeconds(int defaultCacheMaxAgeSeconds) {
    this.defaultCacheMaxAgeSeconds = defaultCacheMaxAgeSeconds;
  }

  public long getTileCacheBytes() {
    return tileCacheBytes;
  }

  public void setTileCacheBytes(long tileCacheBytes) {
    this.tileCacheBytes = tileCacheBytes;
  }

  public int getPreloadConcurrency() {
    return preloadConcurrency;
  }

  public void setPreloadConcurrency(int preloadConcurrency) {
    this.preloadConcurrency = preloadConcurrency;
  }

  public boolean isUiEnabled() {
    return uiEnabled;
  }

  public void setUiEnabled(boolean uiEnabled) {
    this.uiEnabled = uiEnabled;
  }

  public String getAdminRole() {
    return adminRole;
  }

  public void setAdminRole(String adminRole) {
    this.adminRole = adminRole;
  }

  public Map<String, Layer> getLayers() {
    return layers;
  }

  public void setLayers(List<LayerProperties> layers) {
    this.layers.clear();
    layers.forEach(
        p -> {
          Layer l = p.toLayer();
          this.layers.put(l.effectiveId(), l);
        });
  }

  /** Programmatic seeding path used by tests. Bypasses the {@link LayerProperties} shim. */
  public void installLayers(List<Layer> layers) {
    this.layers.clear();
    layers.forEach(l -> this.layers.put(l.effectiveId(), l));
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

  public Auth getAuth() {
    return auth;
  }

  public static class Auth {
    public enum Mode {
      JWT,
      TOKEN
    }

    private Mode mode = Mode.JWT;
    private String adminToken = "";
    private String clientId = "xyz-tile-cache";

    public Mode getMode() {
      return mode;
    }

    public void setMode(Mode mode) {
      this.mode = mode;
    }

    public String getAdminToken() {
      return adminToken;
    }

    public void setAdminToken(String adminToken) {
      this.adminToken = adminToken;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }
  }
}
