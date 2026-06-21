package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Disk-only layer: tiles served exclusively from {@code {baseTileDir}/{id}/{z}/{x}/{y}.png}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalLayer(
    String id,
    String name,
    String attribution,
    int maxZoom,
    int initZoom,
    int tileExpirationMinutes,
    List<String> allowedUsers,
    List<String> allowedGroups)
    implements Layer {

  public LocalLayer {
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
  }

  @Override
  public SourceType sourceType() {
    return SourceType.LOCAL;
  }

  @Override
  public String urlTemplate() {
    return null;
  }

  @Override
  public String tileFileExtension() {
    return "png";
  }

  @Override
  public LocalLayer withId(String newId) {
    return new LocalLayer(
        newId,
        name,
        attribution,
        maxZoom,
        initZoom,
        tileExpirationMinutes,
        allowedUsers,
        allowedGroups);
  }
}
