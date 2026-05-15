package org.lockard.xyztilecache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

record PmtilesHeader(
    long rootDirOffset,
    long rootDirLength,
    long metadataOffset,
    long metadataLength,
    long leafDirsOffset,
    long leafDirsLength,
    long tileDataOffset,
    long tileDataLength,
    boolean clustered,
    int internalCompression,
    int tileCompression,
    int tileType,
    int minZoom,
    int maxZoom,
    double minLon,
    double minLat,
    double maxLon,
    double maxLat) {

  static final int COMPRESSION_NONE = 1;
  static final int COMPRESSION_GZIP = 2;
  static final int TILE_TYPE_MVT = 1;

  static PmtilesHeader parse(byte[] bytes) {
    if (bytes.length < 127) {
      throw new IllegalArgumentException("PMTiles header must be at least 127 bytes");
    }
    ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

    byte[] magic = new byte[7];
    bb.get(magic);
    if (magic[0] != 'P'
        || magic[1] != 'M'
        || magic[2] != 'T'
        || magic[3] != 'i'
        || magic[4] != 'l'
        || magic[5] != 'e'
        || magic[6] != 's') {
      throw new IllegalArgumentException("Not a PMTiles file: magic bytes mismatch");
    }

    int version = bb.get() & 0xFF;
    if (version != 3) {
      throw new IllegalArgumentException("Unsupported PMTiles spec version: " + version);
    }

    long rootDirOffset = bb.getLong();
    long rootDirLength = bb.getLong();
    long metadataOffset = bb.getLong();
    long metadataLength = bb.getLong();
    long leafDirsOffset = bb.getLong();
    long leafDirsLength = bb.getLong();
    long tileDataOffset = bb.getLong();
    long tileDataLength = bb.getLong();

    // bytes 72-95 are reserved; skip to byte 96
    bb.position(96);

    boolean clustered = bb.get() != 0;
    int internalCompression = bb.get() & 0xFF;
    int tileCompression = bb.get() & 0xFF;
    int tileType = bb.get() & 0xFF;
    int minZoom = bb.get() & 0xFF;
    int maxZoom = bb.get() & 0xFF;

    double minLon = bb.getInt() / 10_000_000.0;
    double minLat = bb.getInt() / 10_000_000.0;
    double maxLon = bb.getInt() / 10_000_000.0;
    double maxLat = bb.getInt() / 10_000_000.0;

    return new PmtilesHeader(
        rootDirOffset,
        rootDirLength,
        metadataOffset,
        metadataLength,
        leafDirsOffset,
        leafDirsLength,
        tileDataOffset,
        tileDataLength,
        clustered,
        internalCompression,
        tileCompression,
        tileType,
        minZoom,
        maxZoom,
        minLon,
        minLat,
        maxLon,
        maxLat);
  }
}
