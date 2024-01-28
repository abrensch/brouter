package btools.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * BitOutputStream is a replacement for java.io.DataOutputStream extending it by
 * bitwise operations suitable for statistical encoding.
 * <br>
 * It automatically re-aligns to byte-alignment as soon as any of the methods of
 * OutputStream or DataOutput or its own method 'encodeVarBytes' is called.
 */
public class SimpleByteArrayOutputStream extends OutputStream {

  private final byte[] buf;
  private int pos;

  /**
   * Construct a SimpleByteArrayOutputStream for the underlying byte array
   */
  public SimpleByteArrayOutputStream (byte[] buffer) {
    buf = buffer;
  }

  @Override
  public void write(int b) throws IOException {
    buf[pos++] = (byte) b;
  }
}
