package org.lockard.xyztilecache;

import java.util.Set;

public class PreloadRequest {
  private Set<String> layers;

  private BoundingBox boundingBox;

  public PreloadRequest(Set<String> layers, BoundingBox bbox) {
    this.layers = layers;
    this.boundingBox = bbox;
  }

  public Set<String> getLayers() {
    return layers;
  }

  public void setLayers(Set<String> layers) {
    this.layers = layers;
  }

  public BoundingBox getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(BoundingBox boundingBox) {
    this.boundingBox = boundingBox;
  }

  @Override
  public String toString() {
    return "PreloadRequest{" + "layers=" + layers + ", boundingBox=" + boundingBox + '}';
  }
}
