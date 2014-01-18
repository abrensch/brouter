package btools.util;

import java.util.ArrayList;

/**
 * Special Memory efficient Map to map a long-key to
 * a "small" value (some bits only) where it is expected
 * that the keys are dense, so that we can use more or less
 * a simple array as the best-fit data model (except for
 * the 32-bit limit of arrays!)
 *
 * Additionally, to enable small-memory unit testing 
 * of code using this map this one has a fallback for a small map
 * where we have only few keys, but not dense. In this
 * case, we use the mechanics of the CompactLongMap
 *
 * Target application are osm-node ids which are in the
 * range 0...3 billion and basically dense (=only few
 * nodes deleted)
 *
 * @author ab
 */
public class DenseLongMap
{
  private ArrayList<int[]> blocklist = new ArrayList<int[]>(1024);

  private static final int BLOCKSIZE = 0x10000; // 64k * 32 bits
  private int valuebits;
  private int maxvalue;
  private long maxkey;
  private long maxmemory;

  /**
   * Creates a DenseLongMap for the given value range
   * Note that one value is reserved for the "unset" state,
   * so with 6 value bits you can store values in the
   * range 0..62 only
   *
   * @param valuebits number of bits to use per value
   */
  public DenseLongMap( int valuebits )
  {
    if ( valuebits < 1 || valuebits > 32 )
    {
      throw new IllegalArgumentException( "invalid valuebits (1..32): " + valuebits );
    }
    this.valuebits = valuebits;
    maxmemory = (Runtime.getRuntime().maxMemory() / 8) * 7; // assume most of it for our map
    maxvalue = (1 << valuebits) - 2;
    maxkey = ( maxmemory / valuebits ) * 8;
  }



  public void put( long key, int value )
  {
    if ( key < 0L || key > maxkey )
    {
      throw new IllegalArgumentException( "key out of range (0.." + maxkey + "): " + key
            + " give more memory (currently " + (maxmemory / 0x100000)
            + "MB) to extend key range" );
    }
    if ( value < 0 || value > maxvalue )
    {
      throw new IllegalArgumentException( "value out of range (0.." + maxvalue + "): " + value );
    }

    int blockn = (int)(key >> 21);
    int offset = (int)(key & 0x1fffff);

    int[] block = blockn < blocklist.size() ? blocklist.get( blockn ) : null;

    if ( block == null )
    {
      block = new int[BLOCKSIZE * valuebits];

      while (blocklist.size() < blockn+1 )
      {
        blocklist.add(null);
      }      
      blocklist.set( blockn, block );
    }
    
    int bitmask = 1 << (offset & 0x1f);
    int invmask = bitmask ^ 0xffffffff;
    int probebit = 1;
    int blockidx = (offset >> 5)*valuebits;
    int blockend = blockidx + valuebits;
    int v = value + 1; // 0 is reserved (=unset)

    while( blockidx < blockend )
    {
      if ( ( v & probebit ) != 0 )
      {
        block[blockidx] |= bitmask;
      }
      else
      {
        block[blockidx] &= invmask;
      }
      probebit <<= 1;
      blockidx++;
    }
  }


  public int getInt( long key )
  {
    if ( key < 0 )
    {
      return -1;
    }
    int blockn = (int)(key >> 21);
    int offset = (int)(key & 0x1fffff);

    int[] block = blockn < blocklist.size() ? blocklist.get( blockn ) : null;

    if ( block == null )
    {
      return -1;
    }
    int bitmask = 1 << (offset & 0x1f);
    int probebit = 1;
    int blockidx = (offset >> 5)*valuebits;
    int blockend = blockidx + valuebits;
    int v = 0; // 0 is reserved (=unset)

    while( blockidx < blockend )
    {
      if ( ( block[blockidx] & bitmask ) != 0 )
      {
        v  |= probebit;
      }
      probebit <<= 1;
      blockidx++;
    }
    return v-1;
  }

}
