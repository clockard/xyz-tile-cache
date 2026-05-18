package org.lockard.xyztilecache.pmtiles;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.VectorConfiguration;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.TileResult;

class VectorTileRemoteCacheTest {

  @TempDir Path tempDir;

  private VectorTileRemoteCache cache(String dir, long minFreeBytes) {
    VectorConfiguration vConfig = new VectorConfiguration();
    vConfig.setDownloadDirectory(dir);
    XyzConfiguration xConfig = new XyzConfiguration();
    xConfig.setMinFreeDiskBytes(minFreeBytes);
    return new VectorTileRemoteCache(vConfig, xConfig);
  }

  // ── get() ─────────────────────────────────────────────────────────────────

  @Test
  void get_notConfigured_returnsEmpty() {
    assertThat(cache("", 0).get(0, 0, 0)).isEmpty();
  }

  @Test
  void get_fileMissing_returnsEmpty() {
    assertThat(cache(tempDir.toString(), 0).get(1, 2, 3)).isEmpty();
  }

  @Test
  void get_regularData_returnsWithNoCompression() throws IOException {
    VectorTileRemoteCache c = cache(tempDir.toString(), 0);
    byte[] data = {0x0a, 0x0b, 0x0c};
    Path path = c.cachePath(0, 0, 0);
    Files.createDirectories(path.getParent());
    Files.write(path, data);

    Optional<TileResult> result = c.get(0, 0, 0);
    assertThat(result).isPresent();
    assertThat(result.get().tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_NONE);
    assertThat(result.get().data()).isEqualTo(data);
  }

  @Test
  void get_gzipMagicBytes_returnsWithGzipCompression() throws IOException {
    VectorTileRemoteCache c = cache(tempDir.toString(), 0);
    byte[] data = {0x1f, (byte) 0x8b, 0x08, 0x00};
    Path path = c.cachePath(1, 1, 1);
    Files.createDirectories(path.getParent());
    Files.write(path, data);

    Optional<TileResult> result = c.get(1, 1, 1);
    assertThat(result).isPresent();
    assertThat(result.get().tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_GZIP);
  }

  @Test
  void get_singleByteData_treatedAsNoCompression() throws IOException {
    VectorTileRemoteCache c = cache(tempDir.toString(), 0);
    byte[] data = {0x1f}; // only 1 byte — isGzip requires >= 2
    Path path = c.cachePath(2, 2, 2);
    Files.createDirectories(path.getParent());
    Files.write(path, data);

    Optional<TileResult> result = c.get(2, 2, 2);
    assertThat(result).isPresent();
    assertThat(result.get().tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_NONE);
  }

  // ── store() ────────────────────────────────────────────────────────────────

  @Test
  void store_notConfigured_doesNothing() {
    // Should not throw even with blank dir
    cache("", 0).store(0, 0, 0, new TileResult(new byte[] {1}, PmtilesHeader.COMPRESSION_NONE));
  }

  @Test
  void store_diskSpaceBelowMinimum_doesNotWrite() {
    VectorTileRemoteCache c = cache(tempDir.toString(), Long.MAX_VALUE);
    c.store(5, 5, 5, new TileResult(new byte[] {1, 2, 3}, PmtilesHeader.COMPRESSION_NONE));
    assertThat(c.cachePath(5, 5, 5)).doesNotExist();
  }

  @Test
  void store_writesFileToDisk() {
    VectorTileRemoteCache c = cache(tempDir.toString(), 0);
    byte[] data = {0x0a, 0x0b};
    c.store(3, 3, 3, new TileResult(data, PmtilesHeader.COMPRESSION_NONE));
    assertThat(c.cachePath(3, 3, 3)).exists();
  }

  // ── cachePath() ────────────────────────────────────────────────────────────

  @Test
  void cachePath_returnsExpectedPath() {
    VectorTileRemoteCache c = cache(tempDir.toString(), 0);
    Path expected = tempDir.resolve("remote-cache").resolve("4").resolve("5").resolve("6.pbf");
    assertThat(c.cachePath(4, 5, 6)).isEqualTo(expected);
  }
}
