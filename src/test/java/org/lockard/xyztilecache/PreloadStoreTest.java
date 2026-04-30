package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloadStoreTest {

  @TempDir Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private XyzConfiguration configuration;
  private PreloadStore store;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (store != null) {
      store.close();
    }
  }

  private PreloadStore newStore() throws Exception {
    store = new PreloadStore(configuration, objectMapper);
    store.init();
    return store;
  }

  private Preload preload(String name, boolean includesVector, List<String> layers) {
    BoundingBox bbox = new BoundingBox();
    bbox.setNorth(1);
    bbox.setSouth(-1);
    bbox.setEast(1);
    bbox.setWest(-1);
    Preload p = new Preload();
    p.setId(UUID.randomUUID().toString());
    p.setName(name);
    p.setBoundingBox(bbox);
    p.setMaxZoom(10);
    p.setLayers(layers);
    p.setIncludesVector(includesVector);
    if (includesVector) p.setPmtilesFilename(name + ".pmtiles");
    p.setCreatedAt(Instant.now());
    return p;
  }

  @Test
  void firstRun_createsPreloadsJsonFile() throws Exception {
    newStore();
    assertThat(tempDir.resolve("preloads.json")).exists();
  }

  @Test
  void firstRun_createsLockFile() throws Exception {
    newStore();
    assertThat(tempDir.resolve("preloads.lock")).exists();
  }

  @Test
  void addPreload_appearsInList() throws Exception {
    newStore();
    Preload p = preload("region", true, List.of("base"));
    store.addPreload(p);
    assertThat(store.listPreloads()).hasSize(1);
    assertThat(store.listPreloads().get(0).getName()).isEqualTo("region");
  }

  @Test
  void addPreload_persistsAcrossReload() throws Exception {
    newStore();
    Preload p = preload("region", true, List.of("base"));
    store.addPreload(p);
    store.close();

    PreloadStore store2 = new PreloadStore(configuration, objectMapper);
    store2.init();
    store = store2;

    assertThat(store2.listPreloads()).hasSize(1);
    assertThat(store2.listPreloads().get(0).getName()).isEqualTo("region");
  }

  @Test
  void addPreload_duplicateId_throws() throws Exception {
    newStore();
    Preload p = preload("region", false, List.of("base"));
    store.addPreload(p);
    Preload p2 = preload("other", false, List.of("base"));
    p2.setId(p.getId());
    assertThatThrownBy(() -> store.addPreload(p2)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addPreload_blankId_throws() throws Exception {
    newStore();
    Preload p = preload("region", false, List.of("base"));
    p.setId("");
    assertThatThrownBy(() -> store.addPreload(p)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void removePreload_removesFromList() throws Exception {
    newStore();
    Preload p = preload("region", false, List.of());
    store.addPreload(p);
    store.removePreload(p.getId());
    assertThat(store.listPreloads()).isEmpty();
  }

  @Test
  void removePreload_unknownId_throws() throws Exception {
    newStore();
    assertThatThrownBy(() -> store.removePreload("missing"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void findById_returnsMatching() throws Exception {
    newStore();
    Preload p = preload("region", true, List.of("base"));
    store.addPreload(p);
    assertThat(store.findById(p.getId())).isPresent();
    assertThat(store.findById("missing")).isEmpty();
  }

  @Test
  void close_isIdempotent() throws Exception {
    newStore();
    store.close();
    store.close();
    store = null;
  }
}
