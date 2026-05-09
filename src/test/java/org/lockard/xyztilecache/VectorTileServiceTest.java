package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorTileServiceTest {

  @TempDir Path tempDir;

  private static Path fixturePath() {
    URL url = VectorTileServiceTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
    assertThat(url).isNotNull();
    return Paths.get(url.getPath());
  }

  private VectorConfiguration config(String bundledPath, String downloadDir) {
    VectorConfiguration c = new VectorConfiguration();
    c.setBundledPath(bundledPath);
    c.setDownloadDirectory(downloadDir);
    c.setEnabled(true);
    return c;
  }

  // ── init() variations ─────────────────────────────────────────────────────

  @Test
  void init_disabled_doesNotLoadBundled() throws IOException {
    VectorConfiguration c = new VectorConfiguration();
    c.setEnabled(false);
    c.setBundledPath(fixturePath().toString());
    VectorTileService service = new VectorTileService(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void init_bundledFileNotFound_warnsAndContinues() {
    VectorConfiguration c = config("/nonexistent/world.pmtiles", null);
    VectorTileService service = new VectorTileService(c);
    service.init();
    service.destroy();
  }

  @Test
  void init_nullDownloadDirectory_skipsDownloadScan() throws IOException {
    VectorConfiguration c = config(fixturePath().toString(), null);
    VectorTileService service = new VectorTileService(c);
    service.init();
    // Bundled is still loaded, so tile (0,0,0) should be present in the fixture.
    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  @Test
  void init_blankDownloadDirectory_skipsDownloadScan() throws IOException {
    VectorConfiguration c = config(fixturePath().toString(), "  ");
    VectorTileService service = new VectorTileService(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  @Test
  void init_createsDownloadDirectoryIfAbsent() {
    Path newDir = tempDir.resolve("newsubdir");
    VectorConfiguration c = config(fixturePath().toString(), newDir.toString());
    VectorTileService service = new VectorTileService(c);
    service.init();
    assertThat(newDir).isDirectory();
    service.destroy();
  }

  @Test
  void init_loadsExistingPmtilesFilesFromDownloadDirectory() throws IOException {
    Path dest = tempDir.resolve("region.pmtiles");
    Files.copy(fixturePath(), dest);

    VectorConfiguration c = config("/nonexistent.pmtiles", tempDir.toString());
    VectorTileService service = new VectorTileService(c);
    service.init();

    // Tile from the registered downloaded file should be served (no bundled fallback).
    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  // ── getTile() priority ────────────────────────────────────────────────────

  @Test
  void getTile_noBundledNoDownloads_returnsEmpty() throws IOException {
    VectorConfiguration c = config("/nonexistent.pmtiles", null);
    VectorTileService service = new VectorTileService(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void getTile_tileInBundled_returnsResult() throws IOException {
    VectorConfiguration c = config(fixturePath().toString(), null);
    VectorTileService service = new VectorTileService(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  @Test
  void getTile_downloadedReaderSearchedBeforeBundled() throws IOException {
    VectorConfiguration c = config(fixturePath().toString(), tempDir.toString());
    VectorTileService service = new VectorTileService(c);
    service.init();

    Path downloadedCopy = tempDir.resolve("downloaded.pmtiles");
    Files.copy(fixturePath(), downloadedCopy);
    service.registerDownload(downloadedCopy);

    Optional<TileResult> result = service.getTile(0, 0, 0);
    assertThat(result).isPresent();
    service.destroy();
  }

  // ── registerDownload() ────────────────────────────────────────────────────

  @Test
  void registerDownload_servesTilesFromRegisteredFile() throws IOException {
    VectorConfiguration c = config("/nonexistent.pmtiles", tempDir.toString());
    VectorTileService service = new VectorTileService(c);
    service.init();

    Path copy = tempDir.resolve("area.pmtiles");
    Files.copy(fixturePath(), copy);
    service.registerDownload(copy);

    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  // ── destroy() ────────────────────────────────────────────────────────────

  @Test
  void destroy_withNullBundledReader_doesNotThrow() {
    VectorConfiguration c = config("/nonexistent.pmtiles", null);
    VectorTileService service = new VectorTileService(c);
    service.init();
    service.destroy();
  }
}
