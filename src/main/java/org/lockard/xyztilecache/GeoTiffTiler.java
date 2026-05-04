package org.lockard.xyztilecache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Runs {@code gdal2tiles.py} to convert a GeoTIFF into an XYZ tile pyramid. */
@Component
public class GeoTiffTiler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GeoTiffTiler.class);

  private final String tileCommand;
  private final String infoCommand;
  private final String translateCommand;
  private final XyzConfiguration configuration;

  @Autowired
  public GeoTiffTiler(XyzConfiguration configuration) {
    this(configuration, "gdal2tiles.py", "gdalinfo", "gdal_translate");
  }

  GeoTiffTiler(
      XyzConfiguration configuration,
      String tileCommand,
      String infoCommand,
      String translateCommand) {
    this.configuration = configuration;
    this.tileCommand = tileCommand;
    this.infoCommand = infoCommand;
    this.translateCommand = translateCommand;
  }

  /** Tiling result: highest zoom level produced and total bytes written under outputDir. */
  public record Result(int maxZoom, long totalBytes, long tileCount) {}

  /**
   * Tiles the GeoTIFF at {@code input} into {@code outputDir} as XYZ tiles. The output directory
   * must not already contain z-level subdirs; gdal2tiles is invoked with {@code --xyz} so the
   * y-axis matches XYZ convention. If any band is not 8-bit, the input is first auto-scaled to a
   * Byte VRT via {@code gdal_translate -of VRT -ot Byte -scale} since gdal2tiles only accepts 8-bit
   * input.
   */
  public Result tile(Path input, Path outputDir) throws IOException, InterruptedException {
    Path safeInput = input.toAbsolutePath().normalize();
    Path safeOutput = validateTrustedOutputPath(outputDir);
    Files.createDirectories(safeOutput);
    Path tileInput = safeInput;
    Path vrt = null;
    try {
      if (!isAllByte(safeInput)) {
        LOGGER.info("Input {} is not 8-bit; auto-scaling to Byte VRT for gdal2tiles.", safeInput);
        vrt = Files.createTempFile("upload-", ".vrt");
        translateToByteVrt(safeInput, vrt);
        tileInput = vrt.toAbsolutePath().normalize();
      }
      runGdal2Tiles(tileInput, safeOutput);
    } finally {
      if (vrt != null) {
        try {
          Files.deleteIfExists(vrt);
        } catch (IOException e) {
          LOGGER.warn("Failed to delete temp VRT {}.", vrt, e);
        }
      }
    }
    return summarize(safeOutput);
  }

  private boolean isAllByte(Path input) throws IOException, InterruptedException {
    List<String> cmd = List.of(infoCommand, input.toAbsolutePath().normalize().toString());
    LOGGER.debug("Running {}.", String.join(" ", cmd));
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    Process process = pb.start();
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException(
          "gdalinfo exited %d. Output: %s".formatted(exit, String.join("\n", lines)));
    }
    boolean sawBand = false;
    for (String line : lines) {
      if (line.startsWith("Band ")) {
        sawBand = true;
        if (!line.contains("Type=Byte")) {
          return false;
        }
      }
    }
    return sawBand;
  }

  private void translateToByteVrt(Path input, Path vrt) throws IOException, InterruptedException {
    List<String> cmd =
        List.of(
            translateCommand,
            "-of",
            "VRT",
            "-ot",
            "Byte",
            "-scale",
            input.toAbsolutePath().normalize().toString(),
            vrt.toAbsolutePath().normalize().toString());
    LOGGER.info("Running {}.", String.join(" ", cmd));
    runOrThrow(cmd, "gdal_translate");
  }

  private void runGdal2Tiles(Path input, Path outputDir) throws IOException, InterruptedException {
    Path safeOutput = validateTrustedOutputPath(outputDir);
    List<String> cmd =
        List.of(
            tileCommand,
            "--xyz",
            "-w",
            "none",
            "--zoom=0-",
            "--processes=" + Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            input.toAbsolutePath().normalize().toString(),
            safeOutput.toString());
    LOGGER.info("Running {}.", String.join(" ", cmd));
    runOrThrow(cmd, "gdal2tiles.py");
  }

  private Path validateTrustedOutputPath(Path outputDir) {
    Path safeOutput = outputDir.toAbsolutePath().normalize();
    Path baseDir = Path.of(configuration.getBaseTileDirectory()).toAbsolutePath().normalize();
    if (!safeOutput.startsWith(baseDir)) {
      throw new IllegalArgumentException(
          "Output path is outside configured base directory: " + safeOutput);
    }
    return safeOutput;
  }

  private static void runOrThrow(List<String> cmd, String label)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    Process process = pb.start();
    List<String> tail = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        LOGGER.debug("{}: {}", label, line);
        tail.add(line);
        if (tail.size() > 50) {
          tail.removeFirst();
        }
      }
    }
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException(
          "%s exited %d. Output: %s".formatted(label, exit, String.join("\n", tail)));
    }
  }

  static Result summarize(Path outputDir) throws IOException {
    OptionalInt maxZoom;
    try (var entries = Files.list(outputDir)) {
      maxZoom =
          entries
              .filter(Files::isDirectory)
              .map(p -> p.getFileName().toString())
              .filter(name -> name.chars().allMatch(Character::isDigit))
              .mapToInt(Integer::parseInt)
              .max();
    }
    if (maxZoom.isEmpty()) {
      throw new IOException("gdal2tiles produced no zoom-level directories under " + outputDir);
    }

    long totalBytes = 0;
    long tileCount = 0;
    try (var paths = Files.walk(outputDir)) {
      var files = paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
      for (Path f : files) {
        totalBytes += Files.size(f);
        if (f.getFileName().toString().endsWith(".png")) {
          tileCount++;
        }
      }
    }
    return new Result(maxZoom.getAsInt(), totalBytes, tileCount);
  }
}
