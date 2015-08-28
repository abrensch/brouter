/**
 * Simple Train Router
 *
 * @author ab
 */
package btools.memrouter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import btools.mapaccess.OsmPos;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.util.SortedHeap;


final class ScheduledRouter
{
	private GraphLoader graph;
	
	private int solutionCount = 0;
	
	public long linksProcessed = 0L;
	public long linksReProcessed = 0L;
	public long closedSkippedChained = 0L;
	public long skippedChained = 0L;
	
	private RoutingContext rc;
	private RoutingEngine re;
	
	private long time0;
	private OsmNodeP start;
	private OsmNodeP end;

    SortedHeap<ScheduledTrip> openSet = new SortedHeap<ScheduledTrip>();

	ScheduledRouter( GraphLoader graph, RoutingContext rc, RoutingEngine re )
	{
		this.graph = graph;
		this.rc = rc;
		this.re = re;
	}
	
	
	public OsmTrack findRoute( OsmPos startPos, OsmPos endPos, String startTime, int alternativeIdx ) throws Exception
	{
	    OsmTrack track = null;
		
		start = graph.matchNodeForPosition( startPos, rc.expctxWay );
		if ( start == null ) throw new IllegalArgumentException( "unmatched start: " + startPos );
		end = graph.matchNodeForPosition( endPos, rc.expctxWay );
		if ( end == null ) throw new IllegalArgumentException( "unmatched end: " + endPos );

//		SimpleDateFormat df = new SimpleDateFormat( "dd.MM.yyyy-HH:mm" );
//		time0 = df.parse(startTime).getTime();
time0 = System.currentTimeMillis() + (long)(rc.starttimeoffset * 60000L );
long minutes0 = (time0 + 59999L) / 60000L;
time0 = minutes0 * 60000L;

		OffsetSet finishedOffsets = OffsetSet.emptySet();
		
		OsmLinkP startLink = new OsmLinkP( null, start );
		
		ScheduledTrip startTrip = new ScheduledTrip( OffsetSet.fullSet(), startLink, null, null );
		openSet.add( 0, startTrip );
		for(;;)
		{
			if ( re.isTerminated() )
			{
				throw new RuntimeException( "operation terminated" );
			}
			if ( linksProcessed + linksReProcessed > 5000000 )
			{
				throw new RuntimeException( "5 Million links limit reached" );
			}
			
			// get cheapest trip from heap
			ScheduledTrip trip = openSet.popLowestKeyValue();
			if ( trip == null )
			{
			  break;
			}
			OsmLinkP currentLink = trip.link;
			OsmNodeP currentNode = trip.getTargetNode();
			if ( currentNode == null )
			{
				System.out.println( "ups: " + trip );
				continue;
			}
			
			if ( currentLink.isVirgin() )
			{
			  linksProcessed++;
			}
			else
			{
			  linksReProcessed++;
			}
			
	        // check global closure
			OffsetSet offsets = finishedOffsets.filter( trip.offsets );
			if ( offsets == null ) continue;

			// check local closure for links:
			offsets = currentLink.filterAndClose( offsets, trip.arrival, currentLink instanceof ScheduledLink );
			if ( offsets == null ) continue;
			
			
			// check for arrival
			if ( currentNode == end )
			{
			  for( int offset = 0; offset<trip.offsets.size(); offset++ )
			  {
				  if ( trip.offsets.contains( offset ) )
				  {
				    track = compileTrip( trip, offset );
				    System.out.println( "---- begin route ------ (cost " + track.cost + ")" );
				    for( String s : track.iternity ) System.out.println( s );
				    System.out.println( "---- end route ------" );
				    break; // + plus more offsets..
				  }
			  }
			  finishedOffsets = finishedOffsets.add( offsets );
			  if ( solutionCount++ >= alternativeIdx ) return track;
			}
	        for( OsmLinkP link = currentNode.getFirstLink(); link != null; link = link.getNext( currentNode ) )
			{
	          addNextTripsForLink(trip, currentNode, currentLink, link, offsets, 0 );
			}
		}
	    return track;
	}
	
	private void addToOpenSet( ScheduledTrip nextTrip )
    {
	  int distance = nextTrip.getTargetNode().calcDistance( end );
	  nextTrip.adjustedCost = nextTrip.cost + (int)(distance * rc.pass1coefficient + 0.5);
	  openSet.add( nextTrip.adjustedCost, nextTrip );
    }
	
	
    private void addNextTripsForLink( ScheduledTrip trip, OsmNodeP currentNode, OsmLinkP currentLink, OsmLinkP link, OffsetSet offsets, int level )
	{
	  if ( link == currentLink ) 
	  {
	    return; // just reverse, ignore
	  }
	  OsmNodeP node = link.getTarget(currentNode);
		if ( node == null )
		{
			System.out.println( "ups2: " + link );
			return;
		}

	  // calc distance and check nogos
	  rc.nogomatch = false;
	  int distance = rc.calcDistance( currentNode.ilon, currentNode.ilat, node.ilon, node.ilat );
      if ( rc.nogomatch )
      {
        return;
      }

	  if ( link instanceof ScheduledLink )
      {
// System.out.println( "next trip for link: " + link + " at offset " + offsets );		  
		  
        ScheduledLink slink = (ScheduledLink)link;
      	ScheduledLine line = slink.line;
      	
      	// line change delay
      	long delay = 0L;
      	if ( currentLink instanceof ScheduledLink )
      	{
      	  delay = ((ScheduledLink)currentLink).line == line ? 0L : (long)(rc.changetime * 1000.); // 3 minutes
      	}
        long changePenalty = delay > 0 ? 60000L : 0L;
      	
		List<ScheduledDeparture> nextDepartures = line.getScheduledDepartures( slink.indexInLine, time0 + trip.arrival + delay, offsets );
		for( ScheduledDeparture nextDeparture : nextDepartures )
		{
		  ScheduledTrip nextTrip = new ScheduledTrip( nextDeparture.offsets, link, currentNode, trip );
		  long waitTime = nextDeparture.waitTime + delay;
		  long rideTime = nextDeparture.rideTime;
				
	      nextTrip.cost = trip.cost + (int)( ( rideTime + changePenalty + waitTime*rc.waittimeadjustment ) * rc.cost1speed / 3600. ); // 160ms / meter = 22km/h
	      nextTrip.departure = trip.arrival + waitTime;
	      nextTrip.arrival = nextTrip.departure + rideTime;

			addToOpenSet( nextTrip );

//	      System.out.println( "found: " + nextTrip );		  
	    }
      }
      else if ( link.isWayLink() )
      {
    	  // get costfactor
          rc.expctxWay.evaluate( link.isReverse(currentNode), link.descriptionBitmap, null );
    	    
          // *** penalty for distance
          float costfactor = rc.expctxWay.getCostfactor();
          if ( costfactor > 9999. )
          {
            return;
          }
          int waycost = (int)(distance * costfactor + 0.5f);

          // *** add initial cost if factor changed
          float costdiff = costfactor - trip.lastcostfactor;
          if ( costdiff > 0.0005 || costdiff < -0.0005 )
          {
              waycost += (int)rc.expctxWay.getInitialcost();
          }

          
          if ( node.getNodeDecsription() != null )
          {
              rc.expctxNode.evaluate( rc.expctxWay.getNodeAccessGranted() != 0. , node.getNodeDecsription(), null );
              float initialcost = rc.expctxNode.getInitialcost();
              if ( initialcost >= 1000000. )
              {
                return;
              }
              waycost += (int)initialcost;
          }

          // *** penalty for turning angles
          if ( trip.originNode != null )
          {
            // penalty proportional to direction change
        	double cos = rc.calcCosAngle( trip.originNode.ilon, trip.originNode.ilat, currentNode.ilon, currentNode.ilat, node.ilon, node.ilat );
            int turncost = (int)(cos * rc.expctxWay.getTurncost() + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
            waycost += turncost;
          }

          ScheduledTrip nextTrip = new ScheduledTrip( offsets, link, currentNode, trip );

		  // *** penalty for elevation
          short ele2 = node.selev;
          short ele1 = trip.selev;
          int elefactor = 250000;
          if ( ele2 == Short.MIN_VALUE ) ele2 = ele1;
          nextTrip.selev = ele2;
          if ( ele1 != Short.MIN_VALUE )
          {
            nextTrip.ehbd = trip.ehbd + (ele1 - ele2)*elefactor - distance * rc.downhillcutoff;
            nextTrip.ehbu = trip.ehbu + (ele2 - ele1)*elefactor - distance * rc.uphillcutoff;
          }

          if ( nextTrip.ehbd > rc.elevationpenaltybuffer )
          {
             int excess = nextTrip.ehbd - rc.elevationpenaltybuffer;
             int reduce = distance * rc.elevationbufferreduce;
             if ( reduce > excess )
             {
               reduce = excess;
             }
             excess = nextTrip.ehbd - rc.elevationmaxbuffer;
             if ( reduce < excess )
             {
               reduce = excess;
             }
             nextTrip.ehbd -= reduce;
             if ( rc.downhillcostdiv > 0 )
             {
               int elevationCost = reduce/rc.downhillcostdiv;
               waycost += elevationCost;
             }
          }
          else if ( nextTrip.ehbd < 0 )
          {
        	  nextTrip.ehbd = 0;
          }

          if ( nextTrip.ehbu > rc.elevationpenaltybuffer )
          {
            int excess = nextTrip.ehbu - rc.elevationpenaltybuffer;
            int reduce = distance * rc.elevationbufferreduce;
            if ( reduce > excess )
            {
              reduce = excess;
            }
            excess = nextTrip.ehbu - rc.elevationmaxbuffer;
            if ( reduce < excess )
            {
              reduce = excess;
            }
            nextTrip.ehbu -= reduce;
            if ( rc.uphillcostdiv > 0 )
            {
              int elevationCost = reduce/rc.uphillcostdiv;
              waycost += elevationCost;
            }
          }
          else if ( nextTrip.ehbu < 0 )
          {
        	  nextTrip.ehbu = 0;
          }
          
      	  nextTrip.lastcostfactor = costfactor;
		  nextTrip.cost = trip.cost + (int)(waycost*rc.additionalcostfactor + 0.5);
		  nextTrip.departure = trip.arrival;
		  nextTrip.arrival = nextTrip.departure + (long) ( waycost * 3600. / rc.cost1speed ); // 160ms / meter = 22km/h

			addToOpenSet( nextTrip );
      }
      else // connecting link
      {
		  ScheduledTrip nextTrip = new ScheduledTrip( offsets, link, currentNode, trip );

			long delay = (long)(rc.buffertime * 1000.); // 2 min
		    nextTrip.cost = trip.cost + (int)( delay*rc.waittimeadjustment * rc.cost1speed / 3600. );
			nextTrip.departure = trip.arrival;
			nextTrip.arrival = nextTrip.departure + delay; 
			
			addToOpenSet( nextTrip );
        }
	}
	
	private OsmTrack compileTrip( ScheduledTrip trip, int offset )
	{
	  OsmTrack track = new OsmTrack();
	  track.iternity = new ArrayList<String>();
      ScheduledTrip current = trip;
      ScheduledLine lastLine = new ScheduledLine();
      ScheduledLine dummyLine = new ScheduledLine();
      List<ScheduledTrip> list = new ArrayList<ScheduledTrip>();
      
	  int distance = 0;

	  ScheduledTrip itrip = null;
	  
	  String profile = extractProfile( rc.localFunction );

      OsmNodeP nextNode = null;      
      while( current != null )
      {
System.out.println( "trip=" + current );
    	  OsmNodeP node = current.getTargetNode();
    	  OsmPathElement pe = OsmPathElement.create(node.ilon, node.ilat, node.selev, null, false );
    	  track.addNode(pe);

	      if ( nextNode != null )
	      {
	        distance += node.calcDistance( nextNode );
	      }
    	  
    	  boolean isScheduled = current.link instanceof ScheduledLink;
    	  boolean isConnection = current.link.descriptionBitmap == null && !isScheduled;
    	  ScheduledLine line = isScheduled ? ((ScheduledLink)current.link).line : isConnection ? dummyLine : null;

    	    if ( line != lastLine && !isConnection )
    	    {
    	      itrip = new ScheduledTrip();
    	      itrip.departure = current.departure;
    	      itrip.arrival = current.arrival;
    	      itrip.originNode = current.originNode;
    	      itrip.link = current.link;
    	      
    	      if ( isScheduled && list.size() > 0 )
    	      {
    	    	  list.get( list.size()-1 ).originNode = current.getTargetNode();
    	      }
        	  list.add(itrip);
    	    }
    	    else if ( itrip != null && !isConnection )
    	    {
        	  itrip.departure = current.departure;
        	  itrip.originNode = current.originNode;
    	   }
    	   lastLine = line;
    	  current = current.origin;
    	  nextNode = node;
      }
	  track.distance = distance;
	  track.cost = trip.cost;

      for( int i=list.size()-1; i>=0; i-- )
      {
    	  current = list.get(i);
    	  String lineName = profile;

    	  boolean isScheduled = current.link instanceof ScheduledLink;
    	  if ( isScheduled )
    	  {
    		  lineName = ((ScheduledLink)current.link).line.name;
    	  }
    	  String stationName = "*position*";
    	  if ( current.originNode instanceof StationNode )
    	  {
		    stationName = ((StationNode)current.originNode).name;
    	  }
    	  String nextStationName = "*position*";
    	  if ( i > 0 && list.get(i-1).originNode instanceof StationNode )
    	  {
    		  nextStationName = ((StationNode)list.get(i-1).originNode).name;
    	  }
    	  {
      		Date d0 = new Date( time0 + 60000L * offset + current.departure );
    		Date d1 = new Date( time0 + 60000L * offset + current.arrival );
	        if ( track.iternity.size() > 0 ) track.iternity.add( "" );
	        track.iternity.add( "depart: " + d0 + " " + stationName );
	        track.iternity.add( "                                     --- " + lineName + " ---" );
	        track.iternity.add( "arrive: " + d1 + " " + nextStationName  );
    	  }
      }

      return track;
	}
	
	private String extractProfile( String s )
	{
		int idx = s.lastIndexOf( '/' );
		if ( idx >= 0 ) s = s.substring(  idx+1 );
	    idx = s.indexOf( '.' );
		if ( idx >= 0 ) s = s.substring(  0,idx );
        return s;		
	}
}
