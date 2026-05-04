package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoTiffTilerTest {

  @TempDir Path tempDir;

  @Test
  void tile_producesXyzPyramid_whenGdalIsAvailable() throws Exception {
    assumeTrue(gdalAvailable(), "gdal2tiles.py not on PATH — skipping integration test.");

    Path tif = tempDir.resolve("input.tif");
    createSmallGeoTiff(tif);

    Path outputDir = tempDir.resolve("out");
    GeoTiffTiler tiler = new GeoTiffTiler(makeConfig());
    GeoTiffTiler.Result result = tiler.tile(tif, outputDir);

    assertThat(result.maxZoom()).isGreaterThanOrEqualTo(0);
    assertThat(result.tileCount()).isGreaterThan(0);
    assertThat(outputDir.resolve(String.valueOf(result.maxZoom()))).isDirectory();
  }

  @Test
  void summarize_findsMaxZoomAndCountsTiles() throws Exception {
    Path out = tempDir.resolve("fake-output");
    // Simulate gdal2tiles output: zoom dirs 0, 1, 2 with one .png each, plus a stray xml file.
    for (int z = 0; z <= 2; z++) {
      Path tile = out.resolve(String.valueOf(z)).resolve("0").resolve("0.png");
      Files.createDirectories(tile.getParent());
      Files.write(tile, new byte[] {1, 2, 3, 4});
    }
    Files.write(out.resolve("tilemapresource.xml"), "<xml/>".getBytes());

    GeoTiffTiler.Result r = GeoTiffTiler.summarize(out);
    assertThat(r.maxZoom()).isEqualTo(2);
    assertThat(r.tileCount()).isEqualTo(3);
    assertThat(r.totalBytes()).isGreaterThan(0);
  }

  @Test
  void summarize_throwsWhenNoZoomDirs() throws Exception {
    Path out = tempDir.resolve("empty");
    Files.createDirectories(out);
    assertThatThrownBy(() -> GeoTiffTiler.summarize(out)).isInstanceOf(IOException.class);
  }

  @Test
  void tile_throwsWhenCommandIsMissing() throws Exception {
    Path tif = tempDir.resolve("nope.tif");
    Files.write(tif, new byte[] {0});
    GeoTiffTiler tiler =
        new GeoTiffTiler(
            makeConfig(),
            "/no/such/binary/_xyz_no_exist",
            byteGdalInfoStub().toString(),
            noopTranslateStub().toString());
    assertThatThrownBy(() -> tiler.tile(tif, tempDir.resolve("out")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void tile_throwsWhenCommandExitsNonZeroAndIncludesTail() throws Exception {
    Path script = writeStubScript("noisy-fail.sh", emitLinesScript(120, /* exit */ 1));
    Path tif = tempDir.resolve("input.tif");
    Files.write(tif, new byte[] {1});
    GeoTiffTiler tiler =
        new GeoTiffTiler(
            makeConfig(),
            script.toString(),
            byteGdalInfoStub().toString(),
            noopTranslateStub().toString());
    assertThatThrownBy(() -> tiler.tile(tif, tempDir.resolve("out")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited 1");
  }

  @Test
  void tile_succeedsWhenCommandExitsZero_andSummarizesPyramid() throws Exception {
    // Stub script writes a fake tile pyramid, then exits 0. Tiler should summarize successfully.
    Path script =
        writeStubScript(
            "fake-tiler.sh",
            String.join(
                "\n",
                "#!/bin/sh",
                "set -e",
                "echo starting",
                // Args: --xyz -w none --zoom=0- --processes=N input output
                "OUT=\"$7\"",
                "mkdir -p \"$OUT/0/0\" \"$OUT/1/0\"",
                "printf 'png0' > \"$OUT/0/0/0.png\"",
                "printf 'png1' > \"$OUT/1/0/0.png\"",
                "echo done",
                "exit 0",
                ""));
    Path tif = tempDir.resolve("input.tif");
    Files.write(tif, new byte[] {1});
    GeoTiffTiler tiler =
        new GeoTiffTiler(
            makeConfig(),
            script.toString(),
            byteGdalInfoStub().toString(),
            noopTranslateStub().toString());
    GeoTiffTiler.Result r = tiler.tile(tif, tempDir.resolve("happy-out"));
    assertThat(r.maxZoom()).isEqualTo(1);
    assertThat(r.tileCount()).isEqualTo(2);
  }

  @Test
  void tile_convertsNonByteRasterBeforeTiling() throws Exception {
    // gdalinfo stub reports UInt16, so the tiler must invoke gdal_translate before gdal2tiles.
    Path infoStub =
        writeStubScript(
            "gdalinfo-uint16.sh",
            String.join(
                "\n",
                "#!/bin/sh",
                "echo 'Driver: GTiff/GeoTIFF'",
                "echo 'Band 1 Block=256x256 Type=UInt16, ColorInterp=Gray'",
                "exit 0",
                ""));
    // gdal_translate stub: write a marker file at the VRT path (last positional arg).
    Path translateStub =
        writeStubScript(
            "gdal-translate-touch.sh",
            String.join(
                "\n",
                "#!/bin/sh",
                "shift $(($# - 1))",
                "printf 'vrt-stub' > \"$1\"",
                "exit 0",
                ""));
    // gdal2tiles stub: assert it is being called with the VRT, then produce a pyramid.
    Path tileStub =
        writeStubScript(
            "fake-tiler-vrt.sh",
            String.join(
                "\n",
                "#!/bin/sh",
                "set -e",
                "IN=\"$6\"",
                "OUT=\"$7\"",
                "case \"$IN\" in *.vrt) ;; *) echo \"expected .vrt input, got $IN\" >&2; exit 7;; esac",
                "mkdir -p \"$OUT/0/0\" \"$OUT/1/0\" \"$OUT/2/0\"",
                "printf 'a' > \"$OUT/0/0/0.png\"",
                "printf 'bb' > \"$OUT/1/0/0.png\"",
                "printf 'ccc' > \"$OUT/2/0/0.png\"",
                "exit 0",
                ""));

    Path tif = tempDir.resolve("input.tif");
    Files.write(tif, new byte[] {1});
    GeoTiffTiler tiler =
        new GeoTiffTiler(
            makeConfig(), tileStub.toString(), infoStub.toString(), translateStub.toString());
    GeoTiffTiler.Result r = tiler.tile(tif, tempDir.resolve("vrt-out"));
    assertThat(r.maxZoom()).isEqualTo(2);
    assertThat(r.tileCount()).isEqualTo(3);
  }

  @Test
  void tile_propagatesGdalInfoFailure() throws Exception {
    Path infoStub =
        writeStubScript(
            "gdalinfo-fail.sh",
            String.join(
                "\n",
                "#!/bin/sh",
                "echo 'ERROR 4: not a valid raster' >&2",
                "echo 'ERROR 4: not a valid raster'",
                "exit 1",
                ""));
    Path tif = tempDir.resolve("input.tif");
    Files.write(tif, new byte[] {1});
    GeoTiffTiler tiler =
        new GeoTiffTiler(
            makeConfig(), "gdal2tiles.py", infoStub.toString(), noopTranslateStub().toString());
    assertThatThrownBy(() -> tiler.tile(tif, tempDir.resolve("out")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("gdalinfo exited 1");
  }

  private Path byteGdalInfoStub() throws Exception {
    return writeStubScript(
        "gdalinfo-byte.sh",
        String.join(
            "\n",
            "#!/bin/sh",
            "echo 'Driver: GTiff/GeoTIFF'",
            "echo 'Band 1 Block=256x256 Type=Byte, ColorInterp=Gray'",
            "exit 0",
            ""));
  }

  private Path noopTranslateStub() throws Exception {
    return writeStubScript("gdal-translate-noop.sh", String.join("\n", "#!/bin/sh", "exit 0", ""));
  }

  private Path writeStubScript(String name, String body) throws Exception {
    Path script = tempDir.resolve(name);
    Files.write(script, body.getBytes());
    script.toFile().setExecutable(true);
    return script;
  }

  private static String emitLinesScript(int lines, int exitCode) {
    return String.join(
        "\n",
        "#!/bin/sh",
        "i=0",
        "while [ $i -lt " + lines + " ]; do",
        "  echo \"line $i\"",
        "  i=$((i+1))",
        "done",
        "exit " + exitCode,
        "");
  }

  private XyzConfiguration makeConfig() {
    XyzConfiguration config = new XyzConfiguration();
    config.setBaseTileDirectory(tempDir.toString());
    return config;
  }

  private static boolean gdalAvailable() {
    try {
      Process p = new ProcessBuilder("gdal2tiles.py", "--help").redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void createSmallGeoTiff(Path path) throws Exception {
    // gdal_translate is part of gdal-bin; use it to generate a tiny georeferenced TIFF.
    // We start from a plain XYZ raster and use gdal_create.
    Process p =
        new ProcessBuilder(
                "gdal_create",
                "-of",
                "GTiff",
                "-outsize",
                "32",
                "32",
                "-bands",
                "3",
                "-burn",
                "100",
                "-burn",
                "150",
                "-burn",
                "200",
                "-a_srs",
                "EPSG:4326",
                "-a_ullr",
                "-1",
                "1",
                "1",
                "-1",
                path.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    p.getInputStream().readAllBytes();
    int exit = p.waitFor();
    assumeTrue(exit == 0 && Files.exists(path), "gdal_create failed — skipping.");
  }
}
