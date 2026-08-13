package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.PreloadProgress;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.PreloadStore;
import org.mockito.ArgumentCaptor;

class PreloadServiceTest {

  @SuppressWarnings("unchecked")
  private final LoadingCache<Tile, byte[]> tileCache = mock(LoadingCache.class);

  private LayerStore layerStore;
  private PreloadStore preloadStore;
  private PmtilesDownloader pmtilesDownloader;
  private PreloadService service;

  @BeforeEach
  void setUp() {
    layerStore = mock(LayerStore.class);
    preloadStore = mock(PreloadStore.class);
    pmtilesDownloader = mock(PmtilesDownloader.class);
    // Default: the download finishes immediately and successfully. Tests that care about the
    // vector half's timing or outcome override this with their own future.
    when(pmtilesDownloader.startDownload(any(), any(), anyBoolean()))
        .thenReturn(CompletableFuture.completedFuture(null));
    service =
        new PreloadService(
            layerStore,
            tileCache,
            preloadStore,
            pmtilesDownloader,
            new SimpleMeterRegistry(),
            new XyzConfiguration());
    service.registerMetrics();
  }

  private static BoundingBox bbox() {
    BoundingBox b = new BoundingBox();
    b.setNorth(1);
    b.setSouth(-1);
    b.setEast(1);
    b.setWest(-1);
    b.setMaxZoom(0);
    return b;
  }

  private static Layer xyzLayer(String id) {
    LayerProperties l = new LayerProperties();
    l.setId(id);
    l.setName(id);
    l.setSourceType(Layer.SourceType.XYZ);
    l.setUrlTemplate("http://example.com/{z}/{x}/{y}.png");
    return l.toLayer();
  }

  private static Layer vectorLayer(String id, String url) {
    LayerProperties l = new LayerProperties();
    l.setId(id);
    l.setName(id);
    l.setSourceType(Layer.SourceType.VECTOR_PMTILES);
    l.setUrlTemplate(url);
    return l.toLayer();
  }

  // ── vector layer validation ────────────────────────────────────────────────

  @Test
  void submit_vectorLayer_noUrlTemplate_throwsIllegalArgument() {
    Layer vec = vectorLayer("vec", null);
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    assertThatThrownBy(() -> service.submit("t", bbox(), 5, Set.of("vec"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("urlTemplate");
  }

  @Test
  void submit_vectorLayer_blankUrlTemplate_throwsIllegalArgument() {
    Layer vec = vectorLayer("vec", "  ");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    assertThatThrownBy(() -> service.submit("t", bbox(), 5, Set.of("vec"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("urlTemplate");
  }

  @Test
  void submit_vectorLayer_downloadAlreadyInProgress_throwsIllegalState() {
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    when(pmtilesDownloader.isDownloadInProgress()).thenReturn(true);
    assertThatThrownBy(() -> service.submit("t", bbox(), 5, Set.of("vec"), null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already in progress");
  }

  @Test
  void submit_vectorLayer_invalidBbox_throwsWithoutPersisting() {
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    BoundingBox bad = bbox();
    bad.setWest(10);
    bad.setEast(-10);
    assertThatThrownBy(() -> service.submit("t", bad, 5, Set.of("vec"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
    verifyNoInteractions(preloadStore);
  }

  @Test
  void submit_vectorOnly_skipsRasterPreloadPass() throws Exception {
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));

    Preload result = service.submit("t", bbox(), 5, Set.of("vec"), null, null);

    assertThat(result).isNotNull();
    // No raster layers → no xyz preload job is submitted, so the tile cache is never touched and
    // the PMTiles download is the only thing the job waits on.
    verifyNoInteractions(tileCache);
    awaitTerminal(result);
    assertThat(result.getStatus()).isEqualTo(Preload.Status.DONE);
  }

  // ── no-op paths ────────────────────────────────────────────────────────────

  @Test
  void submit_nullLayers_returnsNull() throws Exception {
    when(layerStore.getLayers()).thenReturn(Map.of());
    assertThat(service.submit("t", bbox(), 5, null, null, null)).isNull();
  }

  @Test
  void submit_emptyLayers_returnsNull() throws Exception {
    when(layerStore.getLayers()).thenReturn(Map.of());
    assertThat(service.submit("t", bbox(), 5, Set.of(), null, null)).isNull();
  }

  @Test
  void submit_allLayersUnknown_returnsNull() throws Exception {
    when(layerStore.getLayers()).thenReturn(Map.of());
    assertThat(service.submit("t", bbox(), 5, Set.of("ghost"), null, null)).isNull();
  }

  // ── happy paths ────────────────────────────────────────────────────────────

  @Test
  void submit_validLayer_returnsPreloadAndPersists() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));

    Preload result = service.submit("my-preload", bbox(), 0, Set.of("test"), null, null);
    assertThat(result).isNotNull();
    assertThat(result.getName()).isEqualTo("my-preload");
    verify(preloadStore).addPreload(any());
  }

  @Test
  void submit_validVectorLayer_returnsPreloadAndStartsDownload() throws Exception {
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    when(pmtilesDownloader.isDownloadInProgress()).thenReturn(false);

    Preload result = service.submit("t", bbox(), 5, Set.of("vec"), null, null);
    assertThat(result).isNotNull();
    // ownsTerminalStatus=false: the service marks the preload DONE/FAILED once every half reports.
    verify(pmtilesDownloader).startDownload(any(), eq(vec), eq(false));
  }

  @Test
  void submit_nullName_autogeneratesNameFromBbox() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));

    Preload result = service.submit(null, bbox(), 5, Set.of("test"), null, null);
    assertThat(result.getName()).contains("bbox");
  }

  @Test
  void submit_blankName_autogeneratesName() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));

    Preload result = service.submit("  ", bbox(), 5, Set.of("test"), null, null);
    assertThat(result.getName()).contains("bbox");
  }

  @Test
  void submit_nullAllowedUsers_defaultsToEmpty() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));

    Preload result = service.submit("t", bbox(), 0, Set.of("test"), null, null);
    assertThat(result.getAllowedUsers()).isEmpty();
  }

  @Test
  void submit_nullAllowedGroups_defaultsToEmpty() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));

    Preload result = service.submit("t", bbox(), 0, Set.of("test"), null, null);
    assertThat(result.getAllowedGroups()).isEmpty();
  }

  // ── status / progress ─────────────────────────────────────────────────────

  private static void awaitTerminal(Preload preload) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (preload.getStatus() == Preload.Status.DONE
          || preload.getStatus() == Preload.Status.FAILED) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Preload did not reach a terminal status: " + preload.getStatus());
  }

  @Test
  void rasterPreload_tracksProgressAndTimestampsThroughDone() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));
    when(tileCache.get(any())).thenReturn(new byte[] {1});

    // maxZoom 0 → exactly one tile, so the counters are deterministic.
    Preload result = service.submit("t", bbox(), 0, Set.of("test"), null, null);
    awaitTerminal(result);

    assertThat(result.getStatus()).isEqualTo(Preload.Status.DONE);
    assertThat(result.getTotalTiles()).isEqualTo(1);
    assertThat(result.getCompletedTiles()).isEqualTo(1);
    assertThat(result.getFailedTiles()).isZero();
    assertThat(result.getStartedAt()).isNotNull();
    assertThat(result.getFinishedAt()).isNotNull();

    PreloadProgress progress = service.progressFor(result);
    assertThat(progress.totalTiles()).isEqualTo(1);
    assertThat(progress.completedTiles()).isEqualTo(1);
    assertThat(progress.percentComplete()).isEqualTo(100);
  }

  @Test
  void rasterPreload_countsFailedTilesSeparately() throws Exception {
    Layer layer = xyzLayer("test");
    when(layerStore.getLayers()).thenReturn(Map.of("test", layer));
    when(tileCache.get(any())).thenThrow(new IllegalStateException("source down"));

    Preload result = service.submit("t", bbox(), 0, Set.of("test"), null, null);
    awaitTerminal(result);

    // A tile that fails is logged and skipped, so the job still completes.
    assertThat(result.getStatus()).isEqualTo(Preload.Status.DONE);
    assertThat(result.getFailedTiles()).isEqualTo(1);
    assertThat(result.getCompletedTiles()).isZero();
    assertThat(service.progressFor(result).percentComplete()).isEqualTo(100);
  }

  // ── mixed raster + vector jobs ────────────────────────────────────────────

  @Test
  void mixedPreload_vectorFinishesFirst_staysRunningUntilRasterDone() throws Exception {
    Layer raster = xyzLayer("test");
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("test", raster, "vec", vec));
    // Vector download is already complete when submit returns; the raster pass is still blocked.
    CountDownLatch releaseRaster = new CountDownLatch(1);
    when(tileCache.get(any()))
        .thenAnswer(
            invocation -> {
              releaseRaster.await();
              return new byte[] {1};
            });

    Preload result = service.submit("t", bbox(), 0, Set.of("test", "vec"), null, null);

    // The completed vector half must not carry the whole job to DONE on its own.
    assertThat(result.getStatus()).isNotEqualTo(Preload.Status.DONE);
    Thread.sleep(100);
    assertThat(result.getStatus()).isNotEqualTo(Preload.Status.DONE);

    releaseRaster.countDown();
    awaitTerminal(result);
    assertThat(result.getStatus()).isEqualTo(Preload.Status.DONE);
    assertThat(result.getCompletedTiles()).isEqualTo(1);
  }

  @Test
  void mixedPreload_rasterFinishesFirst_staysRunningUntilVectorDone() throws Exception {
    Layer raster = xyzLayer("test");
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("test", raster, "vec", vec));
    when(tileCache.get(any())).thenReturn(new byte[] {1});
    CompletableFuture<Void> download = new CompletableFuture<>();
    when(pmtilesDownloader.startDownload(any(), eq(vec), eq(false))).thenReturn(download);

    Preload result = service.submit("t", bbox(), 0, Set.of("test", "vec"), null, null);

    awaitTileCounts(result);
    assertThat(result.getStatus()).isNotEqualTo(Preload.Status.DONE);

    download.complete(null);
    awaitTerminal(result);
    assertThat(result.getStatus()).isEqualTo(Preload.Status.DONE);
  }

  @Test
  void mixedPreload_vectorFails_wholeJobFails() throws Exception {
    Layer raster = xyzLayer("test");
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("test", raster, "vec", vec));
    when(tileCache.get(any())).thenReturn(new byte[] {1});
    when(pmtilesDownloader.startDownload(any(), eq(vec), eq(false)))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("pmtiles extract failed")));

    Preload result = service.submit("t", bbox(), 0, Set.of("test", "vec"), null, null);
    awaitTerminal(result);

    assertThat(result.getStatus()).isEqualTo(Preload.Status.FAILED);
    assertThat(result.getErrorMessage()).contains("pmtiles extract failed");
    // The raster half still ran to completion and its counts were kept.
    assertThat(result.getCompletedTiles()).isEqualTo(1);
  }

  @Test
  void mixedPreload_vectorDownloadRefused_doesNotLeaveJobRunning() throws Exception {
    Layer raster = xyzLayer("test");
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("test", raster, "vec", vec));
    when(tileCache.get(any())).thenReturn(new byte[] {1});
    // Racy path: nothing was downloading at validation time, but something is by the time the
    // download is actually started.
    when(pmtilesDownloader.startDownload(any(), eq(vec), eq(false)))
        .thenThrow(new IllegalStateException("A PMTiles download is already in progress"));

    assertThatThrownBy(() -> service.submit("t", bbox(), 0, Set.of("test", "vec"), null, null))
        .isInstanceOf(IllegalStateException.class);

    Preload submitted = captureAddedPreload();
    awaitTerminal(submitted);
    assertThat(submitted.getStatus()).isEqualTo(Preload.Status.FAILED);
    assertThat(submitted.getErrorMessage()).contains("already in progress");
  }

  private Preload captureAddedPreload() throws Exception {
    ArgumentCaptor<Preload> captor = ArgumentCaptor.forClass(Preload.class);
    verify(preloadStore).addPreload(captor.capture());
    return captor.getValue();
  }

  /** Waits for the raster half to record its tile counts onto the preload. */
  private static void awaitTileCounts(Preload preload) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (preload.getCompletedTiles() + preload.getFailedTiles() >= preload.getTotalTiles()
          && preload.getTotalTiles() > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Raster half never finished");
  }

  @Test
  void progressFor_vectorOnlyPreload_isNull() throws Exception {
    Layer vec = vectorLayer("vec", "https://example.com/tiles.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));

    Preload result = service.submit("t", bbox(), 5, Set.of("vec"), null, null);
    assertThat(service.progressFor(result)).isNull();
  }

  @Test
  void progressFor_finishedPreload_usesPersistedCounts() {
    Preload preload = new Preload();
    preload.setId("restored");
    preload.setTotalTiles(4);
    preload.setCompletedTiles(2);
    preload.setFailedTiles(1);

    PreloadProgress progress = service.progressFor(preload);
    assertThat(progress.totalTiles()).isEqualTo(4);
    assertThat(progress.percentComplete()).isEqualTo(75);
  }

  @Test
  void failInterruptedPreloads_marksUnfinishedJobsFailed() throws Exception {
    Preload running = new Preload();
    running.setId("running");
    running.setStatus(Preload.Status.RUNNING);
    Preload done = new Preload();
    done.setId("done");
    done.setStatus(Preload.Status.DONE);
    when(preloadStore.listPreloads()).thenReturn(List.of(running, done));

    service.failInterruptedPreloads();

    assertThat(running.getStatus()).isEqualTo(Preload.Status.FAILED);
    assertThat(running.getErrorMessage()).contains("restart");
    assertThat(done.getStatus()).isEqualTo(Preload.Status.DONE);
    verify(preloadStore).update(running);
  }

  // ── preloadXyzTiles ───────────────────────────────────────────────────────

  @Test
  void preloadXyzTiles_unknownLayer_doesNothing() {
    when(layerStore.getLayers()).thenReturn(Map.of());
    service.preloadXyzTiles(Set.of("nonexistent"), bbox());
    verifyNoInteractions(tileCache);
  }

  @Test
  void preloadXyzTiles_vectorPmtilesLayer_skipped() {
    Layer vec = vectorLayer("vec", "/some/file.pmtiles");
    when(layerStore.getLayers()).thenReturn(Map.of("vec", vec));
    service.preloadXyzTiles(Set.of("vec"), bbox());
    verifyNoInteractions(tileCache);
  }
}
