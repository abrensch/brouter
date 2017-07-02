package btools.mapdecoder;


public final class BitReadBuffer
{
  private byte[] ab;
  private int idxMax;
  private int idx = -1;
  private int bits; // bits left in buffer
  private long b;

  public BitReadBuffer( byte[] ab )
  {
    this.ab = ab;
    idxMax = ab.length-1;
  }
  
  public boolean decodeBit()
  {
    fillBuffer();
    boolean value = ( ( b & 1L ) != 0 );
    b >>>= 1;
    bits--;
    return value;
  }

  public long decodeBits( int count )
  {
    if ( count == 0 )
    {
      return 0;
    }
    fillBuffer();
    long mask = -1L >>> ( 64 - count );
    long value = b & mask;
    b >>>= count;
    bits -= count;
    return value;
  }

  /**
   * decode an integer in the range 0..max (inclusive).
   */
  public long decodeBounded( long max )
  {
    long value = 0;
    long im = 1; // integer mask
    fillBuffer();
    while (( value | im ) <= max)
    {
      if ( ( b & 1 ) != 0 )
        value |= im;
      b >>>= 1;
      bits--;
      im <<= 1;
    }
    return value;
  }

  /**
   * decode a small number with a variable bit length
   * (poor mans huffman tree)
   * 1 -> 0
   * 01 -> 1 + following 1-bit word ( 1..2 )
   * 001 -> 3 + following 2-bit word ( 3..6 )
   * 0001 -> 7 + following 3-bit word ( 7..14 ) etc.
   */
  public int decodeInt()
  {
    long range = 1;
    int cnt = 1;
    fillBuffer();
    while ((b & range) == 0)
    {
      range = (range << 1) | 1;
      cnt++;
    }
    b >>>= cnt;
    bits -= cnt;
    return (int)((range >>> 1) + ( cnt > 1 ? decodeBits( cnt-1 ) : 0 ));
  }

  /**
   * double-log variant of decodeVarBits better suited for
   * distributions with a big-number tail
   */
  public long decodeLong()
  {
    int n = decodeInt();
    return (1L << n) + decodeBits( n ) - 1L;
  }

  public long[] decodeSortedArray()
  {
    int size = decodeInt();
    long[] values = new long[size];
    if ( size == 0 )
    {
      return values;
    }
    int offset = 0;
    long value = 0;
    int bits = decodeInt();
    int[] sizestack = new int[bits];
    int stackpointer = 0;

    for(;;)
    {
      while( size > 1 && bits > 0 )
      {
        int size2 = (int)decodeBounded( size );
        sizestack[stackpointer++] = size2;
        size -= size2;
        value <<= 1;
        bits--;
      }
      if ( size == 1 )
      {
        values[offset++] = (value << bits) | decodeBits( bits );
      }
      else
      {
        while (size-- > 0)
        {
          values[offset++] = value;
        }
      }
      if ( stackpointer == 0 )
      {
        return values;
      }
      while ( ( value & 1L ) == 1L  )
      {
        value >>= 1;
        bits++;
      }
      value |= 1L;
      size = sizestack[--stackpointer];
    }
  }

  
  private void fillBuffer()
  {
    while (bits <= 56)
    {
      if ( idx < idxMax )
      {
        b |= (ab[++idx] & 0xffL) << bits;
      }
      bits += 8;
    }
  }    
}
