package org.lockard.xyztilecache.service;

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
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;

class PmtilesDownloaderTest {

  @TempDir Path tempDir;

  private XyzConfiguration xyzConfig() {
    XyzConfiguration c = new XyzConfiguration();
    c.setBaseTileDirectory(tempDir.toString());
    return c;
  }

  private Layer layer(String urlTemplate, int maxZoom) {
    Layer l = new Layer();
    l.setId("test-layer");
    l.setName("Test Layer");
    l.setSourceType(Layer.SourceType.VECTOR_PMTILES);
    l.setUrlTemplate(urlTemplate);
    l.setMaxZoom(maxZoom);
    return l;
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
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/planet.pmtiles", 12);
    Preload p = preload(-74.0, 40.5, -73.5, 41.0, 12);
    PmtilesDownloader downloader = new PmtilesDownloader(xyzConfig(), manager);
    ProcessBuilder pb = downloader.buildProcess(p, l, tempDir.resolve("test.pmtiles"));
    assertThat(pb.command()).contains("pmtiles", "extract");
    assertThat(pb.command()).contains(l.getUrlTemplate());
    assertThat(pb.command()).anyMatch(a -> a.startsWith("--bbox="));
    assertThat(pb.command()).contains("--maxzoom=12");
  }

  @Test
  void buildProcess_bboxUsesUsDotSeparator() {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/planet.pmtiles", 14);
    Preload p = preload(-73.1234, 40.5678, -73.0, 41.0, 14);
    PmtilesDownloader downloader = new PmtilesDownloader(xyzConfig(), manager);
    ProcessBuilder pb = downloader.buildProcess(p, l, tempDir.resolve("test.pmtiles"));
    String bboxArg = pb.command().stream().filter(a -> a.startsWith("--bbox=")).findFirst().get();
    assertThat(bboxArg).matches("--bbox=-?\\d+\\.\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+");
  }

  // ── outputFilename ────────────────────────────────────────────────────────

  @Test
  void outputFilename_sanitizesSpecialChars() {
    assertThat(PmtilesDownloader.outputFilename("my region/2024"))
        .isEqualTo("my_region_2024.pmtiles");
  }

  @Test
  void outputFilename_safeCharsUnchanged() {
    assertThat(PmtilesDownloader.outputFilename("region-A.1")).isEqualTo("region-A.1.pmtiles");
  }

  @Test
  void buildProcess_rejectsUnsafePath() {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);
    Preload p = preload(-1, -1, 1, 1, 5);
    PmtilesDownloader downloader = new PmtilesDownloader(xyzConfig(), manager);
    assertThatThrownBy(() -> downloader.buildProcess(p, l, Path.of("/tmp/evil;injected")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── Concurrent download guard ─────────────────────────────────────────────

  @Test
  void startDownload_inProgress_flagIsTrue() {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            started.countDown();
            try {
              proceed.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return new ProcessBuilder("true");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5), l);
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
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            return new ProcessBuilder("true");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5), l).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
  }

  @Test
  void startDownload_failedProcess_doesNotCallManager() throws Exception {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            return new ProcessBuilder("false");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5), l).get();
    verify(manager, never()).initLayer(any());
  }

  @Test
  void startDownload_flagReleasedAfterFailure() throws Exception {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            return new ProcessBuilder("false");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5), l).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
  }

  @Test
  void startDownload_processStartFails_doesNotCallManagerAndReleasesFlag() throws Exception {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            return new ProcessBuilder("__nonexistent_command_xyz_123456__");
          }
        };

    downloader.startDownload(preload(-1, -1, 1, 1, 5), l).get();
    assertThat(downloader.isDownloadInProgress()).isFalse();
    verify(manager, never()).initLayer(any());
  }

  @Test
  void startDownload_concurrentCall_throwsIllegalState() throws Exception {
    VectorPmtilesManager manager = mock(VectorPmtilesManager.class);
    Layer l = layer("https://example.com/tiles.pmtiles", 5);

    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

    PmtilesDownloader downloader =
        new PmtilesDownloader(xyzConfig(), manager) {
          @Override
          protected ProcessBuilder buildProcess(Preload preload, Layer layer, Path out) {
            try {
              latch.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return new ProcessBuilder("true");
          }
        };

    java.util.concurrent.CompletableFuture<Void> first =
        downloader.startDownload(preload(-1, -1, 1, 1, 5), l);

    assertThatThrownBy(() -> downloader.startDownload(preload(-1, -1, 1, 1, 5), l))
        .isInstanceOf(IllegalStateException.class);

    latch.countDown();
    first.get();
  }

  // ── requireValidBoundingBox ───────────────────────────────────────────────

  @Test
  void requireValidBoundingBox_null_throws() {
    assertThatThrownBy(() -> PmtilesDownloader.requireValidBoundingBox(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required");
  }

  @Test
  void requireValidBoundingBox_nonFiniteValues_throws() {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(Double.NaN);
    bbox.setSouth(-90);
    bbox.setEast(180);
    bbox.setNorth(90);
    assertThatThrownBy(() -> PmtilesDownloader.requireValidBoundingBox(bbox))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-finite");
  }

  @Test
  void requireValidBoundingBox_westGeqEast_throws() {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(10);
    bbox.setSouth(-10);
    bbox.setEast(10);
    bbox.setNorth(10);
    assertThatThrownBy(() -> PmtilesDownloader.requireValidBoundingBox(bbox))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  @Test
  void requireValidBoundingBox_southGeqNorth_throws() {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(-10);
    bbox.setSouth(5);
    bbox.setEast(10);
    bbox.setNorth(5);
    assertThatThrownBy(() -> PmtilesDownloader.requireValidBoundingBox(bbox))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  @Test
  void requireValidBoundingBox_outOfGlobalRange_throws() {
    BoundingBox bbox = new BoundingBox();
    bbox.setWest(-181);
    bbox.setSouth(-90);
    bbox.setEast(180);
    bbox.setNorth(90);
    assertThatThrownBy(() -> PmtilesDownloader.requireValidBoundingBox(bbox))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  // ── requireValidMaxZoom ───────────────────────────────────────────────────

  @Test
  void requireValidMaxZoom_negative_throws() {
    assertThatThrownBy(() -> PmtilesDownloader.requireValidMaxZoom(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  @Test
  void requireValidMaxZoom_over22_throws() {
    assertThatThrownBy(() -> PmtilesDownloader.requireValidMaxZoom(23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  // ── safePathArg ───────────────────────────────────────────────────────────

  @Test
  void safePathArg_unsafeChars_throws() {
    assertThatThrownBy(() -> PmtilesDownloader.safePathArg(Path.of("/tmp/evil file")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe");
  }

  @Test
  void safePathArg_safePath_returnsSame() {
    String safe = PmtilesDownloader.safePathArg(Path.of("/tmp/tiles/region.pmtiles"));
    assertThat(safe).isEqualTo("/tmp/tiles/region.pmtiles");
  }
}
