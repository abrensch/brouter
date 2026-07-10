package btools.mapcreator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Generates Terrarium-encoded terrain tiles as PNG, so the decoder and the resampler can
 * be exercised without a WebP plugin and without network access. PNG and WebP both reach
 * {@link TerrariumTileDecoder} as an ImageIO-decodable byte array.
 */
public final class TerrainTiles {

  /** Elevation in metres as a function of the tile's own pixel coordinates. */
  public interface PixelRgb {
    int[] apply(int x, int y);
  }

  /** Elevation in metres as a function of global source pixel coordinates. */
  public interface Elevation {
    double at(int globalX, int globalY);
  }

  private TerrainTiles() {
  }

  /**
   * Inverse of {@link TerrariumTileDecoder#decodeSample}: elevation to Terrarium rgb.
   */
  public static int[] encode(double elevation) {
    long v = Math.round((elevation + 32768.0) * 256.0);
    v = Math.max(0L, Math.min(0xffffffL, v));
    return new int[]{(int) ((v >>> 16) & 0xff), (int) ((v >>> 8) & 0xff), (int) (v & 0xff)};
  }

  public static byte[] rgbPng(int size, PixelRgb fn) throws IOException {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int[] rgb = fn.apply(x, y);
        img.setRGB(x, y, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
      }
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ImageIO.write(img, "png", bos);
    return bos.toByteArray();
  }

  public static byte[] constantPng(int size, double elevation) throws IOException {
    int[] rgb = encode(elevation);
    return rgbPng(size, (x, y) -> rgb);
  }

  /**
   * A tile of the global field {@code elevation}, for tile (tx, ty) at the given tile size.
   */
  public static byte[] tilePng(int size, int tx, int ty, Elevation elevation) throws IOException {
    return rgbPng(size, (x, y) -> encode(elevation.at(tx * size + x, ty * size + y)));
  }
}
