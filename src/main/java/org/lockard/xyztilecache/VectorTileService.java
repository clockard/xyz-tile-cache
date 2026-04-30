package org.lockard.xyztilecache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VectorTileService {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTileService.class);

  private final VectorConfiguration config;

  private volatile PmtilesReader bundledReader;
  private final CopyOnWriteArrayList<PmtilesReader> downloadedReaders =
      new CopyOnWriteArrayList<>();

  public VectorTileService(VectorConfiguration config) {
    this.config = config;
  }

  @PostConstruct
  void init() {
    if (!config.isEnabled()) {
      return;
    }

    Path bundled = Path.of(config.getBundledPath());
    if (Files.exists(bundled)) {
      try {
        bundledReader = new PmtilesReader(bundled);
        LOGGER.info("Loaded bundled PMTiles: {}", bundled);
      } catch (IOException e) {
        LOGGER.warn("Failed to open bundled PMTiles at {}: {}", bundled, e.getMessage());
      }
    } else {
      LOGGER.warn("Bundled PMTiles not found at {}; vector fallback unavailable.", bundled);
    }

    String downloadDir = config.getDownloadDirectory();
    if (downloadDir == null || downloadDir.isBlank()) {
      return;
    }
    Path dir = Path.of(downloadDir);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      LOGGER.warn("Could not create vector download directory {}: {}", dir, e.getMessage());
    }
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        files
            .filter(p -> p.toString().endsWith(".pmtiles"))
            .sorted()
            .forEach(
                p -> {
                  try {
                    registerDownload(p);
                  } catch (IOException e) {
                    LOGGER.warn("Could not open downloaded PMTiles {}: {}", p, e.getMessage());
                  }
                });
      } catch (IOException e) {
        LOGGER.warn("Could not scan vector download directory {}: {}", dir, e.getMessage());
      }
    }
  }

  public Optional<TileResult> getTile(int z, int x, int y) throws IOException {
    for (int i = downloadedReaders.size() - 1; i >= 0; i--) {
      Optional<TileResult> result = downloadedReaders.get(i).getTile(z, x, y);
      if (result.isPresent()) {
        return result;
      }
    }
    if (bundledReader != null) {
      return bundledReader.getTile(z, x, y);
    }
    return Optional.empty();
  }

  public void registerDownload(Path path) throws IOException {
    PmtilesReader reader = new PmtilesReader(path);
    downloadedReaders.add(reader);
    LOGGER.info("Registered downloaded PMTiles: {}", path);
  }

  @PreDestroy
  void destroy() {
    closeReaderSilently(bundledReader);
    downloadedReaders.forEach(this::closeReaderSilently);
  }

  private void closeReaderSilently(PmtilesReader reader) {
    if (reader == null) return;
    try {
      reader.close();
    } catch (IOException e) {
      LOGGER.debug("Error closing PmtilesReader", e);
    }
  }
}
