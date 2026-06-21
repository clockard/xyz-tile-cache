package org.lockard.xyztilecache.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;

class LayerStoreTest {

  @TempDir Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private XyzConfiguration configuration;
  private LayerStore layerStore;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    configuration.setLayers(List.of(layerProperties("base")));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (layerStore != null) {
      layerStore.close();
    }
  }

  private Layer layer(String name) {
    LayerProperties l = new LayerProperties();
    l.setName(name);
    l.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
    return l.toLayer();
  }

  private LayerProperties layerProperties(String name) {
    LayerProperties l = new LayerProperties();
    l.setName(name);
    l.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
    return l;
  }

  private LayerStore storeAndInit() throws Exception {
    // Use a no-op event publisher — unit tests don't need Spring's event bus
    layerStore = new LayerStore(configuration, objectMapper, event -> {});
    layerStore.init();
    return layerStore;
  }

  @Test
  void firstRun_createsLayersJsonFile() throws Exception {
    storeAndInit();
    assertThat(tempDir.resolve("layers.json")).exists();
  }

  @Test
  void firstRun_createsLockFile() throws Exception {
    storeAndInit();
    assertThat(tempDir.resolve("layers.lock")).exists();
  }

  @Test
  void subsequentRun_loadsLayersFromJsonInsteadOfConfig() throws Exception {
    storeAndInit(); // writes "base" to JSON

    layerStore.close(); // release lock so store2 can open it
    layerStore = null;

    XyzConfiguration config2 = new XyzConfiguration();
    config2.setBaseTileDirectory(tempDir.toString());
    LayerStore store2 = new LayerStore(config2, objectMapper, event -> {});
    store2.init();
    layerStore = store2; // ensure tearDown closes it

    assertThat(store2.getLayers()).containsKey("base");
  }

  @Test
  void addLayer_appearsInStoreAndJson() throws Exception {
    storeAndInit();
    layerStore.addLayer(layer("new-layer"));

    assertThat(layerStore.getLayers()).containsKey("new-layer");
    assertThat(tempDir.resolve("layers.json")).content().contains("new-layer");
  }

  @Test
  void addLayer_throwsForDuplicateName() throws Exception {
    storeAndInit();
    assertThatThrownBy(() -> layerStore.addLayer(layer("base")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void addLayer_throwsForBlankName() throws Exception {
    storeAndInit();
    LayerProperties bad = new LayerProperties();
    bad.setName("");
    bad.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
    assertThatThrownBy(() -> layerStore.addLayer(bad.toLayer()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateLayer_replacesInStoreAndJson() throws Exception {
    storeAndInit();
    LayerProperties updated = layerProperties("base");
    updated.setMaxZoom(10);
    layerStore.updateLayer("base", updated.toLayer());

    assertThat(layerStore.getLayers().get("base").getMaxZoom()).isEqualTo(10);
    assertThat(tempDir.resolve("layers.json")).content().contains("base");
  }

  @Test
  void updateLayer_throwsForUnknownName() throws Exception {
    storeAndInit();
    assertThatThrownBy(() -> layerStore.updateLayer("missing", layer("missing")))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void removeLayer_removesFromStore() throws Exception {
    storeAndInit();
    layerStore.removeLayer("base");
    assertThat(layerStore.getLayers()).doesNotContainKey("base");
  }

  @Test
  void removeLayer_throwsForUnknownName() throws Exception {
    storeAndInit();
    assertThatThrownBy(() -> layerStore.removeLayer("missing"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void syncLayers_noopWhenFileUnchanged() throws Exception {
    storeAndInit();
    int sizeBefore = layerStore.getLayers().size();
    // File has not changed — syncLayers should return immediately with no side effects
    layerStore.syncLayers();
    assertThat(layerStore.getLayers()).hasSize(sizeBefore);
  }

  @Test
  void syncLayers_noEventPublishedWhenLayerIsUnchanged() throws Exception {
    var eventCount = new java.util.concurrent.atomic.AtomicInteger();
    layerStore = new LayerStore(configuration, objectMapper, e -> eventCount.incrementAndGet());
    layerStore.init();

    // Write the same layer list back — no meaningful change
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(tempDir.resolve("layers.json").toFile(), List.of(layer("base")));
    Files.setLastModifiedTime(
        tempDir.resolve("layers.json"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

    layerStore.syncLayers();
    assertThat(eventCount.get()).isZero();
  }

  @Test
  void syncLayers_picksUpExternallyWrittenChange() throws Exception {
    storeAndInit();
    assertThat(layerStore.getLayers()).doesNotContainKey("new-layer");

    // Simulate another instance writing a new layer to the file
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(
            tempDir.resolve("layers.json").toFile(), List.of(layer("base"), layer("new-layer")));
    // Force a future mtime so the change is detected regardless of filesystem resolution
    Files.setLastModifiedTime(
        tempDir.resolve("layers.json"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

    layerStore.syncLayers();
    assertThat(layerStore.getLayers()).containsKey("new-layer");
  }

  @Test
  void addLayer_throwsForBlankUrlTemplate() throws Exception {
    storeAndInit();
    LayerProperties bad = new LayerProperties();
    bad.setName("valid-name");
    bad.setUrlTemplate("");
    assertThatThrownBy(() -> layerStore.addLayer(bad.toLayer()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addLayer_throwsForNullUrlTemplate() throws Exception {
    storeAndInit();
    LayerProperties bad = new LayerProperties();
    bad.setName("valid-name");
    assertThatThrownBy(() -> layerStore.addLayer(bad.toLayer()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void close_isIdempotent() throws Exception {
    storeAndInit();
    layerStore.close(); // first close — channel open
    layerStore.close(); // second close — channel already closed, must not throw
    layerStore = null; // prevent tearDown from closing a third time
  }

  @Test
  void addLayer_acceptsLocalSourceWithoutUrlTemplate() throws Exception {
    storeAndInit();
    LayerProperties local = new LayerProperties();
    local.setName("local-orthophoto");
    local.setSourceType(Layer.SourceType.LOCAL);
    layerStore.addLayer(local.toLayer());
    assertThat(layerStore.getLayers()).containsKey("local-orthophoto");
    assertThat(layerStore.getLayers().get("local-orthophoto").sourceType())
        .isEqualTo(Layer.SourceType.LOCAL);
  }

  @Test
  void writtenJsonDoesNotContainRuntimeStats() throws Exception {
    storeAndInit();
    String json = Files.readString(tempDir.resolve("layers.json"));
    assertThat(json).doesNotContain("cachedTiles").doesNotContain("tilesServed");
  }
}
