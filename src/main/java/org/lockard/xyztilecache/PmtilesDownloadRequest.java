package org.lockard.xyztilecache;

public class PmtilesDownloadRequest {

  private String name;

  private BoundingBox boundingBox;

  private int maxZoom = 15;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public BoundingBox getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(BoundingBox boundingBox) {
    this.boundingBox = boundingBox;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }
}
