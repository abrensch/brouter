/**
 * Set off departure offsets (immutable)
 *
 * @author ab
 */
package btools.memrouter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OffsetSet
{
  private static Map<Long,OffsetSet> existingSets = new HashMap<Long,OffsetSet>();
  private static OffsetSet empty = new OffsetSet( 0L );
  private static OffsetSet full = new OffsetSet( -1L );

  protected long mask;
  
  private static int instancecount = 0;

  public static OffsetSet emptySet()
  {
    return empty;  
  }

  public static OffsetSet fullSet()
  {
    return full;
  }

  private OffsetSet( long m )
  {
    mask = m;
  }

  private static OffsetSet create( long m, OffsetSet template )
  {
	  if ( m == 0L )
	  {
	    return null;
	  }
	  if ( m == template.mask )
	  {
	    return template;
	  }
	  
	  Long mm = Long.valueOf( m );
	  OffsetSet set = existingSets.get( mm );
	  if ( set == null )
	  {
	    set = new OffsetSet( m );
	    existingSets.put( mm, set );
		instancecount++;
	    System.out.println( "created set: " + set + " instancecount=" + instancecount );
	  }
      return set;
  }
  public static OffsetSet create( List<Integer> offsets, OffsetSet template )
  {
	  long m = 0L;
	  for( Integer offset : offsets )
	  {
		  int i = offset.intValue();
		  if ( i >= 0 && i < 64 )
		  {
			  m |= ( 1L << i );
		  }
	  }
	  return create( m, template );
  }
  
  public int size()
  {
	  return 64;
  }
  
  public boolean contains( int offset )
  {
	  return ( ( 1L << offset ) & mask ) != 0L;
  }
  
  public OffsetSet add( int offset )
  {
	  return create( mask | ( 1L << offset ), this );
  }
  
  public OffsetSet add( OffsetSet offsets )
  {
	  return create(mask | offsets.mask, this );
  }

  public OffsetSet filter( OffsetSet in )
  {
		long fmask = in.mask;
		fmask = fmask ^ ( fmask & mask );
		return create( fmask, in );
  }

  public static OffsetSet filterAndClose( OffsetSet in, OffsetSetHolder gateHolder, int timeDiff )
  {
	OffsetSet gate = gateHolder.getOffsetSet();
	long gmask = gate.mask;
	  
    long fmask = in.mask;
	
	// delete the high offsets with offset + timeDiff >= maxoffset
    fmask = timeDiff > 31 ? 0L : ( fmask << timeDiff ) >> timeDiff;		
	
	fmask = fmask ^ ( fmask & gmask );

	gmask |= fmask;
	
	gateHolder.setOffsetSet( create( gmask, gate ) ); // modify the gate
		
	if ( timeDiff > 0 )
	{
		fmask = fmask ^ ( fmask & (gmask >> timeDiff) );
	}
	return create( fmask, in );
  }
  
  
  @Override
  public String toString()
  {
	  if ( mask == -1L ) return "*";
	  
	  StringBuilder sb = new StringBuilder();
	  int nbits = 0;
	  for( int i=0; i<65; i++ )
	  {
		  boolean bit = i < 64 ? ((1L << i) & mask) != 0L : false;
		  if ( bit ) nbits++;
		  else if ( nbits > 0)
		  {
			if ( sb.length() > 0 ) sb.append( ',' );
			if ( nbits == 1) sb.append( i-1 );
			else sb.append( (i-nbits) + "-" + (i-1) );
			nbits = 0;
		  }
	  }
	  return sb.toString();
  }
}
