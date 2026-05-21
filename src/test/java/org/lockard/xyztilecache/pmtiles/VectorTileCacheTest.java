package org.lockard.xyztilecache.pmtiles;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.TileResult;

class VectorTileCacheTest {

  @TempDir Path tempDir;

  private VectorTileCache cache(Path dir, long minFreeBytes) {
    XyzConfiguration xConfig = new XyzConfiguration();
    xConfig.setMinFreeDiskBytes(minFreeBytes);
    return new VectorTileCache(dir, xConfig);
  }

  // ── get() ─────────────────────────────────────────────────────────────────

  @Test
  void get_fileMissing_returnsEmpty() {
    assertThat(cache(tempDir, 0).get(1, 2, 3)).isEmpty();
  }

  @Test
  void get_regularData_returnsWithNoCompression() throws IOException {
    VectorTileCache c = cache(tempDir, 0);
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
    VectorTileCache c = cache(tempDir, 0);
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
    VectorTileCache c = cache(tempDir, 0);
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
  void store_diskSpaceBelowMinimum_doesNotWrite() {
    VectorTileCache c = cache(tempDir, Long.MAX_VALUE);
    c.store(
        5,
        5,
        5,
        new TileResult(
            new byte[] {1, 2, 3}, PmtilesHeader.COMPRESSION_NONE, "application/x-protobuf"));
    assertThat(c.cachePath(5, 5, 5)).doesNotExist();
  }

  @Test
  void store_writesFileToDisk() {
    VectorTileCache c = cache(tempDir, 0);
    byte[] data = {0x0a, 0x0b};
    c.store(
        3, 3, 3, new TileResult(data, PmtilesHeader.COMPRESSION_NONE, "application/x-protobuf"));
    assertThat(c.cachePath(3, 3, 3)).exists();
  }

  // ── cachePath() ────────────────────────────────────────────────────────────

  @Test
  void cachePath_returnsExpectedPath() {
    VectorTileCache c = cache(tempDir, 0);
    Path expected = tempDir.resolve("4").resolve("5").resolve("6.pbf");
    assertThat(c.cachePath(4, 5, 6)).isEqualTo(expected);
  }
}
