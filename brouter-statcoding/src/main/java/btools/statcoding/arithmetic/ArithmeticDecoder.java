package btools.statcoding.arithmetic;

import java.io.IOException;

import btools.statcoding.BitInputStream;

/**
 * Reads from an arithmetic-coded bit stream and decodes symbols.
 * <br><br>
 * This code is mostly taken from:
 * https://github.com/nayuki/Reference-arithmetic-coding
 *
 * @see ArithmeticEncoder
 */
public final class ArithmeticDecoder extends ArithmeticCoderBase {

    // The underlying bit input stream
    private final BitInputStream input;

    // The current raw code bits being buffered, which is always in the range [low,
    // high].
    private long code;

    private boolean initialized = false;

    /**
     * Constructs an arithmetic coding decoder based on the specified bit input
     * stream, and fills the code bits.
     * 
     * @param in      the bit input stream to read from
     */
    public ArithmeticDecoder(BitInputStream in) {
        input = in;
    }

    public BitInputStream getInputStream() {
        return input;
    }

    /**
     * Decodes the next symbol based on the specified frequency table and returns
     * it. Also updates this arithmetic coder's state and may read in some bits.
     * 
     * @param stats the (integrated) frequency table to use
     * @return the next symbol
     * @throws IllegalArgumentException if the frequency table's total is too large
     * @throws IOException              if an I/O exception occurred
     */
    public int read(long[] stats) throws IOException {

        if (!initialized) { // check needs init
            initialized = true;
            for (int i = 0; i < numStateBits; i++) {
                code = code << 1 | readCodeBit();
            }
        }

        // Translate from coding range scale to frequency table scale
        long total = stats[stats.length - 1];

        if (total > maximumTotal)
            throw new IllegalArgumentException("Cannot decode symbol because total is too large");
        long range = high - low + 1;
        long offset = code - low;
        long value = ((offset + 1) * total - 1) / range;
        if (value * range / total > offset)
            throw new AssertionError();
        if (!(0 <= value && value < total))
            throw new AssertionError();

        // A kind of binary search. Find last symbol with stats[symbol-1] <= value.
        int start = 0;
        int end = stats.length;
        while (end - start > 1) {
            int middle = (start + end) >>> 1;
            long middleLow = middle == 0 ? 0L : stats[middle - 1];
            if (middleLow > value)
                end = middle;
            else
                start = middle;
        }
        if (start + 1 != end)
            throw new AssertionError();

        int symbol = start;

        long symLow = symbol == 0 ? 0L : stats[symbol - 1];
        long symHigh = stats[symbol];
        if (!(symLow * range / total <= offset && offset < symHigh * range / total))
            throw new AssertionError();

        update(stats, symbol);

        if (!(low <= code && code <= high))
            throw new AssertionError("Code out of range");

        return symbol;
    }

    protected void shift() throws IOException {
        code = ((code << 1) & stateMask) | readCodeBit();
    }

    protected void underflow() throws IOException {
        code = (code & halfRange) | ((code << 1) & (stateMask >>> 1)) | readCodeBit();
    }

    // Returns the next bit (0 or 1) from the input stream. The end
    // of stream is treated as an infinite number of trailing zeros.
    private int readCodeBit() throws IOException {
        return input.decodeBit() ? 1 : 0;
    }

}
