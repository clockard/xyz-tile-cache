package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Polymorphic layer model. Each source type is a concrete record with only the fields it actually
 * uses. Wire format (JSON / layers.json) stays flat: the {@code sourceType} property discriminates
 * which record to deserialize into.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "sourceType",
    visible = true,
    defaultImpl = XyzLayer.class)
@JsonSubTypes({
  @JsonSubTypes.Type(value = XyzLayer.class, name = "XYZ"),
  @JsonSubTypes.Type(value = WmtsRestLayer.class, name = "WMTS_REST"),
  @JsonSubTypes.Type(value = WmtsKvpLayer.class, name = "WMTS_KVP"),
  @JsonSubTypes.Type(value = LocalLayer.class, name = "LOCAL"),
  @JsonSubTypes.Type(value = VectorPmtilesLayer.class, name = "VECTOR_PMTILES"),
})
public sealed interface Layer
    permits XyzLayer, WmtsRestLayer, WmtsKvpLayer, LocalLayer, VectorPmtilesLayer {

  enum SourceType {
    XYZ,
    WMTS_REST,
    WMTS_KVP,
    LOCAL,
    VECTOR_PMTILES
  }

  enum RequestStrategy {
    PROCEED,
    RETRY,
    BLOCK
  }

  // ── Common fields (all records expose these) ────────────────────────────────

  String id();

  String name();

  String attribution();

  int maxZoom();

  int initZoom();

  int tileExpirationMinutes();

  List<String> allowedUsers();

  List<String> allowedGroups();

  @JsonProperty("urlTemplate")
  String urlTemplate();

  @JsonProperty("sourceType")
  SourceType sourceType();

  /** Returns a copy of this layer with the given id. Used when a path id overrides a body id. */
  Layer withId(String newId);

  // ── Derived / per-type behavior ─────────────────────────────────────────────

  /** File-system extension (without dot) for tiles stored under this layer's directory. */
  @JsonIgnore
  String tileFileExtension();

  /** Falls back to {@link #name()} when id is blank, matching legacy YAML configs. */
  @JsonIgnore
  default String effectiveId() {
    return (id() != null && !id().isBlank()) ? id() : name();
  }

  @JsonIgnore
  default boolean isPublic() {
    return allowedUsers().isEmpty() && allowedGroups().isEmpty();
  }

  /** True if the source URL needs a time substitution at request time. */
  @JsonIgnore
  default boolean doesUrlHaveTime() {
    return urlTemplate() != null && urlTemplate().contains("{time}");
  }

  /** Optional HTTP headers to attach to upstream requests. Empty for layers that don't fetch. */
  default Map<String, String> headers() {
    return Map.of();
  }

  /** Format string for the {@code {time}} substitution. */
  default String timeFormat() {
    return "yyyy-MM-dd'T'HH:mm:ss'Z'";
  }

  // ── Legacy getter aliases (kept for source-compat with pre-refactor callers) ─

  @JsonIgnore
  default String getId() {
    return id();
  }

  @JsonIgnore
  default String getName() {
    return name();
  }

  @JsonIgnore
  default String getAttribution() {
    return attribution();
  }

  @JsonIgnore
  default int getMaxZoom() {
    return maxZoom();
  }

  @JsonIgnore
  default int getInitZoom() {
    return initZoom();
  }

  @JsonIgnore
  default int getTileExpirationMinutes() {
    return tileExpirationMinutes();
  }

  @JsonIgnore
  default List<String> getAllowedUsers() {
    return allowedUsers();
  }

  @JsonIgnore
  default List<String> getAllowedGroups() {
    return allowedGroups();
  }

  @JsonIgnore
  default String getUrlTemplate() {
    return urlTemplate();
  }

  @JsonIgnore
  default SourceType getSourceType() {
    return sourceType();
  }

  @JsonIgnore
  default String getEffectiveId() {
    return effectiveId();
  }

  @JsonIgnore
  default String getTileFileExtension() {
    return tileFileExtension();
  }

  @JsonIgnore
  default Map<String, String> getHeaders() {
    return headers();
  }

  @JsonIgnore
  default String getTimeFormat() {
    return timeFormat();
  }
}
