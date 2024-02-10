package btools.statcoding;

import junit.framework.TestCase;

public class BitInterleavedConverterTest extends TestCase {

    public void testConsistency() {
      for (long l = 1L; l < (1L << 58); l *= 17L ) {
        long il = BitInterleavedConverter.shift32Interleaved(l);
        long s32 = BitInterleavedConverter.interleaved2Shift32(il);
        assertEquals(s32, l);
      }
    }
    public void testSingleBits() {
      for (int idx = 0;  idx<64; idx++) {
        long l = 1L << idx;
        long il = BitInterleavedConverter.shift32Interleaved(l);
        long s32 = BitInterleavedConverter.interleaved2Shift32(il);
        long ilExpected = 1L << ( idx < 32 ? idx*2 : (idx-32)*2+1 );
        assertEquals(il,ilExpected);
        assertEquals(l,s32);
    }
  }
}
