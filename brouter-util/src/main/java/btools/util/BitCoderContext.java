package btools.util;


public class BitCoderContext
{
  private byte[] ab;
  private int idx = -1;
  private int bm = 0x100; // byte mask
  private int b;

  public BitCoderContext( byte[] ab )
  {
    this.ab = ab;
  }

  /**
   * encode a distance with a variable bit length
   * (poor mans huffman tree)
   * 1 -> 0
   * 01 -> 1 + following 1-bit word ( 1..2 )
   * 001 -> 3 + following 2-bit word ( 3..6 )
   * 0001 -> 7 + following 3-bit word ( 7..14 ) etc.
   * 
   * @see #decodeVarBits
   */
  public final void encodeVarBits( int value )
  {
    int range = 0;
    while (value > range)
    {
      encodeBit( false );
      value -= range + 1;
      range = 2 * range + 1;
    }
    encodeBit( true );
    encodeBounded( range, value );
  }

  /**
   * @see #encodeVarBits
   */
  public final int decodeVarBits()
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

  public final void encodeBit( boolean value )
  {
    if ( bm == 0x100 )
    {
      bm = 1;
      ab[++idx] = 0;
    }
    if ( value )
      ab[idx] |= bm;
    bm <<= 1;
  }

  public final boolean decodeBit()
  {
    if ( bm == 0x100 )
    {
      bm = 1;
      b = ab[++idx];
    }
    boolean value = ( ( b & bm ) != 0 );
    bm <<= 1;
    return value;
  }

  /**
   * encode an integer in the range 0..max (inclusive).
   * For max = 2^n-1, this just encodes n bits, but in general
   * this is variable length encoding, with the shorter codes
   * for the central value range
   */
  public final void encodeBounded( int max, int value )
  {
    int im = 1; // integer mask
    while (im <= max)
    {
      if ( bm == 0x100 )
      {
        bm = 1;
        ab[++idx] = 0;
      }
      if ( ( value & im ) != 0 )
      {
        ab[idx] |= bm;
        max -= im;
      }
      bm <<= 1;
      im <<= 1;
    }
  }

  /**
   * decode an integer in the range 0..max (inclusive).
   * @see #encodeBounded
   */
  public final int decodeBounded( int max )
  {
    int value = 0;
    int im = 1; // integer mask
    while (( value | im ) <= max)
    {
      if ( bm == 0x100 )
      {
        bm = 1;
        b = ab[++idx];
      }
      if ( ( b & bm ) != 0 )
        value |= im;
      bm <<= 1;
      im <<= 1;
    }
    return value;
  }

  /**
   * @return the encoded length in bytes
   */
  public final int getEncodedLength()
  {
    return idx + 1;
  }

  /**
   * @return the encoded length in bits
   */
  public final long getBitPosition()
  {
    long bitpos = idx << 3;
    int m = bm;
    while (m > 1)
    {
      bitpos++;
      m >>= 1;
    }
    return bitpos;
  }

}
