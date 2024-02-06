package btools.statcoding.codecs;

import btools.statcoding.BitInputStream;
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
public class Number2PassDecoder {

    protected BitInputStream bis;

    private int noisyBits;

    private boolean isPositive;

    /**
     * decode a number
     */
    public long decode() throws IOException {
      return isPositive ? bis.decodeUnsignedVarBits(noisyBits) : bis.decodeSignedVarBits(noisyBits);
    }

    /**
     * Initialize the encoder. Must be called at the beginning of each of the 2
     * encoding passes. For pass 1 this only increments the pass-counter (and the
     * given bit stream is allowed to be null). For pass 2 this encodes the
     * encoding mode and registers the bit stream for subsequent data encoding. Calling init more then
     * twice can be used to delegate data encoding to other bit streams.
     *
     * @param bis the bit stream to use for decoding
     */
    public void init(BitInputStream bis) throws IOException {
      if (this.bis != null) {
        this.bis = bis;
        return;
      }
      this.bis = bis;
      isPositive = bis.decodeBit();
      noisyBits = (int)bis.decodeUnsignedVarBits();
    }
}
