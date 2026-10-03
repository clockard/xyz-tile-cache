package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** WMTS KVP (key-value-pair) layer. Builds a {@code GetTile} query against the base URL. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WmtsKvpLayer(
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
    @JsonProperty("wmtsLayerName") String wmtsLayerName,
    @JsonProperty("wmtsTileMatrixSet") String wmtsTileMatrixSet,
    @JsonProperty("wmtsStyle") String wmtsStyle,
    @JsonProperty("wmtsFormat") String wmtsFormat,
    @JsonProperty("wmtsTime") boolean wmtsTime,
    @JsonProperty("timeFormat") String timeFormat)
    implements Layer {
  // Every component is annotated explicitly: Jackson 3 links a record component to its
  // @JsonIgnore'd legacy alias getter in Layer (getId() etc.) and would drop it on read.

  public WmtsKvpLayer {
    // JSON API callers may omit maxZoom (primitive default 0), which would 404 every z>0 tile.
    if (maxZoom <= 0) maxZoom = 22;
    if (wmtsTileMatrixSet == null || wmtsTileMatrixSet.isBlank()) wmtsTileMatrixSet = "EPSG:3857";
    if (wmtsStyle == null || wmtsStyle.isBlank()) wmtsStyle = "default";
    if (wmtsFormat == null || wmtsFormat.isBlank()) wmtsFormat = "image/png";
    if (timeFormat == null || timeFormat.isBlank()) timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  @Override
  public SourceType sourceType() {
    return SourceType.WMTS_KVP;
  }

  @Override
  public String tileFileExtension() {
    return MimeExtensions.fromMimeType(wmtsFormat);
  }

  @Override
  public boolean doesUrlHaveTime() {
    return wmtsTime || Layer.super.doesUrlHaveTime();
  }

  @Override
  public WmtsKvpLayer withId(String newId) {
    return new WmtsKvpLayer(
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
        wmtsLayerName,
        wmtsTileMatrixSet,
        wmtsStyle,
        wmtsFormat,
        wmtsTime,
        timeFormat);
  }

  public String buildUrl(int z, int x, int y, String timeString) {
    StringBuilder sb = new StringBuilder(urlTemplate.length() + 256);
    sb.append(urlTemplate)
        .append("?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0")
        .append("&LAYER=")
        .append(wmtsLayerName)
        .append("&STYLE=")
        .append(wmtsStyle)
        .append("&FORMAT=")
        .append(wmtsFormat)
        .append("&TILEMATRIXSET=")
        .append(wmtsTileMatrixSet)
        .append("&TILEMATRIX=")
        .append(z)
        .append("&TILEROW=")
        .append(y)
        .append("&TILECOL=")
        .append(x);
    if (wmtsTime && timeString != null) {
      sb.append("&TIME=").append(timeString);
    }
    return sb.toString();
  }
}
