package btools.statcoding;

/**
 * BitInterleavedConverter does the transformations
 * between 3 representations of two 32-bit integers:
 *
 * - two integers x,y
 * - 64-bit interleaved : x31,y31,x30,y30,...,x0,y0
 * - 64-bit shift32     : x31..x0,y31..y0
 *
 * The interleaved representation is useful in particular
 * together with BitOutputStream.encodeUniqueSortedArray
 * when encoding a x,y point cloud, because it helps to
 * exploit local cluster correlations for compact encoding.
 */
public final class BitInterleavedConverter {

  private static final long[] s32_8 = new long[256];

  static {
    for (int i = 0; i < 256; i++) {
      s32_8[i] = interleaved2shift32_8bit(i);
    }
  }

  private static long interleaved2shift32_8bit(int v) {
    long s32 = 0L;
    for (int i=0; i<4; i++) {
      if ((v & 1) != 0) s32 |= 1L<<i;
      if ((v & 2) != 0) s32 |= 1L<<(i+32);
      v >>= 2;
    }
    return s32;
  }

  /**
   * convert a number pair from interleaved to shift32 representation
   */
  public static long interleaved2Shift32(long il) {
    long s32 = 0L;
    int shift = 0;
    while ( il != 0L ) {
      s32 |= s32_8[((int)il) & 255] << shift;
      shift+=4;
      il >>>= 8;
    }
    return s32;
  }

  /**
   * convert a x,y integer pair to shift32 representation
   */
  public static long xy2Shift32(int x, int y) {
    return ((long) x) << 32 | y;
  }

  /**
   * get the x member from shift32 representation
   */
  public static int xFromShift32(long s32) {
    return (int) (s32 >> 32);
  }

  /**
   * get the y member from shift32 representation
   */
  public static int yFromShift32(long s32) {
    return (int) (s32 & 0xffffffff);
  }

  /**
   * convert a number pair from shift32 to interleaved representation
   */
  public static long shift32Interleaved(long s32) {
    return xy2Interleaved(xFromShift32(s32), yFromShift32(s32));
  }

  /**
   * convert a number pair from x,y to interleaved representation
   */
  public static long xy2Interleaved(int x, int y) {
    long il = 0L;
    for (int bm = 0x80000000; bm != 0; bm >>>= 1) {
      il <<= 2;
      if ((x & bm) != 0) il |= 2L;
      if ((y & bm) != 0) il |= 1L;
    }
    return il;
  }

}
