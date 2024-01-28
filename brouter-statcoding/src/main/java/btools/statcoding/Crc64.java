package btools.statcoding;


/**
 * Simple CRC-64 implementation. Standard ECMA-182,
 * http://www.ecma-international.org/publications/standards/Ecma-182.htm
 */
public class Crc64 {

    private final static long POLY = (long) 0xc96c5795d7870f42L; // ECMA-182

    /* CRC64 calculation table. */
    private final static long[] table;

    static
    {
        table = new long[256];

        for (int n = 0; n < 256; n++)
        {
            long crc = n;
            for (int k = 0; k < 8; k++)
            {
                if ((crc & 1) == 1)
                {
                    crc = (crc >>> 1) ^ POLY;
                }
                else
                {
                    crc = (crc >>> 1);
                }
            }
            table[n] = crc;
        }
    }
    public static long update(long crc, int b) {
        return table[(int) ((crc ^ b) & 0xff)] ^ (crc >>> 8);
    }

  public static long crc(byte[] ab, int offset, int len) {
    long crc = -1L;
    int end = offset + len;
    for (int j = offset; j < end; j++) {
      crc = update(crc, ab[j]);
    }
    return crc;
  }
}
