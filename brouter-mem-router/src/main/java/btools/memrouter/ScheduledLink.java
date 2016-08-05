/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.memrouter;

import java.util.SortedSet;
import java.util.TreeSet;


public class ScheduledLink extends OsmLinkP
{
  public ScheduledLink( StationNode source, StationNode target )
  {
    super( source, target );
  }

  public ScheduledLine line;
  public int indexInLine;

  public boolean isConnection()
  {
    return false;
  }

  public boolean isWayLink()
  {
    return false;
  }

  public String toString()
  {
	  return "ScheduledLink: line=" + line.name + " indexInLine=" + indexInLine;
  }

  private SortedSet<Integer> usedTimes;
  
  @Override  
  protected void initLink()
  {
    super.initLink();
    usedTimes = new TreeSet<Integer>();
  }


  public OffsetSet filterAndClose( OffsetSet in, long arrival )
  {
    OffsetSet filtered = super.filterAndClose( in, arrival );
    if ( filtered != null && arrival >= 0 )
    {
      int minutesArrival = (int) ( arrival / 60000L );
      filtered = filtered.filterWithSet( usedTimes, minutesArrival );
    }
    return filtered;
  }

}
