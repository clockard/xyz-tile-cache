package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.BoundingBox;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.store.LayerStore;
import org.lockard.xyztilecache.store.PreloadStore;

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
    service =
        new PreloadService(
            layerStore, tileCache, preloadStore, pmtilesDownloader, new SimpleMeterRegistry());
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
    verify(pmtilesDownloader).startDownload(any(), eq(vec));
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
