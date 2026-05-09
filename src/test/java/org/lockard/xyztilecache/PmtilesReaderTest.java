package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PmtilesReaderTest {

  private static Path fixturePath() {
    URL url = PmtilesReaderTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
    assertThat(url).isNotNull();
    return Paths.get(url.getPath());
  }

  // ── Header parsing ────────────────────────────────────────────────────────

  @Test
  void parseHeader_validMagicAndVersion() throws IOException {
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      PmtilesHeader h = reader.getHeader();
      assertThat(h.minZoom()).isEqualTo(0);
      assertThat(h.maxZoom()).isEqualTo(1);
      assertThat(h.tileType()).isEqualTo(2); // PNG
      assertThat(h.internalCompression()).isEqualTo(PmtilesHeader.COMPRESSION_NONE);
      assertThat(h.tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_NONE);
    }
  }

  @Test
  void parseHeader_tooShort_throwsIllegalArgument() {
    assertThatThrownBy(() -> PmtilesHeader.parse(new byte[50]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("127");
  }

  @Test
  void parseHeader_invalidMagic_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'X';
    assertThatThrownBy(() -> PmtilesHeader.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void parseHeader_secondMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'X'; // should be 'M'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void parseHeader_thirdMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'X'; // should be 'T'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseHeader_fourthMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'T';
    bad[3] = 'X'; // should be 'i'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseHeader_fifthMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'T';
    bad[3] = 'i';
    bad[4] = 'X'; // should be 'l'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseHeader_sixthMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'T';
    bad[3] = 'i';
    bad[4] = 'l';
    bad[5] = 'X'; // should be 'e'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseHeader_seventhMagicByteWrong_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'T';
    bad[3] = 'i';
    bad[4] = 'l';
    bad[5] = 'e';
    bad[6] = 'X'; // should be 's'
    assertThatThrownBy(() -> PmtilesHeader.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseHeader_wrongVersion_throwsIllegalArgument() {
    byte[] bad = new byte[127];
    bad[0] = 'P';
    bad[1] = 'M';
    bad[2] = 'T';
    bad[3] = 'i';
    bad[4] = 'l';
    bad[5] = 'e';
    bad[6] = 's';
    bad[7] = 2; // version 2, not 3
    assertThatThrownBy(() -> PmtilesHeader.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  // ── Tile lookup ───────────────────────────────────────────────────────────

  @Test
  void getTile_z0Origin_returnsTileData() throws IOException {
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      Optional<TileResult> result = reader.getTile(0, 0, 0);
      assertThat(result).isPresent();
      assertThat(result.get().data()).isNotEmpty();
    }
  }

  @Test
  void getTile_z1KnownTile_returnsTileData() throws IOException {
    // z=1,x=1,y=1 → tileId=3, which is in the fixture
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      Optional<TileResult> result = reader.getTile(1, 1, 1);
      assertThat(result).isPresent();
      assertThat(result.get().data()).isNotEmpty();
    }
  }

  @Test
  void getTile_missingTile_returnsEmpty() throws IOException {
    // z=1,x=0,y=0 → tileId=1, not in fixture
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      Optional<TileResult> result = reader.getTile(1, 0, 0);
      assertThat(result).isEmpty();
    }
  }

  @Test
  void getTile_highZoomNotInFile_returnsEmpty() throws IOException {
    // z=20 is way beyond max_zoom=1 in the fixture; tile won't be found
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      Optional<TileResult> result = reader.getTile(20, 0, 0);
      assertThat(result).isEmpty();
    }
  }

  @Test
  void getTile_compressionMatchesHeader() throws IOException {
    try (PmtilesReader reader = new PmtilesReader(fixturePath())) {
      TileResult tile = reader.getTile(0, 0, 0).orElseThrow();
      assertThat(tile.tileCompression()).isEqualTo(reader.getHeader().tileCompression());
    }
  }

  @Test
  void close_idempotent() throws IOException {
    PmtilesReader reader = new PmtilesReader(fixturePath());
    reader.close();
    reader.close(); // should not throw
  }

  // ── Hilbert curve tile ID ─────────────────────────────────────────────────

  @Test
  void tileId_z0Origin_isZero() {
    assertThat(PmtilesReader.tileId(0, 0, 0)).isEqualTo(0);
  }

  @Test
  void tileId_z1KnownValues() {
    // z=1 face offset = (4^1-1)/3 = 1
    // Hilbert order at z=1: (0,0)→0, (0,1)→1, (1,1)→2, (1,0)→3
    assertThat(PmtilesReader.tileId(1, 0, 0)).isEqualTo(1);
    assertThat(PmtilesReader.tileId(1, 0, 1)).isEqualTo(2);
    assertThat(PmtilesReader.tileId(1, 1, 1)).isEqualTo(3);
    assertThat(PmtilesReader.tileId(1, 1, 0)).isEqualTo(4);
  }

  @Test
  void tileId_z2FaceOffset() {
    // z=2 face offset = (4^2-1)/3 = 5
    assertThat(PmtilesReader.tileId(2, 0, 0)).isEqualTo(5);
  }

  // ── Varint decoding ───────────────────────────────────────────────────────

  @Test
  void readVarint_singleByte() {
    ByteBuffer buf = ByteBuffer.wrap(new byte[] {0x05});
    assertThat(PmtilesReader.readVarint(buf)).isEqualTo(5);
  }

  @Test
  void readVarint_multiByte_128() {
    // 128 = 0x80 0x01
    ByteBuffer buf = ByteBuffer.wrap(new byte[] {(byte) 0x80, 0x01});
    assertThat(PmtilesReader.readVarint(buf)).isEqualTo(128);
  }

  @Test
  void readVarint_multiByte_300() {
    // 300 = 0xAC 0x02
    ByteBuffer buf = ByteBuffer.wrap(new byte[] {(byte) 0xAC, 0x02});
    assertThat(PmtilesReader.readVarint(buf)).isEqualTo(300);
  }

  // ── Directory decoding ────────────────────────────────────────────────────

  @Test
  void decodeDirectory_singleEntry_correctFields() {
    // Encode: n=1, tileId=0, runLength=1, length=66, offset stored as 66+1=67
    byte[] raw =
        buildDirectory(
            new long[] {0}, // tileId deltas
            new int[] {1}, // runLengths
            new int[] {66}, // lengths
            new long[] {1} // stored offsets (actual offset = stored-1 = 0)
            );
    java.util.List<PmtilesEntry> entries = PmtilesReader.decodeDirectory(raw);
    assertThat(entries).hasSize(1);
    PmtilesEntry e = entries.get(0);
    assertThat(e.tileId()).isEqualTo(0);
    assertThat(e.runLength()).isEqualTo(1);
    assertThat(e.length()).isEqualTo(66);
    assertThat(e.offset()).isEqualTo(0); // stored=1, actual=0
  }

  @Test
  void decodeDirectory_clusteredDeltaOffset() {
    // Two entries; second uses clustered delta (stored=0 means actual=prev+prevLen)
    byte[] raw =
        buildDirectory(
            new long[] {0, 5}, // tile deltas: first=0, second adds 5 (id=5)
            new int[] {1, 1}, // run lengths
            new int[] {100, 200}, // lengths
            new long[] {1, 0} // stored offsets: first=1 (actual=0), second=0 (clustered)
            );
    java.util.List<PmtilesEntry> entries = PmtilesReader.decodeDirectory(raw);
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).offset()).isEqualTo(0); // stored=1, actual=0
    assertThat(entries.get(1).offset()).isEqualTo(100); // clustered: 0+100=100
  }

  // ── Decompression ─────────────────────────────────────────────────────────

  @Test
  void decompress_noneCompression_returnsOriginal() throws IOException {
    byte[] data = {1, 2, 3};
    assertThat(PmtilesReader.decompress(data, PmtilesHeader.COMPRESSION_NONE)).isEqualTo(data);
  }

  @Test
  void decompress_zeroCompression_returnsOriginal() throws IOException {
    byte[] data = {1, 2, 3};
    assertThat(PmtilesReader.decompress(data, 0)).isEqualTo(data);
  }

  @Test
  void decompress_gzip_returnsDecompressed() throws IOException {
    byte[] original = "hello PMTiles".getBytes();
    byte[] compressed;
    try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(baos)) {
      gzip.write(original);
      gzip.finish();
      compressed = baos.toByteArray();
    }
    byte[] result = PmtilesReader.decompress(compressed, PmtilesHeader.COMPRESSION_GZIP);
    assertThat(result).isEqualTo(original);
  }

  @Test
  void decompress_unsupportedCompression_throwsUnsupportedOperation() {
    assertThatThrownBy(() -> PmtilesReader.decompress(new byte[] {1}, 3))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("compression");
  }

  // ── Gzip-compressed tile fixture ──────────────────────────────────────────

  @Test
  void getTile_gzipCompressedTile_returnsCompressedBytesAndCorrectCompression() throws IOException {
    URL gzipUrl = PmtilesReaderTest.class.getClassLoader().getResource("test_fixture_gzip.pmtiles");
    assertThat(gzipUrl).isNotNull();
    Path gzipPath = Paths.get(gzipUrl.getPath());
    try (PmtilesReader reader = new PmtilesReader(gzipPath)) {
      assertThat(reader.getHeader().tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_GZIP);
      Optional<TileResult> result = reader.getTile(0, 0, 0);
      assertThat(result).isPresent();
      assertThat(result.get().tileCompression()).isEqualTo(PmtilesHeader.COMPRESSION_GZIP);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private byte[] buildDirectory(long[] idDeltas, int[] runLengths, int[] lengths, long[] offsets) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    writeVarint(out, idDeltas.length);
    for (long d : idDeltas) writeVarint(out, d);
    for (int r : runLengths) writeVarint(out, r);
    for (int l : lengths) writeVarint(out, l);
    for (long o : offsets) writeVarint(out, o);
    return out.toByteArray();
  }

  private void writeVarint(java.io.ByteArrayOutputStream out, long v) {
    while (true) {
      int b = (int) (v & 0x7F);
      v >>>= 7;
      if (v != 0) b |= 0x80;
      out.write(b);
      if (v == 0) break;
    }
  }
}
