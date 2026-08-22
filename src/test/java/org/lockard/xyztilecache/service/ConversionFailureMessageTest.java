package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.springframework.mock.web.MockMultipartFile;

/**
 * What an uploader is told when conversion fails. The CLI's own text names the server's staging
 * file and leads with Go logging noise, neither of which helps whoever picked the file.
 */
class ConversionFailureMessageTest {

  @TempDir Path tempDir;

  private PmtilesUploadService serviceWithConverter(MbtilesConverter converter) throws Exception {
    XyzConfiguration configuration = new XyzConfiguration();
    configuration.setBaseTileDirectory(tempDir.toString());
    TileInventoryStore inventory =
        new TileInventoryStore(configuration, new com.fasterxml.jackson.databind.ObjectMapper());
    inventory.init();
    return new PmtilesUploadService(configuration, converter, null, inventory);
  }

  /** A file carrying SQLite's header, so the upload is routed to conversion. */
  private static MockMultipartFile sqliteUpload(String name) {
    byte[] header = "SQLite format 3".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] file = new byte[512];
    System.arraycopy(header, 0, file, 0, header.length);
    return new MockMultipartFile("files", name, "application/octet-stream", file);
  }

  private String messageFor(String cliOutput) throws Exception {
    PmtilesUploadService service =
        serviceWithConverter(
            new MbtilesConverter("unused") {
              @Override
              public void convert(Path mbtiles, Path output) throws java.io.IOException {
                throw new java.io.IOException(cliOutput);
              }
            });
    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                service.install(
                    "layer", List.of(sqliteUpload("trails.mbtiles")), Optional.empty()));
    assertThat(thrown).isInstanceOf(PmtilesUploadService.UploadRejectedException.class);
    return thrown.getMessage();
  }

  @Test
  void aCorruptDatabase_isNamedAsSuch() throws Exception {
    String message =
        messageFor(
            "pmtiles convert exited 1. Output: 2026/08/23 main.go:223: Failed to convert"
                + " /tmp/tiles/.uploads/staging-950/raw-0, Failed to create SQL statement,"
                + " database disk image is malformed: database disk image is malformed");

    assertThat(message).contains("trails.mbtiles");
    assertThat(message).contains("incomplete or corrupted");
    // The uploader has no use for the server's staging path, and it should not be advertised.
    assertThat(message).doesNotContain("staging-");
    assertThat(message).doesNotContain("/tmp/tiles");
  }

  @Test
  void aDatabaseWithoutATilesTable_saysSo() throws Exception {
    String message = messageFor("pmtiles convert exited 1. Output: no such table: tiles");

    assertThat(message).contains("trails.mbtiles");
    assertThat(message).contains("no tiles table");
  }

  @Test
  void anUnrecognisedFailure_isPassedThroughWithoutInternalPaths() throws Exception {
    String message =
        messageFor(
            "pmtiles convert exited 2. Output: something odd at /tmp/tiles/.uploads/staging-7/raw-0");

    assertThat(message).contains("trails.mbtiles");
    assertThat(message).contains("something odd");
    assertThat(message).doesNotContain("staging-7");
  }
}
