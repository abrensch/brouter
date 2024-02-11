package btools.mapaccess;

import btools.statcoding.BitOutputStream;
import btools.util.SimpleByteArrayOutputStream;

/**
 * Container for some re-usable databuffers for the decoder
 */
public final class DataBuffers {
  public byte[] ioBuffer;
  public SimpleByteArrayOutputStream tagBuffer = new SimpleByteArrayOutputStream( new byte[512] );
  public BitOutputStream tagEncoder = new BitOutputStream( tagBuffer );

  public DataBuffers() {
    this(new byte[102400]);
  }

  /**
   * construct a set of databuffers except
   * for 'iobuffer', where the given array is used
   */
  public DataBuffers(byte[] iobuffer) {
    this.ioBuffer = iobuffer;
  }

}
