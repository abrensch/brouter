package btools.mapsplitter;

import java.util.TreeMap;


public final class BitWriteBuffer
{
  private static TreeMap<String, long[]> statsPerName;
  private long lastbitpos = 0;

  private byte[] ab;
  private int idxMax;
  private int idx = -1;
  private int bm = 0x100; // byte mask (write mode)
  private int b;

  public BitWriteBuffer( byte[] ab )
  {
    this.ab = ab;
    idxMax = ab.length-1;
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
  public void encodeInt( int value )
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

  public void encodeLong( long n )
  {
    int maxbit = 0;
    long nn = n + 1L;
    while( nn > 1L )
    {
      maxbit++;
      nn >>= 1;
    }
    encodeInt( maxbit );
    long range = 1 << maxbit;
    encodeBounded( range-1L, n + 1L -range );
  }

  public void encodeBit( boolean value )
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


  /**
   * encode an integer in the range 0..max (inclusive).
   * For max = 2^n-1, this just encodes n bits, but in general
   * this is variable length encoding, with the shorter codes
   * for the central value range
   */
  public void encodeBounded( long max, long value )
  {
    long im = 1L; // integer mask
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
   * @return the encoded length in bytes
   */
  public int getEncodedLength()
  {
    return idx + 1;
  }

  /**
   * @return the encoded length in bits
   */
  public long getWritingBitPosition()
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

  public void encodeSortedArray( long[] values )
  {
    int size = values.length;
    encodeInt( size );
    if ( size == 0 )
    {
      return;
    }      
    long maxValue = values[size-1];
    int nbits = 0;
    while ( maxValue > 0 )
    {
      nbits++;
      maxValue >>= 1;
    }
    if ( nbits > 57 ) throw new IllegalArgumentException( "encodeSortedArray accepts 57-bit numbers at max" );
    encodeInt( nbits );
    encodeSortedArray( values, 0, size, ( 1L << nbits ) >> 1, 0L );
  }

  private void encodeSortedArray( long[] values, int offset, int subsize, long nextbit, long mask )
  {
    if ( subsize == 1 ) // last-choice shortcut
    {
      long bit = 1L;
      while ( bit <= nextbit )
      {
        encodeBit( ( values[offset] & bit ) != 0 );
        bit <<= 1;
      }
      return;
    }
    if ( nextbit == 0 )
    {
      return;
    }

    long data = mask & values[offset];
    mask |= nextbit;

    // count 0-bit-fraction
    int i = offset;
    int end = subsize + offset;
    for ( ; i < end; i++ )
    {
      if ( ( values[i] & mask ) != data )
      {
        break;
      }
    }
    int size1 = i - offset;
    int size2 = subsize - size1;

    encodeBounded( subsize, size2 );
    if ( size1 > 0 )
    {
      encodeSortedArray( values, offset, size1, nextbit >> 1, mask );
    }
    if ( size2 > 0 )
    {
      encodeSortedArray( values, i, size2, nextbit >> 1, mask );
    }
  }

  /**
   * assign the de-/encoded bits since the last call assignBits to the given
   * name. Used for encoding statistics
   * 
   * @see #getBitReport
   */
  public void assignBits( String name )
  {
    long bitpos = getWritingBitPosition();
    if ( statsPerName == null )
    {
      statsPerName = new TreeMap<String, long[]>();
    }
    long[] stats = statsPerName.get( name );
    if ( stats == null )
    {
      stats = new long[2];
      statsPerName.put( name, stats );
    }
    stats[0] += bitpos - lastbitpos;
    stats[1] += 1;
    lastbitpos = bitpos;
  }

  /**
   * Get a textual report on the bit-statistics
   * 
   * @see #assignBits
   */
  public static String getBitReport()
  {
    if ( statsPerName == null )
    {
      return "<empty bit report>";
    }
    StringBuilder sb = new StringBuilder();
    for ( String name : statsPerName.keySet() )
    {
      long[] stats = statsPerName.get( name );
      sb.append( name + " count=" + stats[1] + " bits=" + stats[0] + "\n" );
    }
    statsPerName = null;
    return sb.toString();
  }
}
