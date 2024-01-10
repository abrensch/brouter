package btools.statcoding.arithmetic;

import java.io.*;
import java.util.*;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

import junit.framework.TestCase;

public class ArithmeticCodingTest extends TestCase {

    public void testArithmeticCoding() throws IOException {

        for( int i=0; i<1000; i++ ) {
            int symbolRange = 1 + i;
            int nsymbols = i;
            testArithmeticCoding(symbolRange, nsymbols);
        }
    }

    private void testArithmeticCoding(int symbolRange, int nsymbols) throws IOException {

        long seed = new Random().nextLong();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        long[] freqs = new long[symbolRange];
        Random rnd = new Random(seed);
        for (int i = 0; i < nsymbols; i++) {
            int nextSymbol = rnd.nextInt(symbolRange);
            freqs[nextSymbol]++;
        }

        try (BitOutputStream bos = new BitOutputStream(baos)) {
            ArithmeticEncoder enc = new ArithmeticEncoder(bos);
            enc.createStatsFromFrequencies(freqs);
            rnd = new Random(seed);
            for (int i = 0; i < nsymbols; i++) {
                int nextSymbol = rnd.nextInt(symbolRange);
                enc.write(freqs, nextSymbol);
            }
            enc.finish();
            bos.encodeUnsignedVarBits(100,0);
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (BitInputStream bis = new BitInputStream(bais)) {

            ArithmeticDecoder dec = new ArithmeticDecoder(bis);
            rnd = new Random(seed);

            for (int i = 0; i < nsymbols; i++) {
                int expectedSymbol = rnd.nextInt(symbolRange);
                int decodedSymbol = dec.read(freqs);
                assertEquals(expectedSymbol, decodedSymbol);
            }
            assertEquals(100, bis.decodeUnsignedVarBits(0));
        }
    }
}
