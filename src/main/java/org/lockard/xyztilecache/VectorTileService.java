package org.lockard.xyztilecache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
  private final XyzConfiguration xyzConfig;
  private final VectorTileRemoteCache remoteCache;

  private final CopyOnWriteArrayList<PmtilesReader> downloadedReaders =
      new CopyOnWriteArrayList<>();
  private RemotePmtilesReader remotePmtilesReader;

  public VectorTileService(
      VectorConfiguration config, XyzConfiguration xyzConfig, VectorTileRemoteCache remoteCache) {
    this.config = config;
    this.xyzConfig = xyzConfig;
    this.remoteCache = remoteCache;
  }

  @PostConstruct
  void init() {
    if (!config.isEnabled()) {
      return;
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

    if (!xyzConfig.isOffline()) {
      String sourceUrl = PmtilesDownloader.resolveSourceUrl(config.getSourceUrl());
      if (sourceUrl != null && !sourceUrl.isBlank()) {
        HttpClient httpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.of(xyzConfig.getTileTimeoutSeconds(), ChronoUnit.SECONDS))
                .build();
        remotePmtilesReader =
            new RemotePmtilesReader(sourceUrl, httpClient, xyzConfig.getTileTimeoutSeconds());
        LOGGER.info("Remote PMTiles reader configured for: {}", sourceUrl);
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
    if (remotePmtilesReader != null) {
      Optional<TileResult> cached = remoteCache.get(z, x, y);
      if (cached.isPresent()) {
        return cached;
      }
      Optional<TileResult> result = remotePmtilesReader.getTile(z, x, y);
      if (result.isPresent()) {
        remoteCache.store(z, x, y, result.get());
        return result;
      }
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
