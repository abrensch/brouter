package btools.statcoding.codecs;

import btools.statcoding.BitOutputStream;

import java.io.IOException;

/**
 * Encoder for huffman-encoding objects. <br>
 * <br>
 * It detects identical objects and sorts them into a huffman-tree according to
 * their frequencies. <br>
 * <br>
 * Adapted for 2-pass encoding (pass 1: statistic collection, pass 2: encoding).
 */
public class Number2PassEncoder {

    protected BitOutputStream bos;

    private int pass;

    private final long[] signedBitsForPrefix = new long[64];
    private final long[] unsignedBitsForPrefix = new long[64];

    private int noisyBitsValid;

    private boolean isPositive = true;

    /**
     * Encode an object. In pass 1 this gathers statistics, in pass 2 this actually
     * writes the huffman code to the underlying output stream.
     *
     * @param value the number to encode
     */
    public void encode(long value) throws IOException {
        if (pass == 2) {
            if ( isPositive ) {
              bos.encodeUnsignedVarBits(value, noisyBitsValid);
            } else {
              bos.encodeSignedVarBits(value, noisyBitsValid);
            }
        } else {
          for( int prefix = 0; prefix<64; prefix++ ) {
            signedBitsForPrefix[prefix] += getSignedBits( value, prefix );
            if ( isPositive && value >= 0L ) {
              unsignedBitsForPrefix[prefix] += getUnsignedBits(value, prefix);
            } else {
              isPositive = false;
            }
          }
        }
    }

    private int getSignedBits( long value, int noisyBits ) {
      long shiftedValue = noisyBits == 0 ? value : value + (1L<<(noisyBits-1));
      long lv = shiftedValue >> noisyBits;
      return lv == 0L ? noisyBits + 1 : noisyBits + 2 + golombBits( (lv < 0L ? -lv : lv) - 1L );
    }

    private int getUnsignedBits( long value, int noisyBits ) {
      return noisyBits + golombBits( value >> noisyBits );
    }

    private int golombBits( long value ) {
      int nBits = 0;
      while (nBits < 63 && value >= 1L << nBits ) {
        value -= 1L << nBits++;
      }
      return (nBits << 1) +1;
    }

    /**
     * Initialize the encoder. Must be called at the beginning of each of the 2
     * encoding passes. For pass 1 this only increments the pass-counter (and the
     * given bit stream is allowed to be null). For pass 2 this encodes the tree and
     * registers the bit stream for subsequent data encoding. Calling init more then
     * twice can be used to delegate data encoding to other bit streams.
     *
     * @param bos the bit stream to use for encoding tree and data
     */
    public void init(BitOutputStream bos) throws IOException {
        this.bos = bos;
        pass = Math.min(pass + 1, 2);
        if (pass == 2) { // encode the best noisyBits in pass 2
          long[] bits = isPositive ? unsignedBitsForPrefix : signedBitsForPrefix;
          long min = Long.MAX_VALUE;
          for( int prefix = 0; prefix < 64; prefix++ ) {
              if ( bits[prefix] < min ) {
                noisyBitsValid = prefix;
                min = bits[prefix];
              }
          }
          bos.encodeBit( isPositive );
          bos.encodeUnsignedVarBits(noisyBitsValid);
        }
    }
}
