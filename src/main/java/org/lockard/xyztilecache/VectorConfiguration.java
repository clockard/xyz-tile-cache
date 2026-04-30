package org.lockard.xyztilecache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xyz.vector")
public class VectorConfiguration {

  private String bundledPath = "/app/data/world_z0-7.pmtiles";

  private String downloadDirectory;

  private String sourceUrl = "https://build.protomaps.com/planet.pmtiles";

  private int maxDownloadZoom = 15;

  private boolean enabled = true;

  public String getBundledPath() {
    return bundledPath;
  }

  public void setBundledPath(String bundledPath) {
    this.bundledPath = bundledPath;
  }

  public String getDownloadDirectory() {
    return downloadDirectory;
  }

  public void setDownloadDirectory(String downloadDirectory) {
    this.downloadDirectory = downloadDirectory;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  public int getMaxDownloadZoom() {
    return maxDownloadZoom;
  }

  public void setMaxDownloadZoom(int maxDownloadZoom) {
    this.maxDownloadZoom = maxDownloadZoom;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
