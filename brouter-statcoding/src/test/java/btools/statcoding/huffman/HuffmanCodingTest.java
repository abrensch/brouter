package btools.statcoding.huffman;

import java.io.*;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

import junit.framework.TestCase;

public class HuffmanCodingTest extends TestCase {

    private static final long[] testLongs = new long[] { 0L, 0L, 1L, 1L, 1L, 1L, 1L, 2L, 2L, 3L };

    public void testHuffmanCoding() throws IOException {

        // explicitly test also the "no symbol" case (nsymbols=0) and "only 1 Symbol"
        for (int nsymbols = 0; nsymbols < testLongs.length; nsymbols++) {
            testHuffmanCoding(nsymbols, 0 );
            testHuffmanCoding(nsymbols, 8 );
            testHuffmanCoding(nsymbols, 20 );
        }
    }

    private void testHuffmanCoding(int nsymbols, int lookupBits ) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (BitOutputStream bos = new BitOutputStream(baos)) {

            HuffmanEncoder<Long> enc = new HuffmanEncoder<Long>() {
                @Override
                protected void encodeObjectToStream(Long lv) throws IOException {
                    bos.encodeUnsignedVarBits(lv, 0);
                }
            };

            for (int pass = 1; pass <= 2; pass++) { // 2-pass encoding!
                enc.init(bos);
                long bits0 = bos.getBitPosition();
                for (int i = 0; i < nsymbols; i++) {
                    enc.encodeObject(testLongs[i]);
                }
                long bits1 = bos.getBitPosition();
                if ( pass == 2 ) {
                    assertTrue( enc.getStats().contains ( "" + (bits1-bits0) ) );
                }
            }
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (BitInputStream bis = new BitInputStream(bais)) {

            HuffmanDecoder<Long> dec = new HuffmanDecoder<Long>() {
                @Override
                protected Long decodeObjectFromStream() throws IOException {
                    return bis.decodeUnsignedVarBits(0);
                }
            };
            dec.init(bis, lookupBits);

            for (int i = 0; i < nsymbols; i++) {
                assertTrue(dec.decodeObject() == testLongs[i]);
            }
        }
    }
}
