package org.lockard.xyztilecache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xyz.vector")
public class VectorConfiguration {

  private String downloadDirectory;

  private String sourceUrl = "https://build.protomaps.com/{date}.pmtiles";

  private int maxDownloadZoom = 15;

  private int initZoom = 0;

  private boolean enabled = true;

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

  public int getInitZoom() {
    return initZoom;
  }

  public void setInitZoom(int initZoom) {
    this.initZoom = initZoom;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
