package btools.statcoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

public class PrefixedBitStreamsTest extends TestCase {

    public void testPrefixedBitStreams() throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (BitOutputStream bos = new BitOutputStream(baos)) {
            bos.encodeUnsignedVarBits(17L,3);
            try( PrefixedBitOutputStream pbos = new PrefixedBitOutputStream( bos, 3L, 4L ) ) {
               pbos.encodeUnsignedVarBits(177L,0);
               pbos.encodeString( "hallo" );
               pbos.encodeBit( false );
            }
            bos.encodeBit(true);
        }
        byte[] ab = baos.toByteArray();
        ByteArrayInputStream bais = new ByteArrayInputStream(ab);
        try (BitInputStream bis = new BitInputStream(bais)) {
            assertEquals( 17L, bis.decodeUnsignedVarBits(3) );
            try ( PrefixedBitInputStream pbis = new PrefixedBitInputStream( bis, 3L ) ) {

                assertEquals( 3L, pbis.getMajorVersion() );
                assertEquals( 4L, pbis.getMinorVersion() );
                assertEquals( 177L, pbis.decodeUnsignedVarBits(0) );
                assertEquals( "hallo", pbis.decodeString() );
                assertEquals( false, pbis.decodeBit() );
            }
            assertEquals( true, bis.decodeBit() );
        }
    }

}
