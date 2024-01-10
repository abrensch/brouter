package btools.statcoding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * PrefixedBitInputStream reads a data section that is prefixed with version and
 * size information. Useful to decode a data structure that is expected to
 * evolve.
 */
public class PrefixedBitInputStream extends BitInputStream {

    private long majorVersion;
    private long minorVersion;

    public PrefixedBitInputStream(BitInputStream sourceIn, long maxMajorVersion) throws IOException {
        super((InputStream) null);
        majorVersion = sourceIn.decodeUnsignedVarBits(0)+1;
        minorVersion = sourceIn.decodeUnsignedVarBits(0)+1;
        if (majorVersion > maxMajorVersion) {
            throw new IllegalArgumentException(
                    "unknown major version " + majorVersion + " (max=" + maxMajorVersion + ")");
        }
        byte[] ab = new byte[(int) sourceIn.decodeUnsignedVarBits(5)];
        sourceIn.readFully(ab);
        in = new ByteArrayInputStream(ab);
    }

    public long getMajorVersion() {
        return majorVersion;
    }

    public long getMinorVersion() {
        return minorVersion;
    }
}
