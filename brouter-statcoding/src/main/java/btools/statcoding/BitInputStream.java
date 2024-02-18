package btools.statcoding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * BitInputStream is a replacement for java.io.DataInputStream extending it by
 * bitwise operations suitable for statistical decoding. <br>
 * It automatically re-aligns to byte-alignment as soon as any of the methods of
 * InputStream or DataOutput or its own method 'decodeVarBytes' is called. <br>
 * Please note that while doing bitwise operations, BitInputStream buffers up to
 * 8 bytes from the underlying stream and has a somewhat sloppy EOF detection,
 * so please do fully re-align (see
 * {@link BitOutputStream#writeSyncBlock(long)}) after bitwise operations for
 * compliant EOF behavior.
 */
public class BitInputStream extends InputStream implements DataInput {

    private int bits; // bits left in buffer
    private int eofBits; // dummy bits read after eof
    private long b; // buffer word

    protected InputStream in;
    private DataInputStream dis; // created lazily if needed
    private long[] lastChannelValues; // used for readDiffed, created lazily

    /**
     * Construct a BitInputStream for the underlying InputStream.
     *
     * @param is the underlying stream to read from
     */
    public BitInputStream(InputStream is) {
        in = is;
    }

    /**
     * Construct a BitInputStream for the given Byte-Array.
     *
     * @param ab the underlying byte array to read from
     */
    public BitInputStream(byte[] ab) throws IOException {
        in = new ByteArrayInputStream(ab);
    }

    private void fillBuffer() throws IOException {
        while (bits <= 56) {
            int nextByte = in.read();

            if (nextByte != -1) {
                b |= (nextByte & 0xffL) << (56-bits);
            } else {
                eofBits += 8;
                if (eofBits >= 256) {
                    throw new RuntimeException("end of stream !");
                }
            }
            bits += 8;
        }
    }

    /**
     * A BitInputStream spits out up to 256 dummy-0-bits after EOF. This method
     * tells if we are still reading real data bits.
     *
     * @return true if the next bit is stll real.
     */
    public boolean hasMoreRealBits() throws IOException {
        fillBuffer();
        return eofBits == 0 || bits > eofBits;
    }

    /**
     * This actually just calls readLong(), but is a method on it's for
     * documentation: if the underlying input stream is still used by other
     * consumers after this BitInputStream is discarded or paused, we need to make
     * sure that it's internal 64-bit buffer is empty. Any block of >=8 bytes of
     * byte-aligned data will do, just make sure that the encoder and the decoder
     * agree on a common structure. <br>
     * <br>
     * See also {@link BitOutputStream#writeSyncBlock( long )} <br>
     *
     * @return the sync block as a long value
     */
    public long readSyncBlock() throws IOException {
        return readLong();
    }

    // ****************************************
    // **** METHODS of java.io.InputStream ****
    // ****************************************

    @Override
    public int read() throws IOException {

        if (bits > 0) {
            reAlign();
            if (bits > 7) { // can read byte from bit-buffer
                long value = b >>> 56;
                b <<= 8;
                bits -= 8;
                return (int) value;
            }
        }
        return in.read();
    }

    private void reAlign() throws IOException {
        while ((bits & 7) > 0) { // any padding bits left?
            if (b < 0L) {
                throw new IOException("re-alignment-failure: found non-zero padding bit");
            }
            b <<= 1;
            bits--;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        reAlign();
        if (len == 0) {
            return 0;
        }
        int lenFromBuffer = 0;
        while (bits > 0 && len > 0) {
            b[off++] = (byte) read();
            len--;
            lenFromBuffer++;
        }
        if (lenFromBuffer > 0) {
            if (len == 0 || available() == 0) {
                return lenFromBuffer;
            }
            int result = in.read(b, off, len);
            return result == -1 ? lenFromBuffer : lenFromBuffer + result;
        }
        return in.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return Math.max( 0, (bits >> 3) + in.available() - (eofBits >> 3));
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // **************************************
    // **** METHODS of java.io.DataInput ****
    // **************************************

    // delegate Methods of DataInput to an instance of
    // DataInputStream created lazily
    private DataInputStream getDis() {
        if (dis == null) {
            dis = new DataInputStream(this);
        }
        return dis;
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        getDis().readFully(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        getDis().readFully(b, off, len);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return getDis().skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return getDis().readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return getDis().readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return getDis().readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return getDis().readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return getDis().readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return getDis().readChar();
    }

    @Override
    public int readInt() throws IOException {
        return getDis().readInt();
    }

    @Override
    public long readLong() throws IOException {
        return getDis().readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return getDis().readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return getDis().readDouble();
    }

    @Override
    public String readLine() throws IOException {
        return getDis().readLine();
    }

    @Override
    public String readUTF() throws IOException {
        return getDis().readUTF();
    }

    // ***********************************************
    // **** Byte-aligned Variable Length Decoding ****
    // ***********************************************

    /**
     * Decoding twin to {@link BitOutputStream#encodeVarBytes( long )}
     *
     * @return the decoded long value
     */
    public final long decodeVarBytes() throws IOException {
        long v = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            int nextByte = read();
            if (nextByte == -1) {
                throw new EOFException("unexpected EOF in decodeVarBytes");
            }
            v |= (nextByte & 0x7fL) << shift;
            if ((nextByte & 0x80) == 0) {
                break;
            }
        }
        return restoreSignBit(v);
    }

    /**
     * Decoding twin to {@link BitOutputStream#writeDiffed( long, int )}
     *
     * @param channel the channel number to use
     *
     * @return the decoded long value
     */
    public long readDiffed(int channel) throws IOException {
        if ( lastChannelValues == null ) {
            lastChannelValues = new long[channel+1];
        } else if ( channel >= lastChannelValues.length ) {
            lastChannelValues = Arrays.copyOf( lastChannelValues, channel+1 );
        }
        long d = decodeVarBytes();
        long value = lastChannelValues[channel] + d;
        lastChannelValues[channel] = value;
        return value;
    }

    private long restoreSignBit(long value) {
        return (value & 1L) == 0L ? value >>> 1 : -(value >>> 1) - 1L;
    }

    /**
     * Decoding twin to {@link BitOutputStream#encodeSizedByteArray( byte[] )}
     *
     * @return the the decoded byte array
     */
    public final byte[] decodeSizedByteArray() throws IOException {
        long size = decodeVarBytes();
        if ( size == -1l ) {
            return null;
        }
        if ( size < 0L || size > 0x7fffffffL ) {
          throw new RuntimeException( "invalid byte-array-size: " + size );
        }
        byte[] ab = new byte[(int)size];
        readFully( ab );
        return ab;
    }

    // ***************************************
    // **** Bitwise Fixed Length Encoding ****
    // ***************************************

    /**
     * Decode a single bit.
     *
     * @return true/false for 1/0
     */
    public final boolean decodeBit() throws IOException {
        fillBuffer();
        boolean value = b < 0L;
        b <<= 1;
        bits--;
        return value;
    }

    /**
     * Decode a given number of bits.
     *
     * @param count the number of bit to decode
     * @return the decoded value
     */
    public final long decodeBits(int count) throws IOException {
        if (count == 0) {
            return 0L;
        }
        fillBuffer();
        if (count > bits) {
            return (decodeBits(count-32) << 32) | decodeBits( 32 ); // buffer too small, split
        }
        long value = b >>> (64 - count);
        b <<= count;
        bits -= count;
        return value;
    }

    // ******************************************
    // **** Bitwise Variable Length Decoding ****
    // ******************************************


    /**
     * Decoding twin to
     * {@link BitOutputStream#encodeUnsignedVarBits( long, int )}<br>
     * <br>
     * This is known as "Order k Exponential Golomb Coding" (with k=noisybits)<br>
     * <br>
     * Please note that {@code noisyBits} must match the value used for encoding.
     *
     * @param noisyBits the number of lower bits considered noisy
     * @return the decoded value
     * @see <a href="https://en.wikipedia.org/wiki/Exponential-Golomb_coding">Exponential-Golomb_coding</a>
     */
    public final long decodeUnsignedVarBits(int noisyBits) throws IOException {
        checkNoisyRange( noisyBits );
        return (decodeUnsignedVarBits() << noisyBits) | decodeBits(noisyBits);
    }

    public final long decodeUnsignedVarBits() throws IOException {
        fillBuffer();
        int v = (int) (b >>> 56);
        int codeLength = varBitLengthsArray[v];
        if (codeLength < 8) {
          b <<= codeLength;
          bits -= codeLength;
          return varBitValuesArray[v]; // short code fully decoded by lookup
        }
        int nBits = 0;
        while (codeLength == 17) {
          b <<= 8;
          bits -= 8;
          if ( (nBits +=8 ) == 64 ) {
            throw new IllegalArgumentException( "unexpected length prefix, >= 64" );
          }
          fillBuffer();
          codeLength = varBitLengthsArray[(int) (b >>> 56)];
        }
        int remainingBits = codeLength >> 1;
        nBits += remainingBits;
        int consumed = remainingBits+1;
        b <<= consumed;
        bits -= consumed;
        long range = nBits > 0 ? 0xffffffffffffffffL >>> (64 - nBits): 0L;
        return range + decodeBits(nBits);
    }

    public final long decodeUnsignedVarBitsSlow() throws IOException {
        int nBits = 0;
        while ( !decodeBit() ) {
          nBits++;
        }
        long range = nBits > 0 ? 0xffffffffffffffffL >>> (64 - nBits): 0L;
        return range + decodeBits(nBits);
    }

    /**
     * Decoding twin to {@link BitOutputStream#encodeSignedVarBits( long, int )}<br>
     * <br>
     * For noisybits=0 this is known as "Signed Exponential Golomb Coding"<br>
     * <br>
     * Please note that {@code noisyBits} must match the value used for encoding.
     *
     * @param noisyBits the number of lower bits considered noisy
     * @return the decoded value
     * @see <a href="https://en.wikipedia.org/wiki/Exponential-Golomb_coding">Exponential-Golomb_coding</a>
     */
    public final long decodeSignedVarBits(int noisyBits) throws IOException {
        checkNoisyRange( noisyBits );
        boolean isCentral = decodeBit();
        long lv = isCentral ? 0L : decodeUnsignedVarBits() + 1L;
        long noisyWord = decodeBits( noisyBits );
        if ( !isCentral && decodeBit() ) {
            lv = -lv;
        }
        long shiftedValue = (lv << noisyBits) | noisyWord;
        return noisyBits == 0 ? shiftedValue : shiftedValue - (1L<<(noisyBits-1));
    }

    private static void checkNoisyRange( int noisyBits ) {
        if ( noisyBits < 0 || noisyBits > 63 ) {
            throw new IllegalArgumentException( "noisyBits out of rangs (0..63): " + noisyBits );
        }
    }

    /**
     * Decoding twin to {@link BitOutputStream#encodeBounded( long, long )}<br>
     * <br>
     *
     * Please note that {@code max} must match the value used for encoding.
     *
     * @param max the number of lower bits considered noisy
     * @return the decoded value
     */
    public final int decodeBoundedInt(int max) throws IOException {

        // small number shortcut using lookup-tables
        if (max<16) {
            fillBuffer();
            int idx = max << 4 | (int)(b >>> 60);
            int count = boundLengthsArrays[idx];
            b <<= count;
            bits -= count;
            return boundValuesArrays[idx];
        }
        return (int)decodeBounded(max);
    }

    public final long decodeBounded(long max) throws IOException {

        long m = max;
        int n = 0;
        while ((m >>>= 1) != 0L) {
          n++;
        }
        long value = decodeBits( n );

        // read the highest bit only if a 1-bit would yield a value <= max
        long im = 1L << n; // integer mask
        if ((value | im) <= max ) {
            if ( decodeBit() ) {
              value |= im;
            }
        }
        return value;
    }

    private static final int[] boundLengthsArrays = new int[256];
    private static final int[] boundValuesArrays = new int[256];
    static {
        BitInputStream bis = new BitInputStream(InputStream.nullInputStream());
        for( int max=0; max<16; max++ ) {
            for(int v = 0; v<16; v++) {
                int idx = max << 4 | v;
                bis.b = ((long)v) << 60;
                bis.bits = 64;
                bis.eofBits = 0;
                try {
                  boundValuesArrays[idx] = (int)bis.decodeBounded(max);
                } catch( IOException ioe ) {
                    throw new RuntimeException( ioe );
                }
              boundLengthsArrays[idx] = 64+bis.eofBits-bis.bits;
            }
        }
    }

  private static final int[] varBitLengthsArray = new int[256];
  private static final int[] varBitValuesArray = new int[256];
  static {
    BitInputStream bis = new BitInputStream(InputStream.nullInputStream());
    for(int v = 0; v<256; v++) {
        bis.b = ((long)(2*v+1)) << 55;
        bis.bits = 64;
        bis.eofBits = 0;
        try {
          varBitValuesArray[v] = (int)bis.decodeUnsignedVarBitsSlow();
        } catch( IOException ioe ) {
          throw new RuntimeException( ioe );
        }
      varBitLengthsArray[v] = 64+bis.eofBits-bis.bits;
    }
  }

    /**
     * Decoding twin to {@link BitOutputStream#encodeString( String )}
     *
     * @return the decoded String (may be null)
     */
    public final String decodeString() throws IOException {

        long classifier = decodeUnsignedVarBits( 1 );
        if (classifier == 0L) {
            return null;
        }
        if (classifier == 1L) {
            return "";
        }
        int type = (int)(classifier-2L);
        int n = 1 + (int)decodeUnsignedVarBits( 3 );
        if ( type < 3 ) {
            // encode a limited charset ( numeric, numeric+, ascii )
            long min = charRangeLow[type];
            long range = charRangeHigh[type]-min-1;
            char[] ac = new char[n];
            for( int j=0; j<n; j++ ) {
                long c = decodeBounded( range ) + min;
                ac[j] = (char)c;
            }
            return new String( ac );
        }
        if ( type == 3 ) {
        	  // decode UTF-8
            byte[] ab = new byte[n];
            readFully(ab);
            return new String(ab, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException( "unknown string classifier: " + classifier );
    }

    // known character ranges: numeric, numeric+, ascii
    private static int[] charRangeLow = { 0x30, 0x2c, 0x20 };
    private static int[] charRangeHigh = { 0x3a, 0x3a, 0x80 };

    /**
     * Decoding twin to {@link BitOutputStream#encodeUniqueSortedArray( long[] )}
     *
     * @return the decoded array of sorted, positive, unique longs
     */
    public long[] decodeUniqueSortedArray() throws IOException {
        int size = (int) decodeUnsignedVarBits(0);
        long[] values = new long[size];
        decodeUniqueSortedArray(values, 0, size);
        return values;
    }

    /**
     * Decoding twin to
     * {@link BitOutputStream#encodeUniqueSortedArray( long[], int, int )} <br>
     * See also {@link #decodeUniqueSortedArray()}
     *
     * @param values the array to decode into
     * @param offset position in this array where to start
     * @param size   number of values to decode
     */
    public void decodeUniqueSortedArray(long[] values, int offset, int size) throws IOException {
        if (size > 0) {
            int nBits = (int) decodeUnsignedVarBits(8);
            decodeUniqueSortedArray(values, offset, size, nBits, 0L);
        }
    }

    /**
     * Decoding twin to
     * {@link BitOutputStream#encodeUniqueSortedArray( long[], int, int, int, long )}
     * <br>
     * See also {@link #decodeUniqueSortedArray( long[], int, int )}
     *
     * @param values     the array to encode
     * @param offset     position in this array where to start
     * @param subSize    number of values to encode
     * @param nextBitPos bit-position of the most significant bit
     * @param value      should be 0 at recursion start
     */
    public void decodeUniqueSortedArray(long[] values, int offset, int subSize, int nextBitPos, long value)
            throws IOException {
        if (subSize == 1) // last-choice shortcut
        {
            values[offset] = value | decodeBits(nextBitPos + 1);
            return;
        }
        if (nextBitPos < 0L) { // cannot happen for unique array
            throw new RuntimeException("unique violation");
        }

        long nextBit = 1L << nextBitPos;
        int size1;
        if (subSize >= nextBit) {
            int min = subSize - (int)nextBit;
            size1 = decodeBoundedInt((int)nextBit - min) + min;
        } else {
            size1 = decodeBoundedInt(subSize);
        }
        int size2 = subSize - size1;

        if (size1 > 0) {
            decodeUniqueSortedArray(values, offset, size1, nextBitPos - 1, value);
        }
        if (size2 > 0) {
            decodeUniqueSortedArray(values, offset + size1, size2, nextBitPos - 1, value | nextBit);
        }
    }

    /**
     * Decode some bits according to the given lengthArray (which is expected to be
     * 2^n in size, with n <= 32) <br>
     * This is very special logic for speeding up huffman decoding based on a lookup
     * table. <br>
     * But could be used for speeding up other var-length codes as well.
     *
     * @param lengthArray an array telling how much bits to consume for the observed
     *                    bit-pattern
     *
     * @return an index to the lookup array
     */
    public final int decodeLookupIndex(int[] lengthArray, int m64lookupBits) throws IOException {
        fillBuffer();
        int v = (int) (b >>> m64lookupBits);
        int count = lengthArray[v];
        b <<= count;
        bits -= count;
        return v;
    }
}
