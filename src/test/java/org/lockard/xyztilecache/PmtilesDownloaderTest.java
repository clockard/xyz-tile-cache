package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PmtilesDownloaderTest {

  @TempDir Path tempDir;

  private VectorConfiguration config(Path dir) {
    VectorConfiguration c = new VectorConfiguration();
    c.setSourceUrl("https://example.com/planet.pmtiles");
    c.setDownloadDirectory(dir.toString());
    c.setMaxDownloadZoom(14);
    return c;
  }

  private Preload preload(double w, double s, double e, double n, int zoom) {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(w);
    bbox.setSouth(s);
    bbox.setEast(e);
    bbox.setNorth(n);
    Preload p = new Preload();
    p.setId(UUID.randomUUID().toString());
    p.setName("test");
    p.setBoundingBox(bbox);
    p.setMaxZoom(zoom);
    p.setIncludesVector(true);
    p.setPmtilesFilename("test.pmtiles");
    return p;
  }

  // ── resolveSourceUrl ─────────────────────────────────────────────────────

  @Test
  void resolveSourceUrl_noPlaceholder_unchanged() {
    String url = "https://example.com/planet.pmtiles";
    assertThat(PmtilesDownloader.resolveSourceUrl(url)).isEqualTo(url);
  }

  @Test
  void resolveSourceUrl_datePlaceholder_replacedWithToday() {
    String today = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
    String resolved =
        PmtilesDownloader.resolveSourceUrl("https://build.protomaps.com/{date}.pmtiles");
    assertThat(resolved).isEqualTo("https://build.protomaps.com/" + today + ".pmtiles");
  }

  @Test
  void resolveSourceUrl_null_returnsNull() {
    assertThat(PmtilesDownloader.resolveSourceUrl(null)).isNull();
  }

  // ── CLI argument tests ────────────────────────────────────────────────────

  @Test
  void buildProcess_correctCliArgs() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    Preload p = preload(-74.0, 40.5, -73.5, 41.0, 12);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    ProcessBuilder pb = downloader.buildProcess(p, tempDir.resolve("test.pmtiles"));
    assertThat(pb.command()).contains("pmtiles", "extract");
    assertThat(pb.command()).contains(cfg.getSourceUrl());
    assertThat(pb.command()).anyMatch(a -> a.startsWith("--bbox="));
    assertThat(pb.command()).contains("--maxzoom=12");
  }

  @Test
  void buildProcess_bboxUsesUsDotSeparator() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    Preload p = preload(-73.1234, 40.5678, -73.0, 41.0, 14);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    ProcessBuilder pb = downloader.buildProcess(p, tempDir.resolve("test.pmtiles"));
    String bboxArg = pb.command().stream().filter(a -> a.startsWith("--bbox=")).findFirst().get();
    assertThat(bboxArg).matches("--bbox=-?\\d+\\.\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+");
  }

  @Test
  void startDownload_blankFilename_throws() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    Preload p = preload(-1, -1, 1, 1, 5);
    p.setPmtilesFilename("   ");
    assertThatThrownBy(() -> downloader.startDownload(p))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void startDownload_missingFilename_throws() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    Preload p = preload(-1, -1, 1, 1, 5);
    p.setPmtilesFilename(null);
    assertThatThrownBy(() -> downloader.startDownload(p))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void startDownload_pathEscape_doesNotRegister() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    Preload p = preload(-1, -1, 1, 1, 5);
    p.setPmtilesFilename("../../etc/passwd");
    downloader.startDownload(p).get();
    verify(service, never()).registerDownload(any());
  }

  @Test
  void buildProcess_rejectsUnsafePath() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);
    PmtilesDownloader downloader = new PmtilesDownloader(cfg, service);
    Preload p = preload(-1, -1, 1, 1, 5);
    assertThatThrownBy(() -> downloader.buildProcess(p, Path.of("/tmp/evil;injected")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── Concurrent download guard ─────────────────────────────────────────────

  @Test
  void startDownload_inProgress_flagIsTrue() {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            started.countDown();
            try {
              proceed.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return new ProcessBuilder("true");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5));
    try {
      started.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(downloader.isDownloadInProgress()).isTrue();
    proceed.countDown();
  }

  @Test
  void startDownload_afterCompletion_flagIsFalse() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            return new ProcessBuilder("true"); // succeeds, but creates no file
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5)).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
  }

  @Test
  void startDownload_failedProcess_doesNotRegister() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            return new ProcessBuilder("false");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5)).get();
    verify(service, never()).registerDownload(any());
  }

  @Test
  void startDownload_flagReleasedAfterFailure() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            return new ProcessBuilder("false");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5)).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
  }

  @Test
  void startDownload_processStartFails_doesNotRegisterAndReleasesFlag() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            return new ProcessBuilder("__nonexistent_command_xyz_123456__");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5)).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
    verify(service, never()).registerDownload(any());
  }

  @Test
  void startDownload_concurrentCall_throwsIllegalState() throws Exception {
    VectorTileService service = mock(VectorTileService.class);
    VectorConfiguration cfg = config(tempDir);

    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

    PmtilesDownloader downloader =
        new PmtilesDownloader(cfg, service) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Path out) {
            try {
              latch.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return new ProcessBuilder("true");
          }
        };

    java.util.concurrent.CompletableFuture<Void> first =
        downloader.startDownload(preload(-1, -1, 1, 1, 5));

    assertThatThrownBy(() -> downloader.startDownload(preload(-1, -1, 1, 1, 5)))
        .isInstanceOf(IllegalStateException.class);

    latch.countDown();
    first.get();
  }
}
