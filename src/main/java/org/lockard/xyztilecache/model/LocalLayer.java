package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Disk-only layer: tiles served exclusively from {@code {baseTileDir}/{id}/{z}/{x}/{y}.png}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalLayer(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("attribution") String attribution,
    @JsonProperty("maxZoom") int maxZoom,
    @JsonProperty("initZoom") int initZoom,
    @JsonProperty("tileExpirationMinutes") int tileExpirationMinutes,
    @JsonProperty("allowedUsers") List<String> allowedUsers,
    @JsonProperty("allowedGroups") List<String> allowedGroups)
    implements Layer {
  // Every component is annotated explicitly: Jackson 3 links a record component to its
  // @JsonIgnore'd legacy alias getter in Layer (getId() etc.) and would drop it on read.

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
