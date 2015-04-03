/**
 * Simple Train Router
 *
 * @author ab
 */
package btools.memrouter;




public final class ScheduledTrip
{
		public OffsetSet offsets;
		
		ScheduledTrip origin;
		OsmLinkP link;
		OsmNodeP originNode;
		int cost;   // in meter!
		int adjustedCost;   // in meter!
		long arrival; // in millis!
		long departure; // in millis!
		
		public float lastcostfactor;
		
		  public int ehbd; // in micrometer
		  public int ehbu; // in micrometer
		  public short selev = Short.MIN_VALUE;
		
		ScheduledTrip()
		{
			// dummy for OpenSetM
		}

		ScheduledTrip( OffsetSet offsets, OsmLinkP link, OsmNodeP originNode, ScheduledTrip origin  )
		{
			this.offsets = offsets;
			this.link = link;
			this.origin = origin;
			this.originNode = originNode;
		}
		
		public OsmNodeP getTargetNode()
		{
			return link.getTarget(originNode);
		}
		
		@Override
		public String toString()
		{
		  String prefix = "PlainLink";
		  if ( link instanceof ScheduledLink )
		  {
			  ScheduledLink l = (ScheduledLink)link;
			  ScheduledLine line = l.line;
			  prefix = "ScheduledLink: line=" + line.name;
		  }
		  else if ( link.isConnection() )
		  {
			  prefix = "ConnectingLink";
		  }

		  return prefix + " depart=" + departure + " arrival=" + arrival + " cost=" + cost + " offsets=" + offsets;
		}
}
