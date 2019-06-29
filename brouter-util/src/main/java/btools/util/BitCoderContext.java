package btools.util;


public class BitCoderContext
{
  private byte[] ab;
  private int idxMax;
  private int idx = -1;
  private int bm = 0x100; // byte mask (write mode)
  private int bits; // bits left in buffer (read mode)
  private int b;

  private static final int[] vl_values = new int[4096];
  private static final int[] vl_length = new int[4096];

  private static final int[] reverse_byte = new int[256];

  static
  {
    // fill varbits lookup table

    BitCoderContext bc = new BitCoderContext( new byte[4] );
    for( int i=0; i<4096; i++ )
    {
      bc.reset();
      bc.bits = 14;
      bc.b = 0x1000 + i;

      int b0 = bc.getReadingBitPosition();
      vl_values[i] = bc.decodeVarBits2();
      vl_length[i] = bc.getReadingBitPosition() - b0;
    }
    for( int b=0; b<256; b++ )
    {
      int r = 0;
      for( int i=0; i<8; i++ )
      {
        if ( (b & (1<<i) ) != 0 ) r |= 1 << (7-i);
      }
      reverse_byte[b] = r;
    }
  }


  public BitCoderContext( byte[] ab )
  {
    this.ab = ab;
    idxMax = ab.length-1;
  }

  public final void reset( byte[] ab )
  {
    this.ab = ab;
    idxMax = ab.length-1;
    reset();
  }

  public final void reset()
  {
    idx = -1;
    bm = 0x100;
    bits = 0;
    b = 0;
  }

  /**
   * encode a distance with a variable bit length
   * (poor mans huffman tree)
   * {@code 1 -> 0}
   * {@code 01 -> 1} + following 1-bit word ( 1..2 )
   * {@code 001 -> 3} + following 2-bit word ( 3..6 )
   * {@code 0001 -> 7} + following 3-bit word ( 7..14 ) etc.
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
  public final int decodeVarBits2()
  {
    int range = 0;
    while (!decodeBit())
    {
      range = 2 * range + 1;
    }
    return range + decodeBounded( range );
  }

  public final int decodeVarBits()
  {
    fillBuffer();
    int b12 = b & 0xfff;
    int len = vl_length[b12];
    if ( len <= 12 )
    {
      b >>>= len;
      bits -= len;
      return vl_values[b12]; // full value lookup
    }
    if ( len <= 23 ) // // only length lookup
    {
      int len2 = len >> 1;
      b >>>= (len2+1);
      int mask = 0xffffffff >>> ( 32 - len2 );
      mask += b & mask;
      b >>>= len2;
      bits -= len;
      return mask;
    }
    return decodeVarBits2();
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
    if ( bits == 0 )
    {
      bits = 8;
      b = ab[++idx] & 0xff;
    }
    boolean value = ( ( b & 1 ) != 0 );
    b >>>= 1;
    bits--;
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
      if ( bits == 0 )
      {
        bits = 8;
        b = ab[++idx] & 0xff;
      }
      if ( ( b & 1 ) != 0 )
        value |= im;
      b >>>= 1;
      bits--;
      im <<= 1;
    }
    return value;
  }

  public final int decodeBits( int count )
  {
    fillBuffer();
    int mask = 0xffffffff >>> ( 32 - count );
    int value = b & mask;
    b >>>= count;
    bits -= count;
    return value;
  }

  public final int decodeBitsReverse( int count )
  {
    fillBuffer();
    int value = 0;
    while( count > 8 )
    {
      value = (value << 8) | reverse_byte[ b & 0xff ];
      b >>=8;
      count -=8;
      bits -=8;
      fillBuffer();
    }
    value = (value << count) | reverse_byte[ b & 0xff ] >> (8-count);
    bits -= count;
    b >>= count;
    return value;
  }

  private void fillBuffer()
  {
    while (bits < 24)
    {
      if ( idx < idxMax )
      {
        b |= (ab[++idx] & 0xff) << bits;
      }
      bits += 8;
    }
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
  public final long getWritingBitPosition()
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

  public final int getReadingBitPosition()
  {
    return (idx << 3) + 8 - bits;
  }

  public final void setReadingBitPosition(int pos)
  {
    idx = pos >>> 3;
    bits = (idx << 3) + 8 - pos;
    b = ab[idx] & 0xff;
    b >>>= (8-bits);
  }

  public final void copyBitsTo( byte[] dst, int bitcount )
  {
    int dstIdx = 0;
    for(;;)
    {
      if ( bitcount > 8 )
      {
        if ( bits < 8 )
        {
          b |= (ab[++idx] & 0xff) << bits;
        }
        else
        {
          bits -= 8;
        }
        dst[dstIdx++] = (byte)b;
        b >>>= 8;
        bitcount -= 8;
      }
      else
      {
        if ( bits < bitcount )
        {
          b |= (ab[++idx] & 0xff) << bits;
          bits += 8;
        }

        int mask = 0xff >>> ( 8 - bitcount );
        dst[dstIdx] = (byte)(b & mask);
        bits -= bitcount;
        b >>>= bitcount;
        break;
      }
    }
  }

}
