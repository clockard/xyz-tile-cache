package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** WMTS RESTful layer: substitutes {@code {TileMatrix}/{TileRow}/{TileCol}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WmtsRestLayer(
    String id,
    String name,
    String urlTemplate,
    String attribution,
    int maxZoom,
    int initZoom,
    int tileExpirationMinutes,
    List<String> allowedUsers,
    List<String> allowedGroups,
    Map<String, String> headers,
    String timeFormat)
    implements Layer {

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
