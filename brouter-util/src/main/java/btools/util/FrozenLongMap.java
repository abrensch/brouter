package btools.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen instance of Memory efficient Map
 *
 * This one is readily sorted into a singe array for faster access
 *
 * @author ab
 */
public class FrozenLongMap<V> extends CompactLongMap<V>
{
  private long[] faid;
  private ArrayList<V> flv;
  private int size = 0;
  private int p2size; // next power of 2 of size

  public FrozenLongMap( CompactLongMap<V> map )
  {
    size = map.size();

    faid = new long[size];
    flv = new ArrayList<V>(size);

    map.moveToFrozenArrays( faid, flv );

    p2size = 0x40000000;
    while( p2size > size ) p2size >>= 1;
  }

  @Override
  public boolean put( long id, V value )
  {
    throw new RuntimeException( "cannot put on FrozenLongIntMap" );
  }

  @Override
  public void fastPut( long id, V value )
  {
    throw new RuntimeException( "cannot put on FrozenLongIntMap" );
  }

  /**
   * @return the number of entries in this set
   */
  @Override
  public int size()
  {
    return size;
  }



  /**
   * @return true if "id" is contained in this set.
   */
  @Override
  protected boolean contains( long id, boolean doPut )
  {
    if ( size == 0 )
    {
      return false;
    }
    long[] a = faid;
    int offset = p2size;
    int n = 0;

    while ( offset> 0 )
    {
      int nn = n + offset;
      if ( nn < size && a[nn] <= id )
      {
        n = nn;
      }
      offset >>= 1;
    }
    if ( a[n] == id )
    {
      value_out = flv.get(n);
      return true;
    }
    return false;
  }

  /**
   * @return the value for "id",
   * Throw an exception if not contained in the map.
   */
  @Override
  public V get( long id )
  {
    if ( size == 0 )
    {
      return null;
    }
    long[] a = faid;
    int offset = p2size;
    int n = 0;

    while ( offset> 0 )
    {
      int nn = n + offset;
      if ( nn < size && a[nn] <= id )
      {
        n = nn;
      }
      offset >>= 1;
    }
    if ( a[n] == id )
    {
      return flv.get(n);
    }
    return null;
  }

  public List<V> getValueList()
  {
    return flv;
  }

  public long[] getKeyArray()
  {
    return faid;
  }
}
