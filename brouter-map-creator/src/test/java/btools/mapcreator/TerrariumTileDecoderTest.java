package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.Test;

public class TerrariumTileDecoderTest {

  @Test
  public void decodesTheTerrariumFormula() {
    // rgb(0,0,0) is the no-data / ocean floor marker
    assertEquals(-32768.0, TerrariumTileDecoder.decodeSample(0, 0, 0), 1e-9);
    // 128 * 256 - 32768 == 0
    assertEquals(0.0, TerrariumTileDecoder.decodeSample(128, 0, 0), 1e-9);
    assertEquals(1.0, TerrariumTileDecoder.decodeSample(128, 1, 0), 1e-9);
    // the blue channel carries 1/256 m, which is why the tiles must be lossless
    assertEquals(0.5, TerrariumTileDecoder.decodeSample(128, 0, 128), 1e-9);
    assertEquals(1.0 / 256.0, TerrariumTileDecoder.decodeSample(128, 0, 1), 1e-9);
    assertEquals(4478.0, TerrariumTileDecoder.decodeSample(145, 126, 0), 1e-9);
  }

  /**
   * The Dead Sea shore is routable land at about -430 m, so the no-data cutoff has to sit
   * well below it. BRouter's Esri-ASCII reader uses -250, which would erase it.
   */
  @Test
  public void deadSeaShoreIsValidElevation() {
    assertTrue(-430.0 > TerrariumTileDecoder.MIN_VALID_ELEVATION);
  }

  @Test
  public void decodesConstantTileFromPng() throws IOException {
    byte[] png = TerrainTiles.constantPng(8, 250.0);
    TerrariumTileDecoder.Tile tile = TerrariumTileDecoder.decode(png);
    assertEquals(8, tile.size());
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        assertEquals(250.0, tile.get(x, y), 1.0 / 256.0);
      }
    }
  }

  @Test
  public void expectedTileSizeIsCheckedFromTheImageHeader() throws IOException {
    byte[] png = TerrainTiles.constantPng(8, 250.0);

    assertEquals(8, TerrariumTileDecoder.decode(png, 8).size());
    try {
      TerrariumTileDecoder.decode(png, 16);
      fail("expected a tile-size mismatch");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("expected 16x16"));
      assertTrue(e.getMessage(), e.getMessage().contains("8x8"));
    }
  }

  @Test
  public void oversizedDimensionsAreRejectedFromAMalformedPngHeader() throws IOException {
    byte[] png = TerrainTiles.constantPng(1, 250.0);
    setPngDimensions(png, 4097, 4097);

    try {
      TerrariumTileDecoder.decode(png);
      fail("expected oversized advertised dimensions to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("4097x4097"));
      assertTrue(e.getMessage(), e.getMessage().contains("maximum"));
    }
  }

  @Test
  public void nonSquareDimensionsAreRejectedFromTheImageHeader() throws IOException {
    BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));

    try {
      TerrariumTileDecoder.decode(output.toByteArray());
      fail("expected non-square advertised dimensions to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not square"));
      assertTrue(e.getMessage(), e.getMessage().contains("8x4"));
    }
  }

  @Test
  public void sixteenBitRgbPngIsRejectedBeforeSampleDecode() throws IOException {
    byte[] png = sixteenBitRgbPng();
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    assertArrayEquals("test PNG must preserve 16-bit RGB samples",
      new int[]{16, 16, 16}, Arrays.copyOf(image.getSampleModel().getSampleSize(), 3));

    try {
      TerrariumTileDecoder.decode(png);
      fail("expected 16-bit RGB samples to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("8-bit"));
      assertTrue(e.getMessage(), e.getMessage().contains("16"));
    }
  }

  @Test
  public void mapsBlackPixelsToNoData() throws IOException {
    byte[] png = TerrainTiles.rgbPng(4, (x, y) -> new int[]{0, 0, 0});
    TerrariumTileDecoder.Tile tile = TerrariumTileDecoder.decode(png);
    for (float v : tile.elevations()) {
      assertTrue("rgb(0,0,0) must decode to no-data", Float.isNaN(v));
    }
  }

  @Test
  public void preservesRowMajorOrientationFromTopLeft() throws IOException {
    // elevation encodes the pixel's own row, so orientation errors are visible
    byte[] png = TerrainTiles.rgbPng(4, (x, y) -> TerrainTiles.encode(100.0 + y));
    TerrariumTileDecoder.Tile tile = TerrariumTileDecoder.decode(png);
    for (int y = 0; y < 4; y++) {
      for (int x = 0; x < 4; x++) {
        assertEquals(100.0 + y, tile.get(x, y), 1.0 / 256.0);
      }
    }
  }

  @Test
  public void recognisesWebPMagic() {
    byte[] webp = new byte[16];
    System.arraycopy(new byte[]{'R', 'I', 'F', 'F'}, 0, webp, 0, 4);
    System.arraycopy(new byte[]{'W', 'E', 'B', 'P'}, 0, webp, 8, 4);
    assertTrue(TerrariumTileDecoder.isWebP(webp));
    assertFalse(TerrariumTileDecoder.isWebP(new byte[]{1, 2, 3}));
  }

  /**
   * A missing WebP plugin must not be mistaken for a tile full of no-data.
   */
  @Test
  public void undecodableBytesThrowRatherThanReturnNoData() {
    byte[] webp = new byte[16];
    System.arraycopy(new byte[]{'R', 'I', 'F', 'F'}, 0, webp, 0, 4);
    System.arraycopy(new byte[]{'W', 'E', 'B', 'P'}, 0, webp, 8, 4);
    try {
      TerrariumTileDecoder.decode(webp);
      fail("expected an IOException for undecodable bytes");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("WebP"));
    }
  }

  @Test
  public void truncatedLosslessWebPStillReportsItsFormat() {
    byte[] webp = webpHeader('V', 'P', '8', 'L');
    try {
      TerrariumTileDecoder.decode(webp);
      fail("expected truncated lossless WebP to fail");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("WebP"));
    }
  }

  /**
   * The production path reads WebP, so the plugin must actually be on the classpath.
   */
  @Test
  public void webPPluginIsAvailable() throws IOException {
    TerrariumTileDecoder.requireWebPSupport();
  }

  /**
   * Lossy WebP decodes without error in ImageIO but destroys the Terrarium encoding
   * (1 count in R = 256 m), so the decoder must reject anything but VP8L up front.
   */
  @Test
  public void lossyWebPIsRejectedLoudly() {
    byte[] lossy = webpHeader('V', 'P', '8', ' ');
    try {
      TerrariumTileDecoder.decode(lossy);
      fail("expected rejection of lossy VP8");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not lossless"));
    }
    byte[] extended = webpHeader('V', 'P', '8', 'X');
    try {
      TerrariumTileDecoder.decode(extended);
      fail("expected rejection of extended-container VP8X");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not lossless"));
    }
  }

  /**
   * Full production-path check: encode Terrarium values as LOSSLESS WebP with the
   * bundled plugin, decode through the real code path, and require exact elevations.
   */
  @Test
  public void losslessWebPRoundTripsExactly() throws IOException {
    int size = 16;
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int[] rgb = TerrainTiles.encode(372.0 + x + y / 256.0);
        img.setRGB(x, y, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
      }
    }

    ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionType("Lossless");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(img, null, null), param);
    } finally {
      writer.dispose();
    }
    byte[] webp = bos.toByteArray();
    assertTrue("writer must produce WebP", TerrariumTileDecoder.isWebP(webp));

    TerrariumTileDecoder.Tile tile = TerrariumTileDecoder.decode(webp);
    assertEquals(size, tile.size());
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        assertEquals("pixel " + x + "," + y, 372.0 + x + y / 256.0, tile.get(x, y), 1e-6);
      }
    }
  }

  private static byte[] webpHeader(char a, char b, char c, char d) {
    byte[] bytes = new byte[24];
    bytes[0] = 'R';
    bytes[1] = 'I';
    bytes[2] = 'F';
    bytes[3] = 'F';
    bytes[8] = 'W';
    bytes[9] = 'E';
    bytes[10] = 'B';
    bytes[11] = 'P';
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    return bytes;
  }

  private static byte[] sixteenBitRgbPng() throws IOException {
    int size = 2;
    ColorModel colorModel = new ComponentColorModel(
      ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[]{16, 16, 16}, false, false,
      Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
    WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT,
      size, size, size * 3, 3, new int[]{0, 1, 2}, null);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        raster.setPixel(x, y, new int[]{32768, 256 + x, 128 + y});
      }
    }
    BufferedImage image = new BufferedImage(colorModel, raster, false, null);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", output)) {
      throw new IOException("no PNG writer available for 16-bit RGB test image");
    }
    return output.toByteArray();
  }

  private static void setPngDimensions(byte[] png, int width, int height) {
    putIntBigEndian(png, 16, width);
    putIntBigEndian(png, 20, height);
    CRC32 crc = new CRC32();
    crc.update(png, 12, 17); // IHDR type and payload
    putIntBigEndian(png, 29, (int) crc.getValue());
  }

  private static void putIntBigEndian(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }
}
