package org.lockard.xyztilecache.handler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.stereotype.Component;

@Component
public class TileSourceHandlerRegistry {

  private final Map<Layer.SourceType, TileSourceHandler> handlers;

  public TileSourceHandlerRegistry(List<TileSourceHandler> handlerList) {
    handlers = new EnumMap<>(Layer.SourceType.class);
    handlerList.forEach(h -> h.sourceTypes().forEach(t -> handlers.put(t, h)));
  }

  public Optional<TileSourceHandler> getHandler(Layer.SourceType type) {
    return Optional.ofNullable(handlers.get(type));
  }
}
