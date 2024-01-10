package btools.statcoding.arithmetic;

import java.io.IOException;

import btools.statcoding.BitInputStream;

/**
 * Decoding twin to RlA2Encoder
 *
 * @see RlA2Encoder
 */
public class RlA2Decoder {

    private long lastValue;
    private long repCount;
    private ACContextDecoder[] decoders;
    private ArithmeticDecoder aDecoder;
    private static final long rleEscape = 0L;

    public void init(BitInputStream bis) throws IOException {
        long maxValue = bis.decodeUnsignedVarBits(0);
        int n = (int) (maxValue) + 2;
        aDecoder = new ArithmeticDecoder(bis);
        decoders = new ACContextDecoder[n];
        for (int i = 0; i < n; i++) {
            decoders[i] = new ACContextDecoder();
            decoders[i].init(aDecoder);
        }
        repCount = 0;
        lastValue = 0L;
    }

    public long decodeValue() throws IOException {
        if (repCount > 0) {
            repCount--;
            return lastValue;
        }
        ACContextDecoder decoder = decoders[(int) lastValue];
        long v = decoder.read();
        if (v == rleEscape) {
            repCount = decoders[decoders.length - 1].read() - 1L;
            v = decoder.read();
            if (v == rleEscape) {
                throw new RuntimeException("unexpected rle!");
            }
        }
        lastValue = v - 1;
        return lastValue;
    }
}
