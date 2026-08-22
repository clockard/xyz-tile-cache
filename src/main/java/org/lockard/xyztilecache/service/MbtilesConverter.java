package org.lockard.xyztilecache.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Converts an MBTiles database to a PMTiles archive with the bundled {@code pmtiles} CLI.
 *
 * <p>Conversion is one-way — the CLI has no PMTiles-to-MBTiles direction — and it carries the tile
 * type across, so a raster MBTiles becomes a raster PMTiles rather than being mislabelled as
 * vector. Doing it this way means the cache never needs to read SQLite itself.
 */
@Component
public class MbtilesConverter {

  private static final Logger LOGGER = LoggerFactory.getLogger(MbtilesConverter.class);
  private static final int LOG_TAIL_LINES = 50;

  private final String pmtilesCommand;

  @Autowired
  public MbtilesConverter() {
    this("pmtiles");
  }

  public MbtilesConverter(String pmtilesCommand) {
    this.pmtilesCommand = pmtilesCommand;
  }

  /** Raised when the {@code pmtiles} binary is not on the PATH, as opposed to a failed run. */
  public static class ConverterUnavailableException extends IOException {
    public ConverterUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Converts {@code mbtiles} into a PMTiles archive at {@code output}.
   *
   * @throws ConverterUnavailableException if the CLI is not installed
   * @throws IOException if the conversion itself fails
   */
  public void convert(Path mbtiles, Path output) throws IOException, InterruptedException {
    List<String> cmd =
        List.of(
            pmtilesCommand,
            "convert",
            mbtiles.toAbsolutePath().toString(),
            output.toAbsolutePath().toString());
    LOGGER.info("Converting MBTiles to PMTiles: {} -> {}", mbtiles.getFileName(), output);

    Process process;
    try {
      process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new ConverterUnavailableException(
          "The 'pmtiles' command is not available; MBTiles upload requires it.", e);
    }

    List<String> tail = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        LOGGER.debug("pmtiles convert: {}", line);
        tail.add(line);
        if (tail.size() > LOG_TAIL_LINES) {
          tail.removeFirst();
        }
      }
    }

    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException(
          "pmtiles convert exited %d. Output: %s".formatted(exit, String.join("\n", tail)));
    }
  }
}
