package org.lockard.xyztilecache.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Tile;
import org.lockard.xyztilecache.model.TileResult;

class RasterTileHandlerTest {

  // ── detectContentType ─────────────────────────────────────────────────────

  @Test
  void detectContentType_pngMagic_returnsPng() {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    assertThat(RasterTileHandler.detectContentType(png)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_jpegMagic_returnsJpeg() {
    byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10};
    assertThat(RasterTileHandler.detectContentType(jpeg)).isEqualTo("image/jpeg");
  }

  @Test
  void detectContentType_gif87aMagic_returnsGif() {
    byte[] gif = {'G', 'I', 'F', '8', '7', 'a'};
    assertThat(RasterTileHandler.detectContentType(gif)).isEqualTo("image/gif");
  }

  @Test
  void detectContentType_gif89aMagic_returnsGif() {
    byte[] gif = {'G', 'I', 'F', '8', '9', 'a'};
    assertThat(RasterTileHandler.detectContentType(gif)).isEqualTo("image/gif");
  }

  @Test
  void detectContentType_webpMagic_returnsWebp() {
    byte[] webp = {'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P'};
    assertThat(RasterTileHandler.detectContentType(webp)).isEqualTo("image/webp");
  }

  @Test
  void detectContentType_unrecognized_returnsPngFallback() {
    byte[] junk = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c};
    assertThat(RasterTileHandler.detectContentType(junk)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_empty_returnsPngFallback() {
    assertThat(RasterTileHandler.detectContentType(new byte[0])).isEqualTo("image/png");
  }

  @Test
  void detectContentType_tooShortForJpeg_fallsThrough() {
    // Only two bytes — JPEG check requires 3
    byte[] data = {(byte) 0xff, (byte) 0xd8};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_jpegSecondByteWrong_fallsThrough() {
    byte[] data = {(byte) 0xff, 0x00, (byte) 0xff};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_jpegThirdByteWrong_fallsThrough() {
    byte[] data = {(byte) 0xff, (byte) 0xd8, 0x00};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_gifTooShort_fallsThrough() {
    byte[] data = {'G', 'I', 'F', '8', '9'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_gifWrongFourthByte_fallsThrough() {
    byte[] data = {'G', 'I', 'F', 'X', '9', 'a'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_gifWrongFifthByte_fallsThrough() {
    byte[] data = {'G', 'I', 'F', '8', '8', 'a'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_gifWrongTrailingByte_fallsThrough() {
    byte[] data = {'G', 'I', 'F', '8', '9', 'b'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_webpTooShort_fallsThrough() {
    byte[] data = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  @Test
  void detectContentType_riffButNotWebp_fallsThrough() {
    byte[] data = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    assertThat(RasterTileHandler.detectContentType(data)).isEqualTo("image/png");
  }

  // ── sourceTypes / getTile ─────────────────────────────────────────────────

  @Test
  void sourceTypes_includesAllRasterTypes() {
    @SuppressWarnings("unchecked")
    LoadingCache<Tile, byte[]> cache = mock(LoadingCache.class);
    RasterTileHandler handler = new RasterTileHandler(cache);
    assertThat(handler.sourceTypes())
        .containsExactlyInAnyOrder(
            Layer.SourceType.XYZ,
            Layer.SourceType.WMTS_REST,
            Layer.SourceType.WMTS_KVP,
            Layer.SourceType.LOCAL);
  }

  @Test
  void getTile_cacheHit_returnsTileWithDetectedContentType() throws Exception {
    @SuppressWarnings("unchecked")
    LoadingCache<Tile, byte[]> cache = mock(LoadingCache.class);
    byte[] jpegBytes = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
    Layer layer = testLayer();
    when(cache.get(new Tile(layer, 1, 2, 3))).thenReturn(jpegBytes);

    RasterTileHandler handler = new RasterTileHandler(cache);
    Optional<TileResult> result = handler.getTile(layer, 3, 1, 2);

    assertThat(result).isPresent();
    assertThat(result.get().contentType()).isEqualTo("image/jpeg");
    assertThat(result.get().data()).isEqualTo(jpegBytes);
  }

  @Test
  void getTile_cacheMiss_throwsTileNotFoundWithCause() throws Exception {
    @SuppressWarnings("unchecked")
    LoadingCache<Tile, byte[]> cache = mock(LoadingCache.class);
    Layer layer = testLayer();
    RuntimeException cause = new RuntimeException("upstream 404");
    when(cache.get(new Tile(layer, 1, 2, 3))).thenThrow(new CompletionException(cause));

    RasterTileHandler handler = new RasterTileHandler(cache);

    assertThatThrownBy(() -> handler.getTile(layer, 3, 1, 2))
        .isInstanceOf(TileNotFoundException.class)
        .hasCause(cause);
  }

  private static Layer testLayer() {
    return new org.lockard.xyztilecache.model.XyzLayer(
        "test",
        "test",
        "http://example.com/{z}/{x}/{y}.png",
        null,
        22,
        0,
        0,
        java.util.List.of(),
        java.util.List.of(),
        java.util.Map.of(),
        null);
  }
}
