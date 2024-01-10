package btools.statcoding;

import java.io.*;
import java.util.*;

import junit.framework.TestCase;

public class BitStreamsTest extends TestCase {

    private static final long[] testLongs = new long[] { 0L, 1L, 63738377475675L, Long.MAX_VALUE };

    public void testBitStreams() throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        long bitLength;
        try (BitOutputStream bos = new BitOutputStream(baos)) {
            bos.encodeBit(true);
            bos.encodeBit(false);
            bos.encodeBits(9, 3L);
            bos.encodeBounded(111L, 7L);
            for (int noisyBits =0; noisyBits < 64; noisyBits++) {
                for (long l : testLongs) {
                    bos.encodeUnsignedVarBits(l, noisyBits);
                    bos.encodeSignedVarBits(l, noisyBits);
                    bos.encodeSignedVarBits(-l, noisyBits);
                }
            }
            bos.encodeSignedVarBits(Long.MIN_VALUE, 0);
            bitLength = bos.getBitPosition();
        }

        // check content
        byte[] ab = baos.toByteArray();
        try (BitInputStream bis = new BitInputStream(ab)) {
            assertTrue(bis.decodeBit());
            assertFalse(bis.decodeBit());
            assertEquals(ab.length - 1, bis.available());
            assertEquals(3L, bis.decodeBits(9));
            assertEquals(7L, bis.decodeBounded(111L));
            for (int noisyBits =0; noisyBits < 64; noisyBits++) {
                for (long l : testLongs) {
                    assertEquals(l, bis.decodeUnsignedVarBits(noisyBits));
                    assertEquals(l, bis.decodeSignedVarBits(noisyBits));
                    assertEquals(-l, bis.decodeSignedVarBits(noisyBits));
                }
            }
            assertEquals(bis.decodeSignedVarBits(0), Long.MIN_VALUE);
        }

        // check stream size
        // (note: hasMoreRealBits overcounts to 8 bit boundary)
        try (BitInputStream bis = new BitInputStream(ab)) {
            long bitLength2 = 0L;
            while (bis.hasMoreRealBits()) {
                bis.decodeBit();
                bitLength2++;
            }
            assertEquals( (bitLength+7) >> 3, bitLength2 >> 3 );
        }

        // check end of stream
        try (BitInputStream bis = new BitInputStream(ab)) {
            for (;;) {
                bis.decodeBit();
            }
        } catch (Exception e) {
            assertEquals(e.getMessage(), "end of stream !");
        }

    }

    public void testVarBytes() throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] ab = new byte[] { 7,77, -33 };

        try (BitOutputStream bos = new BitOutputStream(baos)) {
            for (long l : testLongs) {
                bos.encodeVarBytes(l);
            }
            bos.encodeVarBytes(Long.MIN_VALUE);
            // test re-alignment
            bos.encodeSignedVarBits(1523L, 3);
            bos.encodeVarBytes(4711L);
            bos.encodeSizedByteArray(null);
            bos.encodeSizedByteArray(ab);
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (BitInputStream bis = new BitInputStream(bais)) {

            for (long l : testLongs) {
                assertEquals(bis.decodeVarBytes(), l);
            }
            assertEquals(bis.decodeVarBytes(), Long.MIN_VALUE);
            assertEquals(bis.decodeSignedVarBits(3), 1523L);
            assertEquals(bis.decodeVarBytes(), 4711L);
            assertNull( bis.decodeSizedByteArray() );
            assertTrue(Arrays.equals(ab, bis.decodeSizedByteArray()));
        }
    }

    public void testEncodeBounded() throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BitOutputStream bos = new BitOutputStream(baos)) {
            for( long max = 0L; max < 1000L; max++ ) {
                for( long value = 0L; value <= max ; value++ ) {
                    bos.encodeBounded( max, value );
                }
            }
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (BitInputStream bis = new BitInputStream(bais)) {
            for( long max = 0L; max < 1000L; max++ ) {
                for( long value = 0L; value <= max ; value++ ) {
                   assertEquals(value, bis.decodeBounded(max));
                }
            }
        }
    }

    public void testReAlignment() throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (BitOutputStream bos = new BitOutputStream(baos)) {
            bos.encodeBits(3, 6L);
            bos.writeUTF("hallo");
            bos.encodeString("du d\u00f6del du");
            bos.encodeString(null);
            bos.encodeString("");
            bos.encodeBits(5, 7L);
            bos.flush();
            bos.writeUTF("duda");
        }
        byte[] ab = baos.toByteArray();
        try (BitInputStream bis = new BitInputStream(ab)) {

            assertEquals(bis.decodeBits(3), 6L);
            assertEquals(bis.readUTF(), "hallo");
            assertEquals(bis.decodeString(), "du d\u00f6del du");
            assertEquals(bis.decodeString(), null);
            assertEquals(bis.decodeString(), "");
            assertEquals(bis.decodeBits(5), 7L);
            assertEquals(bis.readUTF(), "duda");
            assertEquals(0, bis.read(new byte[0], 0, 0));
        }

        // test bad padding
        try (BitInputStream bis = new BitInputStream(ab)) {

            bis.decodeBit();
            bis.readUTF();
            fail("should have thrown bad padding");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("non-zero padding"));
        }
    }

    public void testRandomUniqueSortedArrayEncodeDecode() throws IOException {
        Random rand = new Random();

        int size = 1000000;
        SortedSet<Long> valueSet = new TreeSet<>();
        while (valueSet.size() < size) {
            long lv = rand.nextInt() & 0x0fffffffL;
            valueSet.add(lv);
            if (valueSet.size() == 100) {
                valueSet.add(lv + 1); // force neighbors
            }
        }
        long[] values = new long[size];
        int i = 0;
        for (Long value : valueSet) {
            values[i++] = value;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BitOutputStream bos = new BitOutputStream(baos)) {
            bos.encodeUniqueSortedArray(values);
            bos.encodeUniqueSortedArray(new long[0]);
            bos.encodeUniqueSortedArray(values, 1, 2);
            bos.writeSyncBlock(0L);
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (BitInputStream bis = new BitInputStream(bais)) {
            long[] decodedValues = bis.decodeUniqueSortedArray();
            long[] emptyArray = bis.decodeUniqueSortedArray();
            long[] smallArray = new long[2];
            bis.decodeUniqueSortedArray(smallArray, 0, 2);
            long syncBlock = bis.readSyncBlock();
            assertTrue(Arrays.equals(values, decodedValues));
            assertTrue(emptyArray.length == 0);
            assertEquals(values[1], smallArray[0]);
            assertEquals(values[2], smallArray[1]);
            assertEquals(syncBlock, 0L);
        }
    }

    public void testDataInOutPut() throws IOException {

        // Test inter-operability DataOutputStream->BitInputStream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutput dos = new DataOutputStream(baos);
        writeAllIntoDataOutput(dos);
        DataInput bis = new BitInputStream(baos.toByteArray());
        readAllFromDataInput(bis);

        // do it vice versa (BitOutputStream->DataInputStream)
        baos.reset();
        DataOutput bos = new BitOutputStream(baos);
        writeAllIntoDataOutput(bos);
        DataInput dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        readAllFromDataInput(dis);

    }

    private void writeAllIntoDataOutput(DataOutput dos) throws IOException {

        byte[] ab = new byte[] { 1, 2, 3, 4 };

        dos.write(ab);
        dos.write(ab, 1, 2);
        dos.write(ab, 0, 3); // 3 bytes for skipping test
        dos.write(0x73);
        dos.write(0xab73); // should ignore high bits
        dos.writeBoolean(true);
        dos.writeByte(-19);
        dos.writeByte(-19);
        dos.writeShort(-25000);
        dos.writeShort(-25000);
        dos.writeChar('A');
        dos.writeInt(1864756);
        dos.writeLong(746284645684L);
        dos.writeFloat(0.3f);
        dos.writeDouble(0.9);
        dos.writeUTF("writeUTFIsLimitedTo64k");

        dos.writeBytes("writeBytesIsWrong\n");
        dos.writeChars("writeCharsIsBloated");
    }

    private void readAllFromDataInput(DataInput dis) throws IOException {

        byte[] ab0 = new byte[] { 1, 2, 3, 4 };
        byte[] ab = new byte[4];
        dis.readFully(ab);
        assertTrue(Arrays.equals(ab, ab0));
        byte[] ab2 = new byte[2];
        dis.readFully(ab, 0, 2);
        assertEquals(ab[0], 2);
        assertEquals(ab[1], 3);
        dis.skipBytes(3);
        assertEquals(dis.readByte(), (byte) 0x73);
        assertEquals(dis.readByte(), (byte) 0x73);
        assertEquals(dis.readBoolean(), true);
        assertEquals(dis.readByte(), (byte) (-19));
        assertEquals(dis.readUnsignedByte(), 256 - 19);
        assertEquals(dis.readShort(), (short) (-25000));
        assertEquals(dis.readUnsignedShort(), 65536 - 25000);
        assertEquals(dis.readChar(), 'A');
        assertEquals(dis.readInt(), 1864756);
        assertEquals(dis.readLong(), 746284645684L);
        assertEquals(dis.readFloat(), 0.3f);
        assertEquals(dis.readDouble(), 0.9);
        assertEquals(dis.readUTF(), "writeUTFIsLimitedTo64k");
        assertEquals(dis.readLine(), "writeBytesIsWrong");
        for (int i = 0; i < "writeCharsIsBloated".length(); i++) {
            assertEquals(dis.readChar(), "writeCharsIsBloated".charAt(i));
        }
    }

}
