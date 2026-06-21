package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** WMTS KVP (key-value-pair) layer. Builds a {@code GetTile} query against the base URL. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WmtsKvpLayer(
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
    String wmtsLayerName,
    String wmtsTileMatrixSet,
    String wmtsStyle,
    String wmtsFormat,
    boolean wmtsTime,
    String timeFormat)
    implements Layer {

  public WmtsKvpLayer {
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
    String f = wmtsFormat == null ? "" : wmtsFormat.toLowerCase(Locale.ROOT);
    if (f.contains("jpeg") || f.contains("jpg")) return "jpg";
    if (f.contains("webp")) return "webp";
    if (f.contains("gif")) return "gif";
    return "png";
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
