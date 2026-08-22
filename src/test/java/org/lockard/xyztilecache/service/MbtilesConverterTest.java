package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MbtilesConverterTest {

  @TempDir Path tempDir;

  @Test
  void missingCommand_isReportedAsUnavailableRatherThanAFailedRun() throws Exception {
    Path input = Files.createFile(tempDir.resolve("in.mbtiles"));
    MbtilesConverter converter = new MbtilesConverter("__no_such_command_xyz_123__");

    // The distinction matters: an image without the CLI should tell the user to upload PMTiles
    // instead, not report the file as broken.
    assertThatThrownBy(() -> converter.convert(input, tempDir.resolve("out.pmtiles")))
        .isInstanceOf(MbtilesConverter.ConverterUnavailableException.class);
  }

  @Test
  void nonZeroExit_isReportedAsAFailedConversion() throws Exception {
    Path input = Files.createFile(tempDir.resolve("in.mbtiles"));
    MbtilesConverter converter = new MbtilesConverter("false");

    assertThatThrownBy(() -> converter.convert(input, tempDir.resolve("out.pmtiles")))
        .isInstanceOf(IOException.class)
        .isNotInstanceOf(MbtilesConverter.ConverterUnavailableException.class)
        .hasMessageContaining("pmtiles convert exited");
  }

  @Test
  void zeroExit_completesWithoutError() throws Exception {
    Path input = Files.createFile(tempDir.resolve("in.mbtiles"));
    MbtilesConverter converter = new MbtilesConverter("true");

    converter.convert(input, tempDir.resolve("out.pmtiles"));

    // "true" produces no output file; validating the result is the caller's job.
    assertThat(tempDir.resolve("out.pmtiles")).doesNotExist();
  }
}
