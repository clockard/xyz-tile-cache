package org.lockard.xyztilecache.handler;

import java.io.IOException;
import java.util.Optional;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.TileResult;
import org.lockard.xyztilecache.service.VectorPmtilesManager;
import org.springframework.stereotype.Component;

@Component
public class VectorPmtilesHandler implements TileSourceHandler {

  private final VectorPmtilesManager manager;

  public VectorPmtilesHandler(VectorPmtilesManager manager) {
    this.manager = manager;
  }

  @Override
  public Layer.SourceType sourceType() {
    return Layer.SourceType.VECTOR_PMTILES;
  }

  @Override
  public Optional<TileResult> getTile(Layer layer, int z, int x, int y) throws IOException {
    return manager.getTile(layer.getEffectiveId(), z, x, y);
  }
}
