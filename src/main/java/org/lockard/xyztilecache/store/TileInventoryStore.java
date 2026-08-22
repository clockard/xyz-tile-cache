package org.lockard.xyztilecache.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.TileInventoryEntry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tracks what each layer holds on disk — cached tile count and total bytes — and persists it to
 * {@code tile-inventory.json} so the totals survive a restart without re-walking the cache.
 *
 * <p>Every path that adds to or removes from the tile directory reports here. Callers report a
 * <em>delta</em>, not a fact about the file system: an overwrite is a byte delta with a zero count
 * delta, not a new tile.
 *
 * <h2>How persistence works</h2>
 *
 * <p>Each layer holds a persisted baseline plus an unflushed pending value, and what callers read
 * is the sum. Flushing applies the pending <em>delta</em> to whatever the file currently says
 * rather than overwriting it with an absolute total, so two instances sharing a cache directory
 * both contribute instead of clobbering each other, and each adopts the merged total on its next
 * flush. Because the exposed value is derived rather than assigned, a write landing mid-flush is
 * never lost — it simply belongs to the next batch.
 *
 * <p>Flushes are batched: on a timer when something changed, once a write burst crosses {@code
 * xyz.inventoryFlushTiles}, and on shutdown. Nothing is written per tile.
 *
 * <p>Counts are advisory statistics — they drive the {@code xyz_layer_cached_*} gauges and never
 * gate serving decisions — so a transient inaccuracy is not a correctness problem.
 */
@Component
public class TileInventoryStore extends JsonFileStore<TileInventoryEntry> {

  private static final String INVENTORY_FILE = "tile-inventory.json";
  private static final String LOCK_FILE = "tile-inventory.lock";

  /**
   * Written on graceful shutdown and deleted on startup. Its absence at startup means the previous
   * run ended without flushing, so the persisted totals may be behind what is on disk.
   */
  private static final String CLEAN_MARKER_FILE = "tile-inventory.clean";

  private final XyzConfiguration configuration;
  private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<>();
  private final AtomicBoolean dirty = new AtomicBoolean();

  /** Tiles reported since the last flush, used to bound how much a write burst can outrun it. */
  private final AtomicLong unflushedTiles = new AtomicLong();

  private volatile boolean neverInitialised;
  private volatile boolean cleanShutdown;

  public TileInventoryStore(XyzConfiguration configuration, ObjectMapper objectMapper) {
    super(configuration, objectMapper);
    this.configuration = configuration;
  }

  // ── Reporting ─────────────────────────────────────────────────────────────

  /** Records a single tile write: {@code tileDelta} is 1 for a new file, 0 for an overwrite. */
  public void recordWrite(String layerId, long tileDelta, long byteDelta) {
    Counters c = counters.computeIfAbsent(layerId, id -> new Counters());
    c.pendingTiles.addAndGet(tileDelta);
    c.pendingBytes.addAndGet(byteDelta);
    c.removed = false;
    dirty.set(true);
    if (unflushedTiles.addAndGet(Math.abs(tileDelta)) >= configuration.getInventoryFlushTiles()) {
      // A preload writes tiles far faster than the flush timer fires; cap how much a crash can
      // cost without paying for a write per tile.
      unflushedTiles.set(0);
      flushQuietly();
    }
  }

  /** Records a batch of writes as one update — used by imports, which write many tiles at once. */
  public void recordBulk(String layerId, long tiles, long bytes) {
    recordWrite(layerId, tiles, bytes);
  }

  /**
   * Replaces a layer's totals outright. For producers that materialise a whole tile set in one step
   * (a tiled GeoTIFF) and know the resulting counts directly.
   */
  public synchronized void recordAbsolute(String layerId, long tiles, long bytes) {
    Counters c = counters.computeIfAbsent(layerId, id -> new Counters());
    c.pendingTiles.set(tiles);
    c.pendingBytes.set(bytes);
    c.absolute = true;
    c.removed = false;
    dirty.set(true);
  }

  /** Zeroes a layer whose cached tiles have been discarded but which still exists. */
  public void recordReset(String layerId) {
    recordAbsolute(layerId, 0, 0);
  }

  /** Drops a layer that no longer exists; its entry is pruned from the file on the next flush. */
  public synchronized void recordRemoved(String layerId) {
    Counters c = counters.computeIfAbsent(layerId, id -> new Counters());
    c.removed = true;
    dirty.set(true);
  }

  public long tiles(String layerId) {
    Counters c = counters.get(layerId);
    if (c == null || c.removed) {
      return 0L;
    }
    return c.absolute ? c.pendingTiles.get() : c.baseTiles.get() + c.pendingTiles.get();
  }

  public long bytes(String layerId) {
    Counters c = counters.get(layerId);
    if (c == null || c.removed) {
      return 0L;
    }
    return c.absolute ? c.pendingBytes.get() : c.baseBytes.get() + c.pendingBytes.get();
  }

  /**
   * Size of an existing file, or {@code -1} when there is none — the input to a write delta, since
   * a caller has to know whether it is about to create a file or overwrite one already counted. An
   * unreadable file reports {@code -1} too: treating it as new is the same assumption the write
   * itself makes.
   */
  public static long sizeOrMissing(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return -1;
    }
  }

  // ── Persistence ───────────────────────────────────────────────────────────

  @Scheduled(fixedDelayString = "${xyz.inventoryFlushSeconds:30}000")
  public void flushOnSchedule() {
    flushQuietly();
  }

  /**
   * Loads the persisted totals and reads the clean-shutdown marker. Overrides the parent's
   * {@code @PostConstruct} so a damaged inventory cannot stop the application: the file is dropped
   * and rebuilt by a scan instead.
   */
  @Override
  public void init() throws IOException {
    try {
      super.init();
    } catch (IOException e) {
      logger.warn(
          "Could not read {}; it will be rebuilt by scanning the tile directory. ({})",
          INVENTORY_FILE,
          e.toString());
      super.close();
      Files.deleteIfExists(baseDir().resolve(INVENTORY_FILE));
      super.init();
    }
    Path marker = baseDir().resolve(CLEAN_MARKER_FILE);
    cleanShutdown = Files.exists(marker);
    // While this process runs the marker stays absent, so a crash is self-evident at next startup.
    Files.deleteIfExists(marker);
  }

  /**
   * Flushed before the lock channel closes, then the clean-shutdown marker is written. This
   * overrides the parent's {@code @PreDestroy} rather than adding a second one because the relative
   * order of two {@code @PreDestroy} methods is undefined, and closing the channel first would lose
   * the final flush.
   */
  @Override
  public void close() throws IOException {
    flushQuietly();
    super.close();
    try {
      Files.writeString(baseDir().resolve(CLEAN_MARKER_FILE), Instant.now().toString());
    } catch (IOException e) {
      // Only costs an unnecessary scan next startup.
      logger.warn("Could not write the clean-shutdown marker.", e);
    }
  }

  /**
   * True when the persisted totals cannot be trusted and the tile directory must be walked: either
   * no inventory file existed at startup, or the previous run did not shut down cleanly.
   */
  public boolean needsBootstrapScan() {
    return neverInitialised || !cleanShutdown;
  }

  @Override
  protected void seed() {
    neverInitialised = true;
  }

  private Path baseDir() {
    return Path.of(configuration.getBaseTileDirectory());
  }

  // ── Rescanning ────────────────────────────────────────────────────────────

  /**
   * Opens a scan of {@code layerId}, capturing the totals as they stand. Pass the handle back to
   * {@link #completeScan} so writes that land while the directory is being walked are preserved.
   */
  public ScanHandle beginScan(String layerId) {
    return new ScanHandle(layerId, tiles(layerId), bytes(layerId));
  }

  /**
   * Replaces a layer's totals with what the walk found, plus whatever was reported while it ran.
   *
   * <p>A tile written during the walk that the walker also saw is counted twice, so the result can
   * overshoot by at most the number of writes during the scan. A scan is a rare repair operation
   * and is exact on a quiet cache, so this is preferred over blocking writes for its duration.
   */
  public synchronized void completeScan(ScanHandle handle, long walkedTiles, long walkedBytes) {
    long tilesDuringScan = tiles(handle.layerId()) - handle.tilesAtStart();
    long bytesDuringScan = bytes(handle.layerId()) - handle.bytesAtStart();
    recordAbsolute(handle.layerId(), walkedTiles + tilesDuringScan, walkedBytes + bytesDuringScan);
  }

  /** Totals for one layer at the moment a scan began. */
  public record ScanHandle(String layerId, long tilesAtStart, long bytesAtStart) {}

  private void flushQuietly() {
    try {
      flush();
    } catch (IOException e) {
      logger.error("Failed to persist {}.", INVENTORY_FILE, e);
    }
  }

  /**
   * Drains the pending changes and applies them to the file under its lock. Draining first means a
   * concurrent write is accounted to the next batch rather than being written twice or lost.
   */
  public void flush() throws IOException {
    if (!dirty.getAndSet(false)) {
      return;
    }
    Map<String, Pending> drained = new LinkedHashMap<>();
    synchronized (this) {
      counters.forEach(
          (id, c) -> {
            Pending p =
                new Pending(
                    c.pendingTiles.getAndSet(0),
                    c.pendingBytes.getAndSet(0),
                    c.absolute,
                    c.removed);
            c.absolute = false;
            if (p.hasWork()) {
              drained.put(id, p);
            }
          });
    }
    if (drained.isEmpty()) {
      return;
    }
    unflushedTiles.set(0);
    try {
      withLockedReloadAndWrite(() -> drained.forEach(this::applyPending));
    } catch (IOException e) {
      restore(drained);
      throw e;
    }
  }

  /** Applies one layer's drained changes on top of the freshly reloaded file state. */
  private void applyPending(String layerId, Pending p) {
    if (p.removed()) {
      counters.remove(layerId);
      return;
    }
    Counters c = counters.computeIfAbsent(layerId, id -> new Counters());
    if (p.absolute()) {
      c.baseTiles.set(p.tiles());
      c.baseBytes.set(p.bytes());
    } else {
      c.baseTiles.addAndGet(p.tiles());
      c.baseBytes.addAndGet(p.bytes());
    }
  }

  /**
   * Puts drained changes back after a failed write so they are retried by the next flush. A write
   * that landed during the failed flush wins over a restored absolute value; the next reconcile
   * settles that rare case.
   */
  private synchronized void restore(Map<String, Pending> drained) {
    drained.forEach(
        (id, p) -> {
          Counters c = counters.computeIfAbsent(id, k -> new Counters());
          if (p.removed()) {
            c.removed = true;
          } else if (p.absolute() && c.pendingTiles.get() == 0 && c.pendingBytes.get() == 0) {
            c.pendingTiles.set(p.tiles());
            c.pendingBytes.set(p.bytes());
            c.absolute = true;
          } else if (!p.absolute()) {
            c.pendingTiles.addAndGet(p.tiles());
            c.pendingBytes.addAndGet(p.bytes());
          }
        });
    dirty.set(true);
  }

  // ── JsonFileStore hooks ───────────────────────────────────────────────────

  @Override
  protected String fileName() {
    return INVENTORY_FILE;
  }

  @Override
  protected String lockFileName() {
    return LOCK_FILE;
  }

  @Override
  protected TypeReference<List<TileInventoryEntry>> listTypeRef() {
    return new TypeReference<>() {};
  }

  @Override
  protected List<TileInventoryEntry> snapshot() {
    String now = Instant.now().toString();
    List<TileInventoryEntry> entries = new ArrayList<>(counters.size());
    counters.forEach(
        (id, c) -> {
          if (!c.removed) {
            entries.add(new TileInventoryEntry(id, c.baseTiles.get(), c.baseBytes.get(), now));
          }
        });
    return entries;
  }

  /**
   * Adopts the file as the persisted baseline. A layer absent from the file has a baseline of zero
   * — the file is authoritative for what has been persisted, and anything this instance has not
   * flushed yet is still held in its pending values.
   */
  @Override
  protected void applyLoaded(List<TileInventoryEntry> loaded) {
    counters.forEach(
        (id, c) -> {
          c.baseTiles.set(0);
          c.baseBytes.set(0);
        });
    for (TileInventoryEntry entry : loaded) {
      Counters c = counters.computeIfAbsent(entry.layerId(), id -> new Counters());
      c.baseTiles.set(entry.tiles());
      c.baseBytes.set(entry.bytes());
    }
  }

  private record Pending(long tiles, long bytes, boolean absolute, boolean removed) {
    boolean hasWork() {
      return removed || absolute || tiles != 0 || bytes != 0;
    }
  }

  private static final class Counters {
    private final AtomicLong baseTiles = new AtomicLong();
    private final AtomicLong baseBytes = new AtomicLong();
    private final AtomicLong pendingTiles = new AtomicLong();
    private final AtomicLong pendingBytes = new AtomicLong();

    /** Pending values are a target to set, not a delta to add. */
    private volatile boolean absolute;

    /** Layer is gone; prune it from the file on the next flush. */
    private volatile boolean removed;
  }
}
