package org.lockard.xyztilecache.pmtiles;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import org.lockard.xyztilecache.model.TileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PmtilesReader implements Closeable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesReader.class);
  private final Path localFile;
  private final FileChannel channel;
  private final PmtilesHeader header;
  private final List<PmtilesEntry> rootDir;
  // Key: leaf directory offset within the leaf section; value: decoded entries
  private final Cache<Long, List<PmtilesEntry>> leafCache;

  public PmtilesReader(Path path) throws IOException {
    localFile = path;
    channel = FileChannel.open(path.toAbsolutePath().normalize(), StandardOpenOption.READ);
    try {
      header = PmtilesHeader.parse(readRawBytes(0, 127));
      byte[] rootRaw = readRawBytes(header.rootDirOffset(), header.rootDirLength());
      rootDir = decodeDirectory(decompress(rootRaw, header.internalCompression()));
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
    leafCache = CacheBuilder.newBuilder().maximumSize(64).build();
  }

  public PmtilesHeader getHeader() {
    return header;
  }

  byte[] readMetadata(long offset, long length) throws IOException {
    return readRawBytes(offset, length);
  }

  public Optional<TileResult> getTile(int z, int x, int y) throws IOException {
    long id = tileId(z, x, y);
    PmtilesEntry entry = findEntry(rootDir, id);

    if (entry == null) {
      return Optional.empty();
    }

    if (entry.runLength() == 0) {
      // Leaf directory pointer
      List<PmtilesEntry> leafDir = loadLeaf(entry);
      entry = findEntry(leafDir, id);
      if (entry == null || entry.runLength() == 0) {
        return Optional.empty();
      }
    }

    if (id >= entry.tileId() && id < entry.tileId() + entry.runLength()) {
      byte[] data = readRawBytes(header.tileDataOffset() + entry.offset(), entry.length());
      return Optional.of(new TileResult(data, header.tileCompression(), "application/x-protobuf"));
    }

    return Optional.empty();
  }

  public Path getLocalFile() {
    return localFile;
  }

  @Override
  public void close() throws IOException {
    leafCache.invalidateAll();
    channel.close();
  }

  // ── Internal helpers ──────────────────────────────────────────────────────

  private List<PmtilesEntry> loadLeaf(PmtilesEntry leafPointer) throws IOException {
    try {
      return leafCache.get(
          leafPointer.offset(),
          () -> {
            byte[] raw =
                readRawBytes(header.leafDirsOffset() + leafPointer.offset(), leafPointer.length());
            return decodeDirectory(decompress(raw, header.internalCompression()));
          });
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException ioe) throw ioe;
      throw new IOException("Failed to load leaf directory", cause);
    }
  }

  private byte[] readRawBytes(long offset, long length) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate((int) length);
    long pos = offset;
    while (buf.hasRemaining()) {
      int read = channel.read(buf, pos);
      if (read < 0) {
        throw new IOException(
            "Unexpected EOF reading "
                + length
                + " bytes at offset "
                + offset
                + " from "
                + localFile);
      }
      pos += read;
    }
    return buf.array();
  }

  static byte[] decompress(byte[] data, int compression) throws IOException {
    if (compression == PmtilesHeader.COMPRESSION_NONE || compression == 0) {
      return data;
    }
    if (compression == PmtilesHeader.COMPRESSION_GZIP) {
      try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(data))) {
        return gzis.readAllBytes();
      }
    }
    throw new UnsupportedOperationException("Unsupported PMTiles compression: " + compression);
  }

  static List<PmtilesEntry> decodeDirectory(byte[] raw) {
    ByteBuffer buf = ByteBuffer.wrap(raw);
    int n = (int) readVarint(buf);

    long[] tileIds = new long[n];
    int[] runLengths = new int[n];
    int[] lengths = new int[n];
    long[] offsets = new long[n];

    // Pass 1: tile ID deltas (running sum)
    long runningId = 0;
    for (int i = 0; i < n; i++) {
      runningId += readVarint(buf);
      tileIds[i] = runningId;
    }

    // Pass 2: run lengths
    for (int i = 0; i < n; i++) {
      runLengths[i] = (int) readVarint(buf);
    }

    // Pass 3: data lengths
    for (int i = 0; i < n; i++) {
      lengths[i] = (int) readVarint(buf);
    }

    // Pass 4: offsets — stored as (value + 1); 0 is the clustered delta sentinel
    for (int i = 0; i < n; i++) {
      long stored = readVarint(buf);
      if (stored == 0 && i > 0) {
        offsets[i] = offsets[i - 1] + lengths[i - 1];
      } else {
        offsets[i] = stored - 1;
      }
    }

    List<PmtilesEntry> entries = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      entries.add(new PmtilesEntry(tileIds[i], runLengths[i], lengths[i], offsets[i]));
    }
    return entries;
  }

  static long readVarint(ByteBuffer buf) {
    long result = 0;
    int shift = 0;
    while (true) {
      byte b = buf.get();
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) break;
      shift += 7;
    }
    return result;
  }

  // Binary search: find the last entry where entry.tileId() <= target
  static PmtilesEntry findEntry(List<PmtilesEntry> dir, long target) {
    int lo = 0;
    int hi = dir.size() - 1;
    PmtilesEntry result = null;

    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      PmtilesEntry e = dir.get(mid);
      if (e.tileId() <= target) {
        result = e;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }

    return result;
  }

  // ── Hilbert curve tile ID ─────────────────────────────────────────────────

  static long tileId(int z, int x, int y) {
    long faceOffset = ((1L << (2 * z)) - 1) / 3; // (4^z - 1) / 3
    return faceOffset + xyToHilbert(1 << z, x, y);
  }

  private static long xyToHilbert(int n, int x, int y) {
    long d = 0;
    for (int s = n / 2; s > 0; s /= 2) {
      int rx = (x & s) > 0 ? 1 : 0;
      int ry = (y & s) > 0 ? 1 : 0;
      d += (long) s * s * ((3 * rx) ^ ry);
      if (ry == 0) {
        if (rx == 1) {
          x = s - 1 - x;
          y = s - 1 - y;
        }
        int t = x;
        x = y;
        y = t;
      }
    }
    return d;
  }
}
