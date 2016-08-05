/**
 * Set off departure offsets (immutable)
 *
 * @author ab
 */
package btools.memrouter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import btools.router.OsmTrack;

public class Iternity implements Comparable<Iternity>
{
  OsmTrack track;
  OffsetSet offsets;
  List<String> details = new ArrayList<String>();
  List<String> lines = new ArrayList<String>();
  long departtime;
  long arrivaltime;
  
  @Override
  public int compareTo( Iternity it )
  {
    return arrivaltime == it.arrivaltime ? 0 : ( arrivaltime < it.arrivaltime ? -1 : 1 );
  }
  
  void appendSummary( List<String> sum )
  {
    SimpleDateFormat df = new SimpleDateFormat( "dd.MM HH:mm", Locale.GERMAN );    
    sum.add( "depart: " + df.format( new Date( departtime ) )+ " arrive: " + df.format( new Date( arrivaltime ) ) );

    StringBuilder sb = new StringBuilder( "--- " );
    for( String line: lines )
    {
      sb.append( line ).append( ' ' );
    }
    sb.append( "--- " );
    long mins = ( arrivaltime-departtime ) / 60000L;
    sb.append( mins ).append( "min" );
    sum.add( sb.toString() );
    
    int firstOffset = -1;
    boolean hasLaterTrips = false;
    sb = new StringBuilder( "(+later trips: " );
    for ( int offset = 0; offset < offsets.size(); offset++ )
    {
      if ( offsets.contains( offset ) )
      {
        if ( firstOffset < 0 )
        {
          firstOffset = offset;
        }
        else
        {
          sb.append( "+" + (offset-firstOffset) + "min " );
          hasLaterTrips = true;
        }
      }
      if ( sb.length() > 47 )
      {
        sb.setLength( 47 );
        sb.append( "..." );
      }
    }
    sb.append( ")" );
    if ( hasLaterTrips )
    {
      sum.add( sb.toString() );
    }
       
    
  }
}
