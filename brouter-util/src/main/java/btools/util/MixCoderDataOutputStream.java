/**
 * DataOutputStream for fast-compact encoding of number sequences
 *
 * @author ab
 */
package btools.util;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;


public final class MixCoderDataOutputStream extends DataOutputStream
{
  private long lastValue;
  private long lastLastValue;
  private long repCount;
  private boolean doFlush;

  public MixCoderDataOutputStream( OutputStream os )
  {
     super( os );
  }

  public void writeSigned( long v ) throws IOException
  {
    writeUnsigned( v < 0 ? ( (-v) << 1 ) | 1 : v << 1 );
  }

  public void writeUnsigned( long v ) throws IOException
  {
    if ( v < 0 ) throw new IllegalArgumentException(  "writeUnsigned: " + v );
    do
    {
      long i7 = v & 0x7f;
      v >>= 7;
      if ( v != 0 ) i7 |= 0x80;
      writeByte( (byte)( i7 & 0xff ) );
    }
    while( v != 0 );
  }

  public void writeMixed( long v ) throws IOException
  {
    if ( v != lastValue && repCount > 0 )
    {
      long d = lastValue - lastLastValue;
      lastLastValue = lastValue;

      // if diff fits within 6 bits and rep-count < 4, write a single byte
      int repCode = repCount < 4 ? (int)repCount : 0;
      int diffcode = (int)(d > -32 && d < 32 ? d+32 : 0);

      writeByte( (byte)( diffcode | repCode << 6 ) );
      if ( repCode == 0)
      {
        writeUnsigned( repCount );
      }
      if ( diffcode == 0)
      {
        writeSigned( d );
      }
      repCount = 0;
    }
    lastValue = v;
    repCount++;
  }

  @Override
  public void flush() throws IOException
  {
    // todo: does this keep stream consistency after flush ?
    long v = lastValue;
    writeMixed( v+1 );
    lastValue = v;
    repCount = 0;
  }

}
