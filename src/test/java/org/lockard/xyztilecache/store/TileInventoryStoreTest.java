package org.lockard.xyztilecache.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;

class TileInventoryStoreTest {

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private TileInventoryStore inventory;

  @BeforeEach
  void setUp() throws Exception {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    inventory = newStore();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (inventory != null) {
      inventory.close();
    }
  }

  private TileInventoryStore newStore() throws Exception {
    TileInventoryStore store = new TileInventoryStore(configuration, new ObjectMapper());
    store.init();
    return store;
  }

  private Path inventoryFile() {
    return tempDir.resolve("tile-inventory.json");
  }

  // ── Counting ──────────────────────────────────────────────────────────────

  @Test
  void unknownLayer_readsAsZero() {
    assertThat(inventory.tiles("nope")).isZero();
    assertThat(inventory.bytes("nope")).isZero();
  }

  @Test
  void recordWrite_accumulatesCountAndSize() {
    inventory.recordWrite("osm", 1, 100L);
    inventory.recordWrite("osm", 1, 200L);

    assertThat(inventory.tiles("osm")).isEqualTo(2);
    assertThat(inventory.bytes("osm")).isEqualTo(300L);
  }

  @Test
  void recordWrite_overwriteAddsBytesWithoutAddingTile() {
    inventory.recordWrite("osm", 1, 100L);
    inventory.recordWrite("osm", 0, 50L);

    assertThat(inventory.tiles("osm")).isEqualTo(1);
    assertThat(inventory.bytes("osm")).isEqualTo(150L);
  }

  @Test
  void recordWrite_acceptsNegativeByteDeltaForShrinkingTile() {
    inventory.recordWrite("osm", 1, 100L);
    inventory.recordWrite("osm", 0, -60L);

    assertThat(inventory.bytes("osm")).isEqualTo(40L);
  }

  @Test
  void layersAreTrackedIndependently() {
    inventory.recordWrite("osm", 1, 100L);
    inventory.recordWrite("topo", 2, 500L);

    assertThat(inventory.tiles("osm")).isEqualTo(1);
    assertThat(inventory.tiles("topo")).isEqualTo(2);
    assertThat(inventory.bytes("topo")).isEqualTo(500L);
  }

  @Test
  void recordAbsolute_replacesRatherThanAccumulates() {
    inventory.recordWrite("dem", 5, 500L);
    inventory.recordAbsolute("dem", 12, 2_048L);

    assertThat(inventory.tiles("dem")).isEqualTo(12);
    assertThat(inventory.bytes("dem")).isEqualTo(2_048L);
  }

  @Test
  void recordReset_zeroesLayerButKeepsTracking() {
    inventory.recordWrite("osm", 3, 300L);
    inventory.recordReset("osm");
    assertThat(inventory.tiles("osm")).isZero();

    inventory.recordWrite("osm", 1, 10L);
    assertThat(inventory.tiles("osm")).isEqualTo(1);
    assertThat(inventory.bytes("osm")).isEqualTo(10L);
  }

  @Test
  void recordRemoved_dropsLayer() {
    inventory.recordWrite("gone", 3, 300L);
    inventory.recordRemoved("gone");

    assertThat(inventory.tiles("gone")).isZero();
    assertThat(inventory.bytes("gone")).isZero();
  }

  // ── Persistence ───────────────────────────────────────────────────────────

  @Test
  void flush_writesTotalsThatSurviveARestart() throws Exception {
    inventory.recordWrite("osm", 4, 4_000L);
    inventory.flush();
    inventory.close();

    inventory = newStore();

    assertThat(inventory.tiles("osm")).isEqualTo(4);
    assertThat(inventory.bytes("osm")).isEqualTo(4_000L);
  }

  @Test
  void close_flushesPendingChanges() throws Exception {
    inventory.recordWrite("osm", 2, 200L);
    inventory.close();

    inventory = newStore();

    assertThat(inventory.tiles("osm")).isEqualTo(2);
    assertThat(inventory.bytes("osm")).isEqualTo(200L);
  }

  @Test
  void flush_isSkippedWhenNothingChanged() throws Exception {
    inventory.recordWrite("osm", 1, 10L);
    inventory.flush();
    long firstWrite = Files.getLastModifiedTime(inventoryFile()).toMillis();

    Thread.sleep(20);
    inventory.flush();

    assertThat(Files.getLastModifiedTime(inventoryFile()).toMillis()).isEqualTo(firstWrite);
  }

  @Test
  void writeBurstCrossingThreshold_flushesWithoutWaitingForTheTimer() throws Exception {
    configuration.setInventoryFlushTiles(5);

    for (int i = 0; i < 5; i++) {
      inventory.recordWrite("burst", 1, 10L);
    }

    // No explicit flush and no timer tick: crossing the threshold persisted the batch on its own,
    // so the file already holds it.
    assertThat(Files.readString(inventoryFile())).contains("burst");
    inventory.close();
    inventory = newStore();
    assertThat(inventory.tiles("burst")).isEqualTo(5);
    assertThat(inventory.bytes("burst")).isEqualTo(50L);
  }

  @Test
  void writeBurstBelowThreshold_doesNotWriteTheFile() throws Exception {
    configuration.setInventoryFlushTiles(1_000);

    for (int i = 0; i < 20; i++) {
      inventory.recordWrite("quiet", 1, 10L);
    }

    assertThat(Files.readString(inventoryFile())).doesNotContain("quiet");
  }

  @Test
  void flush_appliesDeltaOnTopOfAnotherWritersTotals() throws Exception {
    // Another instance sharing the cache directory persisted its own count first.
    Files.writeString(
        inventoryFile(),
        """
        [ { "layerId" : "shared", "tiles" : 10, "bytes" : 1000, "updatedAt" : "2026-01-01T00:00:00Z" } ]
        """);

    inventory.recordWrite("shared", 3, 300L);
    inventory.flush();

    // Merged, not clobbered: both writers' contributions are present.
    assertThat(inventory.tiles("shared")).isEqualTo(13);
    assertThat(inventory.bytes("shared")).isEqualTo(1_300L);
    assertThat(Files.readString(inventoryFile())).contains("\"tiles\" : 13");
  }

  @Test
  void flush_prunesRemovedLayersFromTheFile() throws Exception {
    inventory.recordWrite("doomed", 2, 200L);
    inventory.flush();
    assertThat(Files.readString(inventoryFile())).contains("doomed");

    inventory.recordRemoved("doomed");
    inventory.flush();

    assertThat(Files.readString(inventoryFile())).doesNotContain("doomed");
    inventory.close();
    inventory = newStore();
    assertThat(inventory.tiles("doomed")).isZero();
  }

  @Test
  void flush_persistsAbsoluteTotalsRatherThanAddingThem() throws Exception {
    inventory.recordWrite("dem", 5, 500L);
    inventory.flush();

    inventory.recordAbsolute("dem", 2, 20L);
    inventory.flush();
    inventory.close();

    inventory = newStore();
    assertThat(inventory.tiles("dem")).isEqualTo(2);
    assertThat(inventory.bytes("dem")).isEqualTo(20L);
  }

  @Test
  void writesLandingDuringAFlushAreKeptForTheNextBatch() throws Exception {
    inventory.recordWrite("osm", 1, 100L);
    inventory.flush();

    inventory.recordWrite("osm", 1, 100L);
    assertThat(inventory.tiles("osm")).isEqualTo(2);

    inventory.flush();
    inventory.close();
    inventory = newStore();
    assertThat(inventory.tiles("osm")).isEqualTo(2);
    assertThat(inventory.bytes("osm")).isEqualTo(200L);
  }
}
