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
  private int lastValue;
  private int repCount;
  private int diffshift;

  private int bm = 0x100;
  private int b;

  public MixCoderDataInputStream( InputStream is )
  {
    super( is );
  }

  public int readMixed() throws IOException
  {
    if ( repCount == 0 )
    {
      boolean negative = decodeBit();
      int d = decodeVarBits() + diffshift;
      repCount = decodeVarBits() + 1;
      lastValue += negative ? -d : d;
      diffshift = 1;
    }
    repCount--;
    return lastValue;
  }

  public final boolean decodeBit() throws IOException
  {
    if ( bm == 0x100 )
    {
      bm = 1;
      b = readByte();
    }
    boolean value = ( ( b & bm ) != 0 );
    bm <<= 1;
    return value;
  }

  public final int decodeVarBits() throws IOException
  {
    int range = 0;
    int value = 0;
    while (!decodeBit())
    {
      value += range + 1;
      range = 2 * range + 1;
    }
    return value + decodeBounded( range );
  }


  public final int decodeBounded( int max ) throws IOException
  {
    int value = 0;
    int im = 1; // integer mask
    while (( value | im ) <= max)
    {
      if ( bm == 0x100 )
      {
        bm = 1;
        b = readByte();
      }
      if ( ( b & bm ) != 0 )
        value |= im;
      bm <<= 1;
      im <<= 1;
    }
    return value;
  }

}
