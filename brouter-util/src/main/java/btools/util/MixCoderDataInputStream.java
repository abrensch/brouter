/**
 * DataInputStream for decoding fast-compact encoded number sequences
 *
 * @author ab
 */
package btools.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;


public final class MixCoderDataInputStream extends DataInputStream
{
  private long lastValue;
  private long repCount;

  public MixCoderDataInputStream( InputStream is )
  {
    super( is );
  }

  public long readSigned() throws IOException
  {
    long v = readUnsigned();
    return ( v & 1 ) == 0 ? v >> 1 : -(v >> 1 );
  }

  public long readUnsigned() throws IOException
  {
    long v = 0;
    int shift = 0;
    for(;;)
    {
      long i7 = readByte() & 0xff;
      v |= (( i7 & 0x7f ) << shift);
      if ( ( i7 & 0x80 ) == 0 ) break;
      shift += 7;
    }
    return v;
  }

  public long readMixed() throws IOException
  {
    if ( repCount == 0 )
    {
      long b = readByte() & 0xff;
      long repCode = b >> 6;
      long diffcode = b & 0x3f;
      repCount = repCode == 0 ?  readUnsigned() : repCode;
      lastValue += diffcode == 0 ? readSigned() : diffcode - 32;
    }
    repCount--;
    return lastValue;
  }

}
