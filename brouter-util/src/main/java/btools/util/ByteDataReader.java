/**
 * fast data-reading from a byte-array
 *
 * @author ab
 */
package btools.util;


public class ByteDataReader {
  protected byte[] ab;
  protected int aboffset;
  protected int aboffsetEnd;

  public ByteDataReader(byte[] byteArray) {
    ab = byteArray;
    aboffsetEnd = ab == null ? 0 : ab.length;
  }

  public ByteDataReader(byte[] byteArray, int offset) {
    ab = byteArray;
    aboffset = offset;
    aboffsetEnd = ab == null ? 0 : ab.length;
  }

  public final void reset(byte[] byteArray) {
    ab = byteArray;
    aboffset = 0;
    aboffsetEnd = ab == null ? 0 : ab.length;
  }


  public final int readInt() {
    int i3 = ab[aboffset++] & 0xff;
    int i2 = ab[aboffset++] & 0xff;
    int i1 = ab[aboffset++] & 0xff;
    int i0 = ab[aboffset++] & 0xff;
    return (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }
}
