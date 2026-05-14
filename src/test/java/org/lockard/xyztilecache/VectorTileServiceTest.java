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

  private VectorConfiguration config(String downloadDir) {
    VectorConfiguration c = new VectorConfiguration();
    c.setDownloadDirectory(downloadDir);
    c.setEnabled(true);
    return c;
  }

  private VectorTileService service(VectorConfiguration c) {
    XyzConfiguration xyz = new XyzConfiguration();
    xyz.setBaseTileDirectory(tempDir.toString());
    xyz.setOffline(true); // disable remote PMTiles reader in unit tests
    return new VectorTileService(c, xyz, new VectorTileRemoteCache(c, xyz));
  }

  // ── init() variations ─────────────────────────────────────────────────────

  @Test
  void init_disabled_returnsEmpty() throws IOException {
    VectorConfiguration c = new VectorConfiguration();
    c.setEnabled(false);
    VectorTileService service = service(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void init_nullDownloadDirectory_returnsEmpty() throws IOException {
    VectorConfiguration c = config(null);
    VectorTileService service = service(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void init_blankDownloadDirectory_returnsEmpty() throws IOException {
    VectorConfiguration c = config("  ");
    VectorTileService service = service(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void init_createsDownloadDirectoryIfAbsent() {
    Path newDir = tempDir.resolve("newsubdir");
    VectorConfiguration c = config(newDir.toString());
    VectorTileService service = service(c);
    service.init();
    assertThat(newDir).isDirectory();
    service.destroy();
  }

  @Test
  void init_loadsExistingPmtilesFilesFromDownloadDirectory() throws IOException {
    Path dest = tempDir.resolve("region.pmtiles");
    Files.copy(fixturePath(), dest);

    VectorConfiguration c = config(tempDir.toString());
    VectorTileService service = service(c);
    service.init();

    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  // ── getTile() ────────────────────────────────────────────────────────────

  @Test
  void getTile_noDownloads_returnsEmpty() throws IOException {
    VectorConfiguration c = config(null);
    VectorTileService service = service(c);
    service.init();
    assertThat(service.getTile(0, 0, 0)).isEmpty();
    service.destroy();
  }

  @Test
  void getTile_downloadedReader_returnsResult() throws IOException {
    VectorConfiguration c = config(tempDir.toString());
    VectorTileService service = service(c);
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
    VectorConfiguration c = config(tempDir.toString());
    VectorTileService service = service(c);
    service.init();

    Path copy = tempDir.resolve("area.pmtiles");
    Files.copy(fixturePath(), copy);
    service.registerDownload(copy);

    assertThat(service.getTile(0, 0, 0)).isPresent();
    service.destroy();
  }

  // ── destroy() ────────────────────────────────────────────────────────────

  @Test
  void destroy_withNoReaders_doesNotThrow() {
    VectorConfiguration c = config(null);
    VectorTileService service = service(c);
    service.init();
    service.destroy();
  }
}
