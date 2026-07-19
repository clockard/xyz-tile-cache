package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** XYZ raster layer: {@code https://example.com/{z}/{x}/{y}.png}-style templated URL. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XyzLayer(
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

  public XyzLayer {
    // JSON API callers may omit maxZoom (primitive default 0), which would 404 every z>0 tile.
    if (maxZoom <= 0) maxZoom = 22;
    if (timeFormat == null || timeFormat.isBlank()) timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  @Override
  public SourceType sourceType() {
    return SourceType.XYZ;
  }

  @Override
  public String tileFileExtension() {
    return UrlExtensions.fromUrl(urlTemplate);
  }

  @Override
  public XyzLayer withId(String newId) {
    return new XyzLayer(
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

  /** Builds the upstream tile URL by substituting z/x/y/time. */
  public String buildUrl(int z, int x, int y, String timeString) {
    String url =
        urlTemplate
            .replace("{x}", String.valueOf(x))
            .replace("{y}", String.valueOf(y))
            .replace("{z}", String.valueOf(z));
    if (doesUrlHaveTime() && timeString != null) {
      url = url.replace("{time}", timeString);
    }
    return url;
  }

  /**
   * Shared URL-extension sniffing used by XyzLayer/LocalLayer. Public to keep both records small.
   */
  static final class UrlExtensions {
    private UrlExtensions() {}

    static String fromUrl(String url) {
      if (url == null) return "png";
      String s = url.toLowerCase(Locale.ROOT);
      int q = s.indexOf('?');
      if (q >= 0) s = s.substring(0, q);
      int dot = s.lastIndexOf('.');
      if (dot < 0) return "png";
      String ext = s.substring(dot + 1);
      return switch (ext) {
        case "jpg", "jpeg" -> "jpg";
        case "webp" -> "webp";
        case "gif" -> "gif";
        case "png" -> "png";
        default -> "png";
      };
    }
  }
}
