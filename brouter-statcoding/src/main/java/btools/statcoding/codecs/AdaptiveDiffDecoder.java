package btools.statcoding.codecs;

import btools.statcoding.BitInputStream;

import java.io.IOException;


/**
 * A AdaptiveDiffEncoder is used to decode series of numbers.
 */
public class AdaptiveDiffDecoder {

    private final BitInputStream bis;
    private int noisyBits = 8;
    private long lastValue;

    /**
     * Construct a AdaptiveDiffEncoder<br>
     * 
     * @param targetIn    the underlying bit-stream
     */
    public AdaptiveDiffDecoder(BitInputStream targetIn) {
        bis = targetIn;
    }

    public long decode() throws IOException {

        // following lines copied from BitInputStream.decodeSignedVarBits(int)
        boolean isCentral = bis.decodeBit();
        long lv = isCentral ? 0L : bis.decodeUnsignedVarBits() + 1L;
        long noisyWord = bis.decodeBits( noisyBits );
        if ( !isCentral && bis.decodeBit() ) {
            lv = -lv;
        }
        long shiftedValue = (lv << noisyBits) | noisyWord;
        long diff = noisyBits == 0 ? shiftedValue : shiftedValue - (1L<<(noisyBits-1));
        
        lastValue += diff;

        noisyBits = isCentral ? ( noisyBits > 0 ? noisyBits-1 : 0 ) : ( noisyBits < 63 ? noisyBits+1 : 63 );
        return lastValue;
    }
}
