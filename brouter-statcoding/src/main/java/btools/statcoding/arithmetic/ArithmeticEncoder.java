package btools.statcoding.arithmetic;

import java.io.IOException;

import btools.statcoding.BitOutputStream;

/**
 * Encodes symbols and writes to an arithmetic-coded bit stream.
 * <br><br>
 * This code is mostly taken from:
 * https://github.com/nayuki/Reference-arithmetic-coding
 * <br><br>
 * Please note that this needs exclusive access to the underlying BitOutputStream
 * after the first symbol is encoded. On finish(), the encoder flushes
 * 32 extra bits in order to allow continues use of the underlying
 * bitstream. So the minimum size of an AC-section in a combined
 * bit-stream shpuld be much larger then 32 bits.
 *
 * @see ArithmeticDecoder
 */
public final class ArithmeticEncoder extends ArithmeticCoderBase {

    // The underlying bit output stream (not null).
    private final BitOutputStream output;

    // Number of saved underflow bits.
    private long numUnderflow;

    private boolean symbolsCoded;

    /**
     * Constructs an arithmetic encoder based on the specified bit stream.
     * 
     * @param out     the bit output stream to write to
     */
    public ArithmeticEncoder(BitOutputStream out) {
        output = out;
    }

    public BitOutputStream getOutputStream() {
        return output;
    }

    /**
     * Encodes the specified symbol based on the specified frequency table. Also
     * updates this arithmetic coder's state and may write out some bits.
     * 
     * @param stats  the (integrated) frequency table to use
     * @param symbol the symbol to encode
     * @throws IllegalArgumentException if the symbol has zero frequency or the
     *                                  frequency table's total is too large
     * @throws IOException              if an I/O exception occurred
     */
    public void write(long[] stats, int symbol) throws IOException {
        update(stats, symbol);
        symbolsCoded = true;
    }

    /**
     * Terminates the arithmetic coding by flushing any buffered bits, so that the
     * output can be decoded properly. It is important that this method must be
     * called at the end of each encoding process.
     * <p>
     * Note that this method merely writes data to the underlying output stream but
     * does not close it.
     * </p>
     * 
     * @throws IOException if an I/O exception occurred
     */
    public void finish() throws IOException {
        if ( symbolsCoded ) {
            writeBitAndFollowBits( true );
            output.encodeBits( numStateBits-1, 0L );
        }
    }

    protected void shift() throws IOException {
        // write the current top-bit followed by numUnderflow inverse bits
        // to the underlying output stream
        writeBitAndFollowBits( (low & halfRange) != 0L );
    }

    private void writeBitAndFollowBits(boolean bit) throws IOException {
        output.encodeBit( bit );
        while( numUnderflow > 0L ) {
            long bitsNow = Math.min( numUnderflow, 8 );
            output.encodeBits( (int)bitsNow, bit ? 0x00 : 0xff );
            numUnderflow -= bitsNow;
        }
    }

    protected void underflow() {
        numUnderflow++;
    }

}
