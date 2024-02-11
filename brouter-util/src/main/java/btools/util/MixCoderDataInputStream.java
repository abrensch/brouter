/**
 * DataInputStream for decoding fast-compact encoded number sequences
 *
 * @author ab
 */
package btools.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;


public final class MixCoderDataInputStream extends DataInputStream {
  private int lastValue;
  private int repCount;
  private int diffshift;

  private int bits; // bits left in buffer
  private int b; // buffer word

  public MixCoderDataInputStream(InputStream is) {
    super(is);
  }

  public int readMixed() throws IOException {
    if (repCount == 0) {
      boolean negative = decodeBit();
      int d = decodeVarBits() + diffshift;
      repCount = decodeVarBits() + 1;
      lastValue += negative ? -d : d;
      diffshift = 1;
    }
    repCount--;
    return lastValue;
  }

  public boolean decodeBit() throws IOException {
    fillBuffer();
    boolean value = ((b & 1) != 0);
    b >>>= 1;
    bits--;
    return value;
  }

  public int decodeVarBits() throws IOException {
    int range = 0;
    while (!decodeBit()) {
      range = 2 * range + 1;
    }
    return range + decodeBounded(range);
  }

  /**
   * decode an integer in the range 0..max (inclusive).
   */
  public int decodeBounded(int max) throws IOException {
    int value = 0;
    int im = 1; // integer mask
    while ((value | im) <= max) {
      if (decodeBit()) {
        value |= im;
      }
      im <<= 1;
    }
    return value;
  }


  private void fillBuffer() throws IOException {
    while (bits < 24) {
      int nextByte = read();

      if (nextByte != -1) {
        b |= (nextByte & 0xff) << bits;
      }
      bits += 8;
    }
  }

}
