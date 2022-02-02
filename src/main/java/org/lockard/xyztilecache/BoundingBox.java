package org.lockard.xyztilecache;

public class BoundingBox {

  private boolean preCache = false;
  private int maxZoom = 17;

  private double north;
  private double south;
  private double east;
  private double west;

  public boolean isPreCache() {
    return preCache;
  }

  public void setPreCache(boolean preCache) {
    this.preCache = preCache;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public double getNorth() {
    return north;
  }

  public void setNorth(double north) {
    this.north = north;
  }

  public double getSouth() {
    return south;
  }

  public void setSouth(double south) {
    this.south = south;
  }

  public double getEast() {
    return east;
  }

  public void setEast(double east) {
    this.east = east;
  }

  public double getWest() {
    return west;
  }

  public void setWest(double west) {
    this.west = west;
  }
}
