/**
 * Set off departure offsets (immutable)
 *
 * @author ab
 */
package btools.memrouter;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

public class OffsetSet
{
  private Map<BitSet, OffsetSet> existingSets = new HashMap<BitSet, OffsetSet>();

  private static final int size = 185;

  protected BitSet mask;

  public OffsetSet emptySet()
  {
    return new OffsetSet( new BitSet( size ), existingSets );
  }

  public static OffsetSet fullSet()
  {
    BitSet allbits = new BitSet( size );
    allbits.set( 0, size );
    return new OffsetSet( allbits, new HashMap<BitSet, OffsetSet>() );
  }

  private OffsetSet( BitSet m, Map<BitSet, OffsetSet> knownSets )
  {
    existingSets = knownSets;
    existingSets.put( m , this );
    mask = m;
  }

  private OffsetSet create( BitSet m )
  {
    if ( m.isEmpty() )
    {
      return null;
    }
    if ( m.equals( mask ) )
    {
      return this;
    }

    OffsetSet set = existingSets.get( m );
    if ( set == null )
    {
      set = new OffsetSet( m, existingSets );
//      System.out.println( "created set: " + set + " instancecount=" + existingSets.size() );
    }
    return set;
  }

  public OffsetSet create( List<Integer> offsets )
  {
    BitSet m = new BitSet( size );
    for ( Integer offset : offsets )
    {
      int i = offset.intValue();
      if ( i >= 0 && i < size )
      {
        m.set( i );
      }
    }
    return create( m );
  }

  public OffsetSet filterWithSet( SortedSet<Integer> usedTimes, int minuteArrival )
  {
    BitSet fmask = (BitSet)mask.clone();
    int idx = 0;
    int maxtime = usedTimes.isEmpty() ? Integer.MAX_VALUE: usedTimes.first().intValue() + size();

    for(;;)
    {
      idx = fmask.nextSetBit( idx );      
      if ( idx < 0 ) break;
      int i = minuteArrival + idx;
      if ( i > maxtime || !usedTimes.add( Integer.valueOf(i) ) )
      {
        fmask.set( idx, false );
      }
      idx++;
    }
    return create( fmask );
  }

  public int size()
  {
    return size;
  }

  public boolean contains( int offset )
  {
    return mask.get( offset );
  }

  public OffsetSet add( int offset )
  {
    if ( mask.get( offset ) ) 
    {
      return this;
    }
    BitSet m = (BitSet)mask.clone();
    m.set( offset );
    return create( m );
  }
  
  public OffsetSet add( OffsetSet offsets )
  {
    BitSet m = (BitSet)mask.clone();
    m.or( offsets.mask );
    return create( m );
  }

  // clear all bits from this set in the argument set
  public OffsetSet filter( OffsetSet in )
  {
    BitSet fmask = (BitSet)in.mask.clone();
    fmask.andNot( mask );
    return create( fmask );
  }

  public OffsetSet ensureMaxOffset( int max )
  {
    if ( max < size )
    { 
      BitSet fmask = (BitSet)mask.clone();
      fmask.set( max > 0 ? max : 0, size, false );
      return create( fmask );
    }
    return this;
  }

  public OffsetSet filterAndClose( OffsetSetHolder gateHolder, boolean closeGate )
  {
    OffsetSet gate = gateHolder.getOffsetSet();
    BitSet gmask = (BitSet)gate.mask.clone();

    BitSet fmask = (BitSet)mask.clone();

    fmask.andNot( gmask );

    gmask.or( fmask );

    if ( closeGate )
    {
      gateHolder.setOffsetSet( create( gmask ) ); // modify the gate
    }
    return create( fmask );
  }

  public OffsetSet sweepWith( OffsetSet sweeper, int timeDiff )
  {
    BitSet sweepmask = sweeper.mask;
    BitSet fmask = (BitSet)mask.clone();

    if ( timeDiff >= 0 )
    {
      int idx = 0;
      for(;;)
      {
        idx = sweepmask.nextSetBit( idx ) + 1;
        if ( idx < 1 ) break;

        int sweepStart = Math.max( 0, idx-timeDiff );
        fmask.set( sweepStart, idx, false );
      }
      int sweepStart = Math.max( 0, size-timeDiff );
      fmask.set( sweepStart, size, false );
    }
// System.out.println( "sweep: " + mask + " with: " + sweepmask + "=" + fmask );

    return create( fmask );
  }

  @Override
  public String toString()
  {
   return mask.toString();
  }
}
