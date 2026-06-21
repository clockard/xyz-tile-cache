package org.lockard.xyztilecache.handler;

import com.github.benmanes.caffeine.cache.LoadingCache;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.lockard.xyztilecache.cache.UpstreamUnavailableException;
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
  public Optional<TileResult> getTile(Layer layer, int z, int x, int y) throws IOException {
    try {
      byte[] data = tileCache.get(new Tile(layer, x, y, z));
      return Optional.of(new TileResult(data, 0, detectContentType(data)));
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof UpstreamUnavailableException uu) {
        throw uu;
      }
      LOGGER.debug(
          "Failed to retrieve tile {}/{}/{} for layer {}", z, x, y, layer.effectiveId(), cause);
      throw new TileNotFoundException(cause);
    }
  }

  static String detectContentType(byte[] data) {
    if (data.length >= 3
        && (data[0] & 0xff) == 0xff
        && (data[1] & 0xff) == 0xd8
        && (data[2] & 0xff) == 0xff) {
      return "image/jpeg";
    }
    if (data.length >= 6
        && data[0] == 'G'
        && data[1] == 'I'
        && data[2] == 'F'
        && data[3] == '8'
        && (data[4] == '7' || data[4] == '9')
        && data[5] == 'a') {
      return "image/gif";
    }
    if (data.length >= 12
        && data[0] == 'R'
        && data[1] == 'I'
        && data[2] == 'F'
        && data[3] == 'F'
        && data[8] == 'W'
        && data[9] == 'E'
        && data[10] == 'B'
        && data[11] == 'P') {
      return "image/webp";
    }
    return "image/png";
  }
}
