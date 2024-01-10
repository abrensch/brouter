package btools.statcoding.arithmetic;

import java.io.IOException;

/**
 * Provides the state and behaviors that arithmetic coding encoders and decoders
 * share.
 * <br><br>
 * This code is mostly taken from:
 * https://github.com/nayuki/Reference-arithmetic-coding
 *
 * @see ArithmeticEncoder
 * @see ArithmeticDecoder
 */
public abstract class ArithmeticCoderBase {

    /**
     * Number of bits for the 'low' and 'high' state variables. Must be in the range
     * [1, 62].
     * <ul>
     * <li>For state sizes less than the midpoint of around 32, larger values are
     * generally better - they allow a larger maximum frequency total
     * (maximumTotal), and they reduce the approximation error inherent in adapting
     * fractions to integers; both effects reduce the data encoding loss and
     * asymptotically approach the efficiency of arithmetic coding using exact
     * fractions.</li>
     * <li>But for state sizes greater than the midpoint, because intermediate
     * computations are limited to the long integer type's 63-bit unsigned
     * precision, larger state sizes will decrease the maximum frequency total,
     * which might constrain the user-supplied probability model.</li>
     * <li>Therefore numStateBits=32 is recommended as the most versatile setting
     * because it maximizes maximumTotal (which ends up being slightly over
     * 2^30).</li>
     * <li>Note that numStateBits=62 is legal but useless because it implies
     * maximumTotal=1, which means a frequency table can only support one symbol
     * with non-zero frequency.</li>
     * </ul>
     */
    protected final int numStateBits;

    /**
     * Maximum range (high+1-low) during coding (trivial), which is 2^numStateBits
     * and half and quarter of that
     */
    protected final long fullRange, halfRange, quarterRange;

    /**
     * Minimum range (high+1-low) during coding (non-trivial), which is 0010...010.
     */
    protected final long minimumRange;

    /** Maximum allowed total from a frequency table at all times during coding. */
    protected final long maximumTotal;

    /** Bit mask of numStateBits ones, which is 0111...111. */
    protected final long stateMask;

    /**
     * Low end of this arithmetic coder's current range. Conceptually has an
     * infinite number of trailing 0s.
     */
    protected long low;

    /**
     * High end of this arithmetic coder's current range. Conceptually has an
     * infinite number of trailing 1s.
     */
    protected long high;

    /**
     * Constructs an arithmetic coder, which initializes the code range.
     */
    public ArithmeticCoderBase() {
        numStateBits = 32; // range [1, 62], 32 is best for maximumTotal
        fullRange = 1L << numStateBits;
        halfRange = fullRange >>> 1; // Non-zero
        quarterRange = halfRange >>> 1; // Can be zero
        minimumRange = quarterRange + 2; // At least 2
        maximumTotal = Math.min(Long.MAX_VALUE / fullRange, minimumRange);
        stateMask = fullRange - 1;

        low = 0;
        high = stateMask;
    }

    /**
     * Updates the code range (low and high) of this arithmetic coder as a result of
     * processing the specified symbol with the specified frequency table.
     * <p>
     * Invariants that are true before and after encoding/decoding each symbol
     * (letting fullRange = 2<sup>numStateBits</sup>):
     * </p>
     * <ul>
     * <li>0 &le; low &le; code &le; high &lt; fullRange. ('code' exists only in the
     * decoder.) Therefore these variables are unsigned integers of numStateBits
     * bits.</li>
     * <li>low &lt; 1/2 &times; fullRange &le; high. In other words, they are in
     * different halves of the full range.</li>
     * <li>(low &lt; 1/4 &times; fullRange) || (high &ge; 3/4 &times; fullRange). In
     * other words, they are not both in the middle two quarters.</li>
     * <li>Let range = high &minus; low + 1, then fullRange/4 &lt; minimumRange &le;
     * range &le; fullRange. These invariants for 'range' essentially dictate the
     * maximum total that the incoming frequency table can have, such that
     * intermediate calculations don't overflow.</li>
     * </ul>
     * 
     * @param stats  the (integrated) frequency table to use
     * @param symbol the symbol that was processed
     * @throws IllegalArgumentException if the symbol has zero frequency or the
     *                                  frequency table's total is too large
     */
    protected void update(long[] stats, int symbol) throws IOException {
        // State check
        if (low >= high || (low & stateMask) != low || (high & stateMask) != high)
            throw new AssertionError("Low or high out of range");
        long range = high - low + 1;
        if (!(minimumRange <= range && range <= fullRange))
            throw new AssertionError("Range out of range");

        // Frequency table values check
        long total = stats[stats.length - 1];
        long symLow = symbol == 0 ? 0L : stats[symbol - 1];
        long symHigh = stats[symbol];
        if (symLow == symHigh)
            throw new IllegalArgumentException("Symbol has zero frequency");
        if (total > maximumTotal)
            throw new IllegalArgumentException("Cannot code symbol because total is too large");

        // Update range
        high = low + symHigh * range / total - 1;
        low = low + symLow * range / total;

        // While low and high have the same top bit value, shift them out
        while (((low ^ high) & halfRange) == 0) {
            shift();
            low = ((low << 1) & stateMask);
            high = ((high << 1) & stateMask) | 1;
        }
        // Now low's top bit must be 0 and high's top bit must be 1

        // While low's top two bits are 01 and high's are 10, delete the second-highest
        // bit of both
        while ((low & ~high & quarterRange) != 0) {
            underflow();
            low = (low << 1) ^ halfRange;
            high = ((high ^ halfRange) << 1) | halfRange | 1;
        }
    }

    public void createStatsFromFrequencies(long[] values) {
        scaleDownFrequencies(values);
        long sum = 0L;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            values[i] = sum;
        }
    }

    private void scaleDownFrequencies(long[] values) {
        for(;;) {
            // just count
            long total = 0L;
            for (long v : values) {
                total += v;
            }
            if ( total <= maximumTotal ) {
                return;
            }
            for (int i = 0; i < values.length; i++) {
                values[i] = (values[i]+1) >>> 1;
            }
         }
    }

    /**
     * Called to handle the situation when the top bit of {@code low} and
     * {@code high} are equal.
     * 
     * @throws IOException if an I/O exception occurred
     */
    protected abstract void shift() throws IOException;

    /**
     * Called to handle the situation when low=01(...) and high=10(...).
     * 
     * @throws IOException if an I/O exception occurred
     */
    protected abstract void underflow() throws IOException;

}
