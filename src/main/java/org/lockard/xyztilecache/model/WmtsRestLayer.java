package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** WMTS RESTful layer: substitutes {@code {TileMatrix}/{TileRow}/{TileCol}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WmtsRestLayer(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("urlTemplate") String urlTemplate,
    @JsonProperty("attribution") String attribution,
    @JsonProperty("maxZoom") int maxZoom,
    @JsonProperty("initZoom") int initZoom,
    @JsonProperty("tileExpirationMinutes") int tileExpirationMinutes,
    @JsonProperty("allowedUsers") List<String> allowedUsers,
    @JsonProperty("allowedGroups") List<String> allowedGroups,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("timeFormat") String timeFormat)
    implements Layer {
  // Every component is annotated explicitly: Jackson 3 links a record component to its
  // @JsonIgnore'd legacy alias getter in Layer (getId() etc.) and would drop it on read.

  public WmtsRestLayer {
    // JSON API callers may omit maxZoom (primitive default 0), which would 404 every z>0 tile.
    if (maxZoom <= 0) maxZoom = 22;
    if (timeFormat == null || timeFormat.isBlank()) timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  @Override
  public SourceType sourceType() {
    return SourceType.WMTS_REST;
  }

  @Override
  public String tileFileExtension() {
    // WMTS REST templates don't carry the format, but the path conventionally has no extension at
    // all; tiles are usually PNG. Fall back to PNG unless URL ends in .jpg/.webp/.gif.
    return XyzLayer.UrlExtensions.fromUrl(urlTemplate);
  }

  @Override
  public WmtsRestLayer withId(String newId) {
    return new WmtsRestLayer(
        newId,
        name,
        urlTemplate,
        attribution,
        maxZoom,
        initZoom,
        tileExpirationMinutes,
        allowedUsers,
        allowedGroups,
        headers,
        timeFormat);
  }

  public String buildUrl(int z, int x, int y, String timeString) {
    String url =
        urlTemplate
            .replace("{TileMatrix}", String.valueOf(z))
            .replace("{TileRow}", String.valueOf(y))
            .replace("{TileCol}", String.valueOf(x));
    if (doesUrlHaveTime() && timeString != null) {
      url = url.replace("{time}", timeString);
    }
    return url;
  }
}
