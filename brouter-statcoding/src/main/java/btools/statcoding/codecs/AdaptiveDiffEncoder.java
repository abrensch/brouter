package btools.statcoding.codecs;

import btools.statcoding.BitOutputStream;

import java.io.IOException;


/**
 * A AdaptiveDiffEncoder is used to encode series of numbers.
 */
public class AdaptiveDiffEncoder {

    private final BitOutputStream targetOut;
    private int noisyBits = 8;
    private long lastValue;

    /**
     * Construct a AdaptiveDiffEncoder<br>
     * 
     * @param targetOut    the underlying bit-stream
     */
    public AdaptiveDiffEncoder(BitOutputStream targetOut) {
        this.targetOut = targetOut;
    }

    public void encode( long value ) throws IOException {
        long diff = value-lastValue;
        lastValue = value;
        boolean isCenter = targetOut.encodeSignedVarBits( diff, noisyBits );
        noisyBits = isCenter ? ( noisyBits > 0 ? noisyBits-1 : 0 ) : ( noisyBits < 63 ? noisyBits+1 : 63 );
    }
}
