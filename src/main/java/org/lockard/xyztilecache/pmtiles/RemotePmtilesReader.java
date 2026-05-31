package org.lockard.xyztilecache.pmtiles;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.lockard.xyztilecache.model.TileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemotePmtilesReader {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemotePmtilesReader.class);
  private static final long INITIAL_BACKOFF_MS = 30_000L;
  private static final long MAX_BACKOFF_MS = 300_000L;

  private final String url;
  private final HttpClient httpClient;
  private final int timeoutSeconds;

  private volatile PmtilesHeader header;
  private volatile List<PmtilesEntry> rootDir;
  private final Cache<Long, List<PmtilesEntry>> leafCache;
  private final Object initLock = new Object();
  private volatile long lastInitAttemptMs = 0L;
  private volatile long nextBackoffMs = INITIAL_BACKOFF_MS;

  public RemotePmtilesReader(String url, HttpClient httpClient, int timeoutSeconds) {
    this.url = url;
    this.httpClient = httpClient;
    this.timeoutSeconds = timeoutSeconds;
    this.leafCache = CacheBuilder.newBuilder().maximumSize(64).build();
  }

  public Optional<TileResult> getTile(int z, int x, int y) throws IOException {
    ensureInitialized();
    if (header == null) {
      return Optional.empty();
    }

    long id = PmtilesReader.tileId(z, x, y);
    PmtilesEntry entry = PmtilesReader.findEntry(rootDir, id);
    if (entry == null) {
      return Optional.empty();
    }

    if (entry.runLength() == 0) {
      List<PmtilesEntry> leaf = loadLeaf(entry);
      entry = PmtilesReader.findEntry(leaf, id);
      if (entry == null || entry.runLength() == 0) {
        return Optional.empty();
      }
    }

    if (id >= entry.tileId() && id < entry.tileId() + entry.runLength()) {
      byte[] data = fetchRange(header.tileDataOffset() + entry.offset(), entry.length());
      return Optional.of(new TileResult(data, header.tileCompression(), "application/x-protobuf"));
    }

    return Optional.empty();
  }

  private void ensureInitialized() {
    if (header != null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastInitAttemptMs < nextBackoffMs) {
      return;
    }
    synchronized (initLock) {
      if (header != null) {
        return;
      }
      now = System.currentTimeMillis();
      if (now - lastInitAttemptMs < nextBackoffMs) {
        return;
      }
      lastInitAttemptMs = now;
      try {
        byte[] headerBytes = fetchRange(0, 127);
        PmtilesHeader parsed = PmtilesHeader.parse(headerBytes);
        byte[] rootRaw = fetchRange(parsed.rootDirOffset(), parsed.rootDirLength());
        List<PmtilesEntry> parsedRoot =
            PmtilesReader.decodeDirectory(
                PmtilesReader.decompress(rootRaw, parsed.internalCompression()));
        rootDir = parsedRoot;
        header = parsed; // set last — guards the success check above
        nextBackoffMs = INITIAL_BACKOFF_MS;
        LOGGER.info("RemotePmtilesReader initialized from {}", url);
      } catch (IOException | RuntimeException e) {
        LOGGER.warn(
            "Failed to initialize RemotePmtilesReader from {}: {} (will retry after {} ms)",
            url,
            e.getMessage(),
            nextBackoffMs);
        nextBackoffMs = Math.min(nextBackoffMs * 2, MAX_BACKOFF_MS);
      }
    }
  }

  private List<PmtilesEntry> loadLeaf(PmtilesEntry pointer) throws IOException {
    try {
      return leafCache.get(
          pointer.offset(),
          () -> {
            byte[] raw = fetchRange(header.leafDirsOffset() + pointer.offset(), pointer.length());
            return PmtilesReader.decodeDirectory(
                PmtilesReader.decompress(raw, header.internalCompression()));
          });
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException ioe) throw ioe;
      throw new IOException("Failed to load remote leaf directory", cause);
    }
  }

  private byte[] fetchRange(long offset, long length) throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Range", "bytes=" + offset + "-" + (offset + length - 1))
            .GET()
            .timeout(Duration.of(timeoutSeconds, ChronoUnit.SECONDS))
            .build();
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 206 && response.statusCode() != 200) {
        throw new IOException(
            "Range request to " + url + " failed with HTTP " + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while fetching range from " + url, e);
    }
  }
}
