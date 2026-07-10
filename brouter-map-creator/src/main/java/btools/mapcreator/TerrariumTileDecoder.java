package btools.mapcreator;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes a Terrarium-encoded terrain-RGB image tile into elevations in metres.
 * <p>
 * Terrarium packs the elevation into the three colour channels:
 *
 * <pre>elevation = (R * 256 + G + B / 256) - 32768</pre>
 * <p>
 * which resolves 1/256 m over the full short range. The encoding is only meaningful on a
 * lossless image; Mapterhorn ships lossless WebP (VP8L), where the blue channel carries
 * the low byte and any lossy smoothing would destroy it.
 * <p>
 * Elevations below {@link #MIN_VALID_ELEVATION} are treated as no-data rather than as
 * bathymetry. Note the cutoff cannot be raised much: the shore of the Dead Sea, real
 * routable land, sits near -430 m.
 */
public final class TerrariumTileDecoder {

  /**
   * Terrarium encodes rgb(0,0,0) as -32768, which marks absent data rather than a place.
   * No land on earth is below -450 m; -1000 m leaves ample headroom.
   */
  public static final double MIN_VALID_ELEVATION = -1000.0;

  private static final int MAX_TILE_DIMENSION = 4096;
  private static final long MAX_TILE_PIXELS =
    (long) MAX_TILE_DIMENSION * MAX_TILE_DIMENSION;

  static {
    // tiles arrive as in-memory byte arrays; the default ImageIO behaviour would back
    // every read with a temp FILE in java.io.tmpdir - millions of pointless create/
    // write/delete cycles over a planet build
    ImageIO.setUseCache(false);
  }

  private TerrariumTileDecoder() {
  }

  /**
   * A decoded square tile. Elevations are metres, row-major from the top-left (north-west)
   * corner, with {@link Float#NaN} for no-data.
   */
  public static final class Tile {
    private final int size;
    private final float[] elevations;

    Tile(int size, float[] elevations) {
      this.size = size;
      this.elevations = elevations;
    }

    public int size() {
      return size;
    }

    public float[] elevations() {
      return elevations;
    }

    public float get(int x, int y) {
      return elevations[y * size + x];
    }
  }

  /**
   * Decode raw image bytes (WebP or PNG) into elevations.
   *
   * @throws IOException when the image cannot be read (for WebP usually a missing ImageIO
   *                     plugin), or when a WebP tile is not lossless: ImageIO decodes
   *                     lossy VP8 without complaint, and the quantization noise would
   *                     silently corrupt elevations (a 1-count error in R is 256 m).
   */
  public static Tile decode(byte[] imageBytes) throws IOException {
    return decodeImage(imageBytes, -1);
  }

  /**
   * Decode a production tile and reject a size mismatch from the image header before
   * allocating its pixel buffer.
   */
  public static Tile decode(byte[] imageBytes, int expectedTileSize) throws IOException {
    if (expectedTileSize <= 0 || expectedTileSize > MAX_TILE_DIMENSION) {
      throw new IllegalArgumentException("expected terrain tile size must be in 1.."
        + MAX_TILE_DIMENSION + ": " + expectedTileSize);
    }
    return decodeImage(imageBytes, expectedTileSize);
  }

  private static Tile decodeImage(byte[] imageBytes, int expectedTileSize) throws IOException {
    if (imageBytes == null) {
      throw new IllegalArgumentException("terrain tile bytes are required");
    }
    if (isWebP(imageBytes)) {
      requireLosslessWebP(imageBytes);
    }

    try (ImageInputStream input = ImageIO.createImageInputStream(
        new ByteArrayInputStream(imageBytes))) {
      if (input == null) {
        throw new IOException(describeUndecodable(imageBytes));
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new IOException(describeUndecodable(imageBytes));
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, false, true);
        int width;
        int height;
        try {
          width = reader.getWidth(0);
          height = reader.getHeight(0);
        } catch (IOException | RuntimeException e) {
          throw readerFailure(imageBytes, "read the header of", e);
        }
        int pixelCount = validateDimensions(width, height, expectedTileSize);

        BufferedImage image;
        try {
          image = reader.read(0);
        } catch (IOException | RuntimeException e) {
          throw readerFailure(imageBytes, "decode", e);
        }
        if (image == null) {
          throw new IOException(describeUndecodable(imageBytes));
        }
        if (image.getWidth() != width || image.getHeight() != height) {
          throw new IOException("terrain tile dimensions changed during decode: header "
            + width + "x" + height + ", image " + image.getWidth() + "x"
            + image.getHeight());
        }
        return decodePixels(image, pixelCount);
      } finally {
        reader.dispose();
      }
    }
  }

  private static int validateDimensions(int width, int height, int expectedTileSize)
      throws IOException {
    if (width <= 0 || height <= 0) {
      throw new IOException("terrain tile has invalid dimensions: " + width + "x" + height);
    }
    if (width > MAX_TILE_DIMENSION || height > MAX_TILE_DIMENSION) {
      throw new IOException("terrain tile dimensions " + width + "x" + height
        + " exceed maximum " + MAX_TILE_DIMENSION + "x" + MAX_TILE_DIMENSION);
    }
    if (width != height) {
      throw new IOException("terrain tile is not square: " + width + "x" + height);
    }
    if (expectedTileSize > 0 && width != expectedTileSize) {
      throw new IOException("terrain tile size mismatch: expected " + expectedTileSize + "x"
        + expectedTileSize + ", got " + width + "x" + height);
    }

    long pixelCount;
    try {
      pixelCount = Math.multiplyExact((long) width, (long) height);
    } catch (ArithmeticException e) {
      throw new IOException("terrain tile pixel count overflow: " + width + "x" + height, e);
    }
    if (pixelCount > MAX_TILE_PIXELS) {
      throw new IOException("terrain tile pixel count " + pixelCount + " exceeds maximum "
        + MAX_TILE_PIXELS);
    }
    return (int) pixelCount;
  }

  private static Tile decodePixels(BufferedImage image, int pixelCount) throws IOException {
    int width = image.getWidth();
    int height = image.getHeight();
    Raster raster = image.getRaster();
    int bands = raster.getNumBands();
    if (bands < 3) {
      throw new IOException("terrain tile needs 3 colour channels, found " + bands);
    }
    int[] sampleSizes = raster.getSampleModel().getSampleSize();
    if (sampleSizes[0] != 8 || sampleSizes[1] != 8 || sampleSizes[2] != 8) {
      throw new IOException("terrain tile needs 8-bit RGB channels, found sample sizes "
        + Arrays.toString(Arrays.copyOf(sampleSizes, 3)));
    }

    long sampleCount;
    try {
      sampleCount = Math.multiplyExact((long) pixelCount, (long) bands);
    } catch (ArithmeticException e) {
      throw new IOException("terrain tile sample count overflow: " + pixelCount
        + " pixels x " + bands + " bands", e);
    }
    if (sampleCount > Integer.MAX_VALUE) {
      throw new IOException("terrain tile sample count exceeds Java array limit: "
        + sampleCount);
    }

    int[] samples;
    try {
      samples = raster.getPixels(0, 0, width, height, (int[]) null);
    } catch (RuntimeException e) {
      throw new IOException("cannot read terrain tile pixels", e);
    }
    if (samples.length != (int) sampleCount) {
      throw new IOException("terrain tile returned an unexpected sample count: expected "
        + sampleCount + ", got " + samples.length);
    }
    float[] elevations = new float[pixelCount];
    for (int i = 0, p = 0; i < elevations.length; i++, p += bands) {
      double elevation = decodeSample(samples[p], samples[p + 1], samples[p + 2]);
      elevations[i] = elevation < MIN_VALID_ELEVATION ? Float.NaN : (float) elevation;
    }
    return new Tile(width, elevations);
  }

  private static IOException readerFailure(byte[] imageBytes, String action, Exception cause) {
    String format = isWebP(imageBytes) ? "WebP terrain tile" : "terrain tile";
    String detail = cause.getMessage();
    return new IOException("cannot " + action + " " + format
      + (detail == null || detail.isEmpty() ? "" : ": " + detail), cause);
  }

  /**
   * The Terrarium formula, exposed for testing.
   */
  public static double decodeSample(int r, int g, int b) {
    return (r * 256.0 + g + b / 256.0) - 32768.0;
  }

  /**
   * Fail loudly and usefully when the WebP plugin is absent, rather than returning null
   * pixels that would silently become no-data across whole regions.
   */
  private static String describeUndecodable(byte[] bytes) {
    if (isWebP(bytes)) {
      return "tile is WebP but no WebP ImageIO plugin is on the classpath "
        + "(add com.github.usefulness:webp-imageio)";
    }
    return "no ImageIO reader accepted the terrain tile (" + bytes.length + " bytes)";
  }

  public static boolean isWebP(byte[] bytes) {
    return bytes.length > 12
      && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
      && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
  }

  /**
   * Enforce the losslessness invariant on the container level: only the VP8L variant is
   * lossless. Lossy VP8 (and VP8X, whose sub-chunks may hide either variant) are rejected
   * loudly rather than decoded into metre-scale noise.
   */
  static void requireLosslessWebP(byte[] bytes) throws IOException {
    if (bytes.length < 16) {
      throw new IOException("truncated WebP tile (" + bytes.length + " bytes)");
    }
    if (bytes[12] == 'V' && bytes[13] == 'P' && bytes[14] == '8' && bytes[15] == 'L') {
      return;
    }
    String fourcc = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
    throw new IOException("WebP tile is not lossless VP8L (found '" + fourcc
      + "'); refusing to decode elevation from a lossy or extended-container tile");
  }

  /**
   * Check up-front that WebP tiles will decode, so a long conversion does not run for
   * minutes before failing on the first tile.
   */
  public static void requireWebPSupport() throws IOException {
    ImageIO.scanForPlugins();
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
    if (!readers.hasNext()) {
      throw new IOException("no WebP ImageIO plugin found; "
        + "add com.github.usefulness:webp-imageio to the classpath");
    }
  }
}
