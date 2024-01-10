package btools.statcoding.arithmetic;

import java.io.IOException;

import btools.statcoding.BitOutputStream;

/**
 * Encoder for 2nd-order arithmetic encoding + run-length-escape.
 *
 * 2nd-order means that statistics are counted separately for each
 * preceding symbol, thus taking neighbor correlations into account.
 *
 * RlA2Encoder is adapted for 2-pass encoding (pass1: collect stats, pass2: encode).
 *
 * See the image example for example usage.
 *
 * @see RlA2Decoder
 */
public class RlA2Encoder {

    private final long maxValue;
    private final long minRunLength;
    private long lastValue;
    private long contextValue;
    private long repCount;
    private final ACContextEncoder[] encoders;
    private static final int rleEscape = 0;
    private int pass;
    private ArithmeticEncoder aEncoder;

    public RlA2Encoder(long maxValue, long minRunLength) {
        this.maxValue = maxValue;
        this.minRunLength = minRunLength;
        int n = (int) (maxValue + 2); // [0..maxValue,runLength]
        encoders = new ACContextEncoder[n];
        for (int i = 0; i < n; i++) {
            encoders[i] = new ACContextEncoder();
        }
    }

    public void init(BitOutputStream bos) throws IOException {
        if (++pass == 2) {
            bos.encodeUnsignedVarBits(maxValue, 0);
            aEncoder = new ArithmeticEncoder(bos);
        }
        for (ACContextEncoder encoder: encoders) {
            encoder.init(aEncoder);
        }
        repCount = 0;
        lastValue = 0L;
        contextValue = 0L;
    }

    public void encodeValue(long value) throws IOException {
        if (value < 0L || value > maxValue) {
            throw new IllegalArgumentException("invalid value: " + value + " (maxValue=" + maxValue + ")");
        }
        if (value != lastValue) {
            flushLastValue();
        }
        lastValue = value;
        repCount++;
    }

    private void flushLastValue() throws IOException {
        if (repCount >= minRunLength) {
            encoders[(int) contextValue].write(rleEscape); // prefix run-length escape
            encoders[encoders.length - 1].write((int) repCount); // write run-length
            repCount = 1;
        }
        while (repCount > 0) {
            encoders[(int) contextValue].write((int) (lastValue + 1L));
            contextValue = lastValue;
            repCount--;
        }
    }

    public void finish() throws IOException {
        flushLastValue();
        if (aEncoder != null) {
            aEncoder.finish();
        }
    }
}
