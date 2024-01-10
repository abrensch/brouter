package btools.statcoding.arithmetic;

import java.io.IOException;

import btools.statcoding.BitInputStream;

/**
 * Decoder for arithmetic decoding that manages the statistics and the
 * re-mapping of symbols to the original numbering.
 * <br><br>
 * The actual arithmetic decoder that decodes the symbols from the bitstream
 * must be provided and can be shared over multiple-instance of ACContextDecoder.
 *
 * @see ACContextEncoder
 */
public final class ACContextDecoder {

    // The underlying decoder
    private ArithmeticDecoder decoder;

    private long[] stats;
    private long[] idx2symbol;

    public void init(ArithmeticDecoder decoder) throws IOException {

        this.decoder = decoder;

        BitInputStream bis = decoder.getInputStream();

        // decode statistics
        int size = (int) bis.decodeUnsignedVarBits(0);
        if (size > 1) { // need no stats for size = 1
            stats = new long[size];
            bis.decodeUniqueSortedArray(stats, 0, size);
        }
        if (size > 0) {
            idx2symbol = new long[size];
            bis.decodeUniqueSortedArray(idx2symbol, 0, size);
        }
    }

    public long read() throws IOException {

        if (idx2symbol == null) {
            throw new IllegalArgumentException("cannot read (no symbols)");
        }
        if (stats == null) {
            return idx2symbol[0];
        }

        int idx = decoder.read(stats);
        return idx2symbol[idx];
    }
}
