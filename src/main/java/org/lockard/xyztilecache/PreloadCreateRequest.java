package org.lockard.xyztilecache;

import java.util.HashSet;
import java.util.Set;

public class PreloadCreateRequest {

  private String name;
  private BoundingBox boundingBox;
  private int maxZoom = 15;
  private Set<String> layers = new HashSet<>();
  private boolean includeVector;

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

  public Set<String> getLayers() {
    return layers;
  }

  public void setLayers(Set<String> layers) {
    this.layers = layers == null ? new HashSet<>() : layers;
  }

  public boolean isIncludeVector() {
    return includeVector;
  }

  public void setIncludeVector(boolean includeVector) {
    this.includeVector = includeVector;
  }
}
