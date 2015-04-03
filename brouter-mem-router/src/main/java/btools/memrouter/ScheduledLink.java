/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.memrouter;


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
}
