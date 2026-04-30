package org.lockard.xyztilecache;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Preload {

  private String id;
  private String name;
  private BoundingBox boundingBox;
  private int maxZoom;
  private List<String> layers = new ArrayList<>();
  private boolean includesVector;
  private String pmtilesFilename;
  private Instant createdAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

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

  public List<String> getLayers() {
    return layers;
  }

  public void setLayers(List<String> layers) {
    this.layers = layers == null ? new ArrayList<>() : layers;
  }

  public boolean isIncludesVector() {
    return includesVector;
  }

  public void setIncludesVector(boolean includesVector) {
    this.includesVector = includesVector;
  }

  public String getPmtilesFilename() {
    return pmtilesFilename;
  }

  public void setPmtilesFilename(String pmtilesFilename) {
    this.pmtilesFilename = pmtilesFilename;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
