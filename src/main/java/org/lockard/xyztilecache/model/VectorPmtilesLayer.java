package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Vector PMTiles layer: serves MVT tiles from a local or remote {@code .pmtiles} archive. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VectorPmtilesLayer(
    String id,
    String name,
    String urlTemplate,
    String attribution,
    int maxZoom,
    int initZoom,
    int tileExpirationMinutes,
    List<String> allowedUsers,
    List<String> allowedGroups)
    implements Layer {

  public VectorPmtilesLayer {
    // JSON API callers may omit maxZoom (primitive default 0), which would 404 every z>0 tile.
    if (maxZoom <= 0) maxZoom = 15;
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
  }

  @Override
  public SourceType sourceType() {
    return SourceType.VECTOR_PMTILES;
  }

  @Override
  public String tileFileExtension() {
    return "pbf";
  }

  @Override
  public VectorPmtilesLayer withId(String newId) {
    return new VectorPmtilesLayer(
        newId,
        name,
        urlTemplate,
        attribution,
        maxZoom,
        initZoom,
        tileExpirationMinutes,
        allowedUsers,
        allowedGroups);
  }
}
