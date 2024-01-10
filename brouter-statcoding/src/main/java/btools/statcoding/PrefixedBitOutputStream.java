package btools.statcoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A PrefixedBitOutputStream is usually used to encode a data structure that is
 * expected to evolve.It prefixes the actual data section by a header containing
 * version information (major/minor) and the size of the data section in bytes.
 * 
 * A minor version change is expected to keep old decoders working by just
 * skipping any unknown, additional data. This is achieved by prefixing the
 * size.
 */
public class PrefixedBitOutputStream extends BitOutputStream {

    private final BitOutputStream targetOut;
    private long majorVersion;
    private long minorVersion;

    /**
     * Construct a PrefixedBitOutputStream. <br>
     * 
     * @param targetOut    the underlying bit-stream
     * @param majorVersion the major version to encode into the prefix
     * @param minorVersion the minor version to encode into the prefix
     */
    public PrefixedBitOutputStream(BitOutputStream targetOut, long majorVersion, long minorVersion) {
        super(new ByteArrayOutputStream());
        this.targetOut = targetOut;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        if ( majorVersion < 1 || minorVersion < 1 ) {
            throw new IllegalArgumentException( "version numbers must be >= 1 : " + majorVersion + "/" + minorVersion );
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        ByteArrayOutputStream bos = (ByteArrayOutputStream) out;
        targetOut.encodeUnsignedVarBits(majorVersion-1, 0);
        targetOut.encodeUnsignedVarBits(minorVersion-1, 0);
        targetOut.encodeUnsignedVarBits(bos.size(), 5);
        bos.writeTo(targetOut);
    }
}
