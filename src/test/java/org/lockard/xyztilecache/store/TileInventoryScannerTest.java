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

class TileInventoryScannerTest {

  @TempDir Path tempDir;

  private XyzConfiguration configuration;
  private TileInventoryStore inventory;
  private TileInventoryScanner scanner;

  @BeforeEach
  void setUp() throws Exception {
    configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    openStore();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (inventory != null) {
      inventory.close();
    }
  }

  private void openStore() throws Exception {
    inventory = new TileInventoryStore(configuration, new ObjectMapper());
    inventory.init();
    scanner = new TileInventoryScanner(configuration, inventory);
  }

  private void writeTile(String layerId, String z, String x, String y, int size) throws Exception {
    Path tile = tempDir.resolve(Path.of(layerId, z, x, y + ".png"));
    Files.createDirectories(tile.getParent());
    Files.write(tile, new byte[size]);
  }

  private void scanAndWait(String reason) {
    assertThat(scanner.requestScan(reason)).isTrue();
    awaitScanFinished();
  }

  private void awaitScanFinished() {
    long deadline = System.currentTimeMillis() + 10_000;
    while (scanner.isScanning() && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(scanner.isScanning()).as("scan finished within 10s").isFalse();
  }

  // ── Scanning ──────────────────────────────────────────────────────────────

  @Test
  void scan_countsTilesAndBytesPerLayerDirectory() throws Exception {
    writeTile("osm", "3", "1", "2", 10);
    writeTile("osm", "3", "1", "3", 20);
    writeTile("topo", "1", "0", "0", 5);

    scanAndWait("test");

    assertThat(inventory.tiles("osm")).isEqualTo(2);
    assertThat(inventory.bytes("osm")).isEqualTo(30);
    assertThat(inventory.tiles("topo")).isEqualTo(1);
    assertThat(inventory.bytes("topo")).isEqualTo(5);
  }

  @Test
  void scan_countsPmtilesArchiveBytesButNotAsATile() throws Exception {
    writeTile("vec", "2", "1", "1", 8);
    Path archive = tempDir.resolve(Path.of("vec", "basemap.pmtiles"));
    Files.write(archive, new byte[512]);

    scanAndWait("test");

    assertThat(inventory.tiles("vec")).isEqualTo(1);
    assertThat(inventory.bytes("vec")).isEqualTo(520);
  }

  @Test
  void scan_correctsTotalsThatDriftedFromDisk() throws Exception {
    writeTile("osm", "3", "1", "2", 10);
    inventory.recordWrite("osm", 99, 99_000L);

    scanAndWait("test");

    assertThat(inventory.tiles("osm")).isEqualTo(1);
    assertThat(inventory.bytes("osm")).isEqualTo(10);
  }

  @Test
  void scan_picksUpLayerDirectoriesWithNoConfiguredLayer() throws Exception {
    // A directory landed by import belongs to a layer the store was never told about.
    writeTile("imported", "0", "0", "0", 42);

    scanAndWait("test");

    assertThat(inventory.tiles("imported")).isEqualTo(1);
    assertThat(inventory.bytes("imported")).isEqualTo(42);
  }

  @Test
  void scan_persistsWhatItFound() throws Exception {
    writeTile("osm", "3", "1", "2", 10);

    scanAndWait("test");
    inventory.close();
    openStore();

    assertThat(inventory.tiles("osm")).isEqualTo(1);
    assertThat(inventory.bytes("osm")).isEqualTo(10);
  }

  @Test
  void scan_keepsWritesThatLandedWhileItRan() throws Exception {
    writeTile("osm", "3", "1", "2", 10);
    TileInventoryStore.ScanHandle handle = inventory.beginScan("osm");

    // A tile written after the walk started but that the walk did not see.
    inventory.recordWrite("osm", 1, 7L);
    inventory.completeScan(handle, 1, 10L);

    assertThat(inventory.tiles("osm")).isEqualTo(2);
    assertThat(inventory.bytes("osm")).isEqualTo(17);
  }

  @Test
  void requestScan_whileAlreadyScanning_isIgnored() throws Exception {
    for (int i = 0; i < 200; i++) {
      writeTile("big", "5", String.valueOf(i), "0", 64);
    }
    assertThat(scanner.requestScan("first")).isTrue();
    // Either the first scan is still running (request refused) or it already finished (accepted);
    // both are fine, but two scans must never run at once.
    scanner.requestScan("second");
    awaitScanFinished();

    assertThat(inventory.tiles("big")).isEqualTo(200);
  }

  // ── When a scan is needed ─────────────────────────────────────────────────

  @Test
  void firstStartWithNoInventoryFile_needsAScan() {
    assertThat(inventory.needsBootstrapScan()).isTrue();
  }

  @Test
  void restartAfterCleanShutdown_doesNotNeedAScan() throws Exception {
    inventory.recordWrite("osm", 1, 10L);
    inventory.close();

    openStore();

    assertThat(inventory.needsBootstrapScan()).isFalse();
    assertThat(inventory.tiles("osm")).isEqualTo(1);
  }

  @Test
  void restartAfterAnUncleanShutdown_needsAScan() throws Exception {
    inventory.recordWrite("osm", 1, 10L);
    inventory.flush();
    // Simulate a kill: the file is there, but nothing marked the shutdown clean.
    inventory.close();
    Files.delete(tempDir.resolve("tile-inventory.clean"));

    openStore();

    assertThat(inventory.needsBootstrapScan()).isTrue();
  }

  @Test
  void corruptInventoryFile_startsAnywayAndNeedsAScan() throws Exception {
    inventory.recordWrite("osm", 1, 10L);
    inventory.close();
    Files.writeString(tempDir.resolve("tile-inventory.json"), "{ not json at all");

    openStore();

    assertThat(inventory.needsBootstrapScan()).isTrue();
    assertThat(inventory.tiles("osm")).isZero();
  }

  @Test
  void readyEvent_skipsTheScanWhenTheInventoryIsTrusted() throws Exception {
    writeTile("osm", "3", "1", "2", 10);
    inventory.recordAbsolute("osm", 5, 500L);
    inventory.close();
    openStore();

    scanner.scanIfInventoryIsUntrusted();

    // Trusted inventory: no scan started, so the (wrong) persisted totals stand until asked.
    assertThat(scanner.isScanning()).isFalse();
    assertThat(inventory.tiles("osm")).isEqualTo(5);
  }

  @Test
  void readyEvent_scansWhenTheInventoryIsUntrusted() {
    assertThat(inventory.needsBootstrapScan()).isTrue();

    scanner.scanIfInventoryIsUntrusted();
    awaitScanFinished();

    assertThat(Files.exists(tempDir.resolve("tile-inventory.json"))).isTrue();
  }
}
