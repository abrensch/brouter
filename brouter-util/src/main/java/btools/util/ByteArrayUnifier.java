package btools.util;

import btools.statcoding.Crc64;

public final class ByteArrayUnifier {
  private final byte[][] byteArrayCache;
  private final int size;

  public ByteArrayUnifier(int size) {
    this.size = size;
    byteArrayCache = new byte[size][];
  }

  /**
   * Unify a byte array in order to reuse instances when possible.
   * The byte arrays are assumed to be treated as immutable,
   * allowing the reuse
   *
   * @param ab the byte array to unify
   * @return the cached instance or a copy of the input instance if not yet cached
   */
  public byte[] unify(byte[] ab) {
    int len = ab.length;
    long crc = Crc64.crc(ab, 0, len);
    int idx = ((int)crc & 0xfffffff) % size;
    byte[] abc = byteArrayCache[idx];
    if (abc != null && abc.length == len) {
      int i = 0;
      while (i < len) {
        if (ab[i] != abc[i])
          break;
        i++;
      }
      if (i == len)
        return abc;
    }
    byte[] nab = new byte[len];
    System.arraycopy(ab, 0, nab, 0, len);
    byteArrayCache[idx] = nab;
    return nab;
  }
}
