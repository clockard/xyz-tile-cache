package org.lockard.xyztilecache.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for JSON-file backed stores that persist a list of {@code T} to a file under the
 * configured base tile directory. All mutations are serialized by an OS-level file lock; in-process
 * callers are also serialized by the same lock since {@link FileChannel#lock()} blocks across
 * threads. Subclasses implement the data shape and how loaded state is applied to memory.
 */
public abstract class JsonFileStore<T> {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  private final XyzConfiguration configuration;
  private final ObjectMapper objectMapper;

  private Path jsonPath;
  private FileChannel lockChannel;
  private volatile FileTime lastKnownMtime = FileTime.fromMillis(0);

  /**
   * In-JVM serialization for {@link FileChannel#lock()}. Without this, two threads in the same JVM
   * calling {@code lockChannel.lock()} concurrently trigger {@link
   * java.nio.channels.OverlappingFileLockException} — the file lock is per-process, so the JVM
   * refuses overlapping in-process holds rather than blocking.
   */
  private final ReentrantLock inProcessLock = new ReentrantLock();

  protected JsonFileStore(XyzConfiguration configuration, ObjectMapper objectMapper) {
    this.configuration = configuration;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void init() throws IOException {
    Path baseDir = Paths.get(configuration.getBaseTileDirectory());
    Files.createDirectories(baseDir);
    jsonPath = baseDir.resolve(fileName());
    lockChannel =
        FileChannel.open(
            baseDir.resolve(lockFileName()),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);

    inProcessLock.lock();
    try (FileLock ignored = lockChannel.lock()) {
      if (Files.exists(jsonPath)) {
        logger.info("Loading {} from {}.", fileName(), jsonPath);
        loadFromFile();
      } else {
        logger.info("No {} found — writing seed data to {}.", fileName(), jsonPath);
        seed();
        writeFile();
      }
    } finally {
      inProcessLock.unlock();
    }
  }

  @PreDestroy
  public void close() throws IOException {
    if (lockChannel != null && lockChannel.isOpen()) {
      lockChannel.close();
    }
  }

  /**
   * Reloads from disk if the file's mtime has changed since the last load. Captures a snapshot of
   * the previous in-memory state and passes it to {@link #onReloaded} so subclasses can diff and
   * publish events. Subclasses should call this from a {@code @Scheduled} method.
   */
  protected void syncIfChanged() {
    if (jsonPath == null) return;
    try {
      if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;
      inProcessLock.lock();
      try (FileLock ignored = lockChannel.lock()) {
        if (Files.getLastModifiedTime(jsonPath).equals(lastKnownMtime)) return;
        List<T> before = snapshot();
        loadFromFile();
        onReloaded(before);
      } finally {
        inProcessLock.unlock();
      }
    } catch (IOException e) {
      logger.error("Failed to sync {} from {}.", fileName(), jsonPath, e);
    }
  }

  /**
   * Acquires the file lock, reloads from disk to pick up any concurrent changes, runs {@code
   * mutator} against the in-memory state, then writes the file back out.
   */
  protected final void withLockedReloadAndWrite(IORunnable mutator) throws IOException {
    inProcessLock.lock();
    try (FileLock ignored = lockChannel.lock()) {
      loadFromFile();
      mutator.run();
      writeFile();
    } finally {
      inProcessLock.unlock();
    }
  }

  private void loadFromFile() throws IOException {
    List<T> loaded = objectMapper.readValue(jsonPath.toFile(), listTypeRef());
    applyLoaded(loaded);
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  private void writeFile() throws IOException {
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), snapshot());
    lastKnownMtime = Files.getLastModifiedTime(jsonPath);
  }

  // ── Subclass extension points ─────────────────────────────────────────────

  /** File name (under the base tile directory) used for the persisted JSON list. */
  protected abstract String fileName();

  /** File name (under the base tile directory) used for the cross-process file lock. */
  protected abstract String lockFileName();

  /** Jackson type token for {@code List<T>}. */
  protected abstract TypeReference<List<T>> listTypeRef();

  /** A point-in-time copy of the in-memory entries, suitable for writing to disk. */
  protected abstract List<T> snapshot();

  /** Replace the in-memory state with the just-loaded list. */
  protected abstract void applyLoaded(List<T> loaded);

  /** Populate initial in-memory state when no file exists yet. Default: leave empty. */
  protected void seed() {}

  /** Called under the file lock immediately after a reload. Default: do nothing. */
  protected void onReloaded(List<T> previousSnapshot) {}

  @FunctionalInterface
  protected interface IORunnable {
    void run() throws IOException;
  }
}
