package org.lockard.xyztilecache.handler;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.TileResult;

public interface TileSourceHandler {
  Set<Layer.SourceType> sourceTypes();

  Optional<TileResult> getTile(Layer layer, int z, int x, int y) throws IOException;
}
