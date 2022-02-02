package org.lockard.xyztilecache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xyz")
public class XyzConfiguration {

  private String baseTileDirectory;

  private long maxTileStorage;

  private Map<String, Layer> layers = new HashMap<>();

  private List<BoundingBox> boundingBoxes = new ArrayList<>();

  public String getBaseTileDirectory() {
    return baseTileDirectory;
  }

  public void setBaseTileDirectory(String baseTileDirectory) {
    this.baseTileDirectory = baseTileDirectory;
  }

  public long getMaxTileStorage() {
    return maxTileStorage;
  }

  public void setMaxTileStorage(long maxTileStorageMB) {
    this.maxTileStorage = maxTileStorageMB;
  }

  public Map<String, Layer> getLayers() {
    return layers;
  }

  public void setLayers(List<Layer> layers) {
    this.layers.clear();
    layers.forEach((l) -> this.layers.put(l.getName(), l));
  }

  public List<BoundingBox> getBoundingBoxes() {
    return boundingBoxes;
  }

  public void setBoundingBoxes(List<BoundingBox> boundingBoxes) {
    this.boundingBoxes = boundingBoxes;
  }
}
