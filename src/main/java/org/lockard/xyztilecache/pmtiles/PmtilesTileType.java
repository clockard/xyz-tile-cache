package org.lockard.xyztilecache.pmtiles;

/**
 * What a PMTiles archive holds, from the {@code tile_type} byte in its v3 header.
 *
 * <p>An archive declares exactly one tile type for all of its tiles, so this is a property of the
 * file rather than of any individual tile — which is what lets a layer settle on a single type and
 * reject archives that disagree.
 *
 * <p>MVT keeps the {@code application/x-protobuf} content type this cache has always served for
 * vector tiles. The registered type is {@code application/vnd.mapbox-vector-tile}, but clients are
 * already consuming the former and it costs nothing to stay compatible.
 */
public enum PmtilesTileType {
  UNKNOWN(0, "application/octet-stream", "bin"),
  MVT(1, "application/x-protobuf", "pbf"),
  PNG(2, "image/png", "png"),
  JPEG(3, "image/jpeg", "jpg"),
  WEBP(4, "image/webp", "webp"),
  AVIF(5, "image/avif", "avif");

  private final int headerValue;
  private final String contentType;
  private final String extension;

  PmtilesTileType(int headerValue, String contentType, String extension) {
    this.headerValue = headerValue;
    this.contentType = contentType;
    this.extension = extension;
  }

  public int headerValue() {
    return headerValue;
  }

  /** Content type to serve tiles of this kind under. */
  public String contentType() {
    return contentType;
  }

  /** Extension used for individually cached tiles of this kind on disk. */
  public String extension() {
    return extension;
  }

  public boolean isVector() {
    return this == MVT;
  }

  /**
   * Maps a header {@code tile_type} byte. An unrecognised value — a type added to the spec after
   * this was written — reads as {@link #UNKNOWN} rather than failing, so the archive still serves;
   * its tiles just go out as opaque bytes.
   */
  public static PmtilesTileType fromHeaderValue(int value) {
    for (PmtilesTileType type : values()) {
      if (type.headerValue == value) {
        return type;
      }
    }
    return UNKNOWN;
  }
}
