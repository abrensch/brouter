package btools.statcoding.arithmetic;

import java.io.IOException;
import java.util.*;

import btools.statcoding.BitOutputStream;

/**
 * Encoder for arithmetic encoding that manages the statistics and offers an
 * interface for 2-pass encoding.
 * <br><br>
 * It uses an additional indirection to re-map the symbols to encode to the
 * smaller set of symbols that are actually observed.
 * <br><br>
 * The actual arithmetic coder that encodes the (re-mapped) symbols to the
 * bitstream must be provided and can be shared over multiple-instance of
 * ACContextEncoder.
 *
 * @see ACContextDecoder
 */
public final class ACContextEncoder {

    // The underlying encoder
    private ArithmeticEncoder encoder;

    private final TreeMap<Integer, long[]> frequencies = new TreeMap<>();

    private long[] stats;
    private int pass;

    public void init(ArithmeticEncoder encoder) throws IOException {

        this.encoder = encoder;

        if (++pass == 2) {
            // prepare frequency table
            int size = frequencies.size();
            stats = new long[size];
            long[] idx2symbol = new long[size];
            int idx = 0;
            for (Integer iSymbol : frequencies.keySet()) {
                long[] freq = frequencies.get(iSymbol);
                stats[idx] = freq[0];
                freq[1] = idx;
                idx2symbol[idx] = iSymbol;
                idx++;
            }
            encoder.createStatsFromFrequencies(stats);
            BitOutputStream bos = encoder.getOutputStream();

            // encode statistics
            bos.encodeUnsignedVarBits(size, 0);
            if (size > 1) { // need no stats for size = 1
                bos.encodeUniqueSortedArray(stats, 0, size);
            }
            bos.encodeUniqueSortedArray(idx2symbol, 0, size);
        }
    }

    public void write(int symbol) throws IOException {
        long[] current = frequencies.get(symbol);
        if (pass < 2) {
            if (current == null) {
                current = new long[2]; // [frequency, index]
                frequencies.put(symbol, current);
            }
            current[0]++;
        } else {
            if (current == null) {
                throw new IllegalArgumentException("symbol " + symbol + " is unknown from pass1");
            }
            encoder.write(stats, (int) current[1]);
        }
    }
}
