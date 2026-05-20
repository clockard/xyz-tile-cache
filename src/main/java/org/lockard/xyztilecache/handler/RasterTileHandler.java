package org.lockard.xyztilecache.handler;

import com.google.common.cache.LoadingCache;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.TileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RasterTileHandler implements TileSourceHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(RasterTileHandler.class);
  private static final Set<Layer.SourceType> RASTER_TYPES =
      EnumSet.of(
          Layer.SourceType.XYZ,
          Layer.SourceType.WMTS_REST,
          Layer.SourceType.WMTS_KVP,
          Layer.SourceType.LOCAL);

  private final LoadingCache<Tile, byte[]> tileCache;

  public RasterTileHandler(LoadingCache<Tile, byte[]> tileCache) {
    this.tileCache = tileCache;
  }

  @Override
  public Set<Layer.SourceType> sourceTypes() {
    return RASTER_TYPES;
  }

  @Override
  public String contentType() {
    return "image/png";
  }

  @Override
  public Optional<TileResult> getTile(Layer layer, int z, int x, int y) {
    try {
      return Optional.of(new TileResult(tileCache.get(new Tile(layer, x, y, z)), 0));
    } catch (ExecutionException e) {
      LOGGER.debug(
          "Failed to retrieve tile {}/{}/{} for layer {}",
          z,
          x,
          y,
          layer.getEffectiveId(),
          e.getCause());
      throw new TileNotFoundException();
    }
  }
}
