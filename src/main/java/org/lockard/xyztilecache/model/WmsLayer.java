package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lockard.xyztilecache.XyzUtil;

/**
 * WMS layer: turns each XYZ tile into a {@code GetMap} request for that tile's extent.
 *
 * <p>Tiles are always requested in {@code EPSG:3857}. WMS 1.3.0 reversed the axis order of
 * geographic CRSs relative to 1.1.1, so a {@code BBOX} in EPSG:4326 means {@code lat,lon} under one
 * version and {@code lon,lat} under the other — the classic source of maps that come back
 * transposed. Web Mercator is easting/northing in both versions, and it is the grid tiles are
 * served on anyway, so requesting it sidesteps the ambiguity entirely. Servers that cannot deliver
 * EPSG:3857 are not supported.
 *
 * <p>The version only changes the name of the CRS parameter: 1.3.0 spells it {@code CRS}, 1.1.1
 * spells it {@code SRS}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WmsLayer(
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
    String wmsLayers,
    String wmsStyles,
    String wmsFormat,
    String wmsVersion,
    boolean wmsTransparent,
    int wmsTileSize,
    boolean wmsTime,
    Map<String, String> wmsExtraParams,
    String timeFormat)
    implements Layer {

  private static final String CRS = "EPSG:3857";
  private static final String VERSION_1_1_1 = "1.1.1";

  public WmsLayer {
    // JSON API callers may omit maxZoom (primitive default 0), which would 404 every z>0 tile.
    if (maxZoom <= 0) maxZoom = 22;
    if (wmsStyles == null) wmsStyles = "";
    if (wmsFormat == null || wmsFormat.isBlank()) wmsFormat = "image/png";
    if (wmsVersion == null || wmsVersion.isBlank()) wmsVersion = "1.3.0";
    if (wmsTileSize <= 0) wmsTileSize = 256;
    if (timeFormat == null || timeFormat.isBlank()) timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    // Sorted so a generated URL is stable across runs, which keeps tests and logs readable.
    wmsExtraParams = wmsExtraParams == null ? Map.of() : Map.copyOf(new TreeMap<>(wmsExtraParams));
  }

  @Override
  public SourceType sourceType() {
    return SourceType.WMS;
  }

  @Override
  public String tileFileExtension() {
    return MimeExtensions.fromMimeType(wmsFormat);
  }

  @Override
  public boolean doesUrlHaveTime() {
    return wmsTime || Layer.super.doesUrlHaveTime();
  }

  @Override
  public WmsLayer withId(String newId) {
    return new WmsLayer(
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
        wmsLayers,
        wmsStyles,
        wmsFormat,
        wmsVersion,
        wmsTransparent,
        wmsTileSize,
        wmsTime,
        wmsExtraParams,
        timeFormat);
  }

  /** True when the server speaks WMS 1.1.1, which names the CRS parameter {@code SRS}. */
  @JsonIgnore
  public boolean isVersion111() {
    return VERSION_1_1_1.equals(wmsVersion);
  }

  /** Builds the {@code GetMap} request for one tile. */
  public String buildUrl(int z, int x, int y, String timeString) {
    XyzUtil.Bounds3857 bounds = XyzUtil.tileBounds3857(x, y, z);
    StringBuilder sb = new StringBuilder(urlTemplate.length() + 256);
    // A WMS endpoint is commonly published with its own query string already attached
    // (".../ows?service=WMS"), so the first separator depends on what the base URL carries.
    sb.append(urlTemplate).append(urlTemplate.contains("?") ? '&' : '?');
    append(sb, "SERVICE", "WMS");
    append(sb, "REQUEST", "GetMap");
    append(sb, "VERSION", wmsVersion);
    append(sb, "LAYERS", wmsLayers == null ? "" : wmsLayers);
    append(sb, "STYLES", wmsStyles);
    append(sb, "FORMAT", wmsFormat);
    append(sb, "TRANSPARENT", wmsTransparent ? "TRUE" : "FALSE");
    append(sb, isVersion111() ? "SRS" : "CRS", CRS);
    append(sb, "BBOX", formatBbox(bounds));
    append(sb, "WIDTH", Integer.toString(wmsTileSize));
    append(sb, "HEIGHT", Integer.toString(wmsTileSize));
    if (wmsTime && timeString != null) {
      append(sb, "TIME", timeString);
    }
    wmsExtraParams.forEach((k, v) -> append(sb, k, v == null ? "" : v));
    // Drop the trailing separator left by the last parameter.
    sb.setLength(sb.length() - 1);
    return sb.toString();
  }

  private static void append(StringBuilder sb, String key, String value) {
    sb.append(encode(key)).append('=').append(encode(value)).append('&');
  }

  /**
   * Percent-encodes a query value. {@code URLEncoder} targets form encoding, where a space is
   * {@code +}; in a URL query a literal {@code %20} is the safer spelling for servers that decode
   * the raw path.
   */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /**
   * Formats the extent as {@code minx,miny,maxx,maxy}. Six decimals is under a micrometre, far
   * finer than a tile is wide at any supported zoom, and avoids the scientific notation a plain
   * {@code Double.toString} would produce for small values.
   */
  private static String formatBbox(XyzUtil.Bounds3857 b) {
    return String.format(
        java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", b.minX(), b.minY(), b.maxX(), b.maxY());
  }
}
