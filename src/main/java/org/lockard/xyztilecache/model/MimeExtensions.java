package org.lockard.xyztilecache.model;

import java.util.Locale;

/**
 * Maps an image MIME type to the file extension tiles of that type are stored under. Shared by the
 * layer types whose format is declared as a MIME type rather than implied by the URL (WMTS KVP,
 * WMS).
 */
final class MimeExtensions {

  private MimeExtensions() {}

  static String fromMimeType(String mimeType) {
    String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (type.contains("jpeg") || type.contains("jpg")) return "jpg";
    if (type.contains("webp")) return "webp";
    if (type.contains("gif")) return "gif";
    return "png";
  }
}
