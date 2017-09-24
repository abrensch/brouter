/**
 * Simple Train Router
 *
 * @author ab
 */
package btools.memrouter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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

  private static List<Iternity> trips = new ArrayList<Iternity>();
  private static long oldChecksum = 0;
  
  private String startEndText()
  {
    return (start == null ? "unmatched" : start.getName() ) + "->" + (end == null ? "unmatched" : end.getName() );
  }

  public OsmTrack findRoute( OsmPos startPos, OsmPos endPos, int alternativeIdx ) throws Exception
  {
    if ( alternativeIdx == -1 ) // lowest cost result
    {
      List<Iternity> singleTrip = _findRoute( startPos, endPos, true );
      if ( singleTrip.isEmpty() )
      {
        if ( linksProcessed + linksReProcessed > 5000000 ) throw new RuntimeException( "5 million links limit reached" );
        else throw new RuntimeException( "no track found! (" + startEndText() + ")" );
      }
      Iternity iternity = singleTrip.get( 0 );
      OsmTrack t = iternity.track;
      t.iternity = iternity.details;
      return t;
    }

    // check for identical params
    long[] nogocheck = rc.getNogoChecksums();
    
    long checksum = nogocheck[0] + nogocheck[1] + nogocheck[2];
    checksum += startPos.getILat() + startPos.getILon() + endPos.getILat() + endPos.getILon();
    checksum += rc.localFunction.hashCode();
    
    if ( checksum != oldChecksum )
    {
      trips = _findRoute( startPos, endPos, false );
      Collections.sort( trips ); // sort by arrival time
      oldChecksum = checksum;
    }

    if ( trips.isEmpty() )
    {
      if ( linksProcessed + linksReProcessed > 5000000 ) throw new RuntimeException( "5 million links limit reached" );
      else throw new RuntimeException( "no track found! (" + startEndText() + ")" );
    }
    
    if ( alternativeIdx == 0 ) // = result overview
    {
      List<String> details = new ArrayList<String>();
      for ( int idx = 0; idx < trips.size(); idx++ )
      {
        if ( idx > 0 ) details.add( "" );
        Iternity iternity = trips.get( idx );
        iternity.appendSummary( details );
      }
      Iternity iternity = trips.get( 0 );
      OsmTrack t = iternity.track;
      t.iternity = details;
      return t;
    }
    
    int idx = alternativeIdx > trips.size() ? trips.size()-1 : alternativeIdx-1;
    Iternity iternity = trips.get( idx );
    OsmTrack t = iternity.track;
    t.iternity = iternity.details;
    return t;
  }    
    
    
  private List<Iternity> _findRoute( OsmPos startPos, OsmPos endPos, boolean fastStop ) throws Exception
  {
    List<Iternity> iternities = new ArrayList<Iternity>();

    start = graph.matchNodeForPosition( startPos, rc.expctxWay, rc.transitonly );
    if ( start == null )
      throw new IllegalArgumentException( "unmatched start: " + startPos );
    end = graph.matchNodeForPosition( endPos, rc.expctxWay, rc.transitonly );
    if ( end == null )
      throw new IllegalArgumentException( "unmatched end: " + endPos );
      
    time0 = System.currentTimeMillis() + (long) ( rc.starttimeoffset * 60000L );
    long minutes0 = ( time0 + 59999L ) / 60000L;
    time0 = minutes0 * 60000L;

    OffsetSet fullSet = OffsetSet.fullSet();
    OffsetSet finishedOffsets = fullSet.emptySet();

    OsmLinkP startLink = new OsmLinkP( null, start );

    ScheduledTrip startTrip = new ScheduledTrip( OffsetSet.fullSet(), startLink, null, null );
    openSet.add( 0, startTrip );
    for ( ;; )
    {
      if ( re.isTerminated() )
      {
        throw new RuntimeException( "operation terminated" );
      }
      if ( linksProcessed + linksReProcessed > 5000000 )
      {
        break;
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

   /*   int maxOffset = (int)((maxarrival - trip.arrival)/60000L);
      OffsetSet offsets = trip.offsets.ensureMaxOffset( maxOffset );
      if ( offsets == null )
        continue;
     */ 
      
      OffsetSet offsets = trip.offsets;

      // check global closure
      offsets = finishedOffsets.filter( offsets );
      if ( offsets == null )
        continue;

/*      offsets = efficientSubset( offsets, trip.adjustedCost );
      if ( offsets == null )
        continue;
*/

      boolean continueOnLineOnly = false;
      // check local closure for the target node:
      if ( currentNode.filterAndCloseNode( offsets, true ) == null )
      {
        continueOnLineOnly = true;
      }

      // check local closure for links:
      offsets = currentLink.filterAndClose( offsets, trip.arrival );
      if ( offsets == null )
        continue;

      // check for arrival
      if ( currentNode == end )
      {
        Iternity iternity = null;
        OffsetSet toffsets = trip.offsets;

        int lastIdx = iternities.size()-1;
        // hack: for equal cost tracks, merge offsets (assuming identical iternity)
        if ( lastIdx >= 0 && iternities.get( lastIdx ).track.cost == trip.cost )
        {
          toffsets = toffsets.add( iternities.get( lastIdx ).offsets );
          iternities.remove( lastIdx );
        }

        for ( int offset = 0; offset < trip.offsets.size(); offset++ )
        {
          if ( trip.offsets.contains( offset ) )
          {
            iternity = compileTrip( trip, offset );
            iternity.offsets = toffsets;
            System.out.println( "---- begin route ------ (cost " + iternity.track.cost + ")" );
            for ( String s : iternity.details )
              System.out.println( s );
            System.out.println( "---- end route ------ (arrival " + new Date( iternity.arrivaltime ) + ")" );
            break; // + plus more offsets..
          }
        }
        finishedOffsets = finishedOffsets.add( offsets );
        if ( iternity != null )
        {
          // tracks come in cost-ascending order, so the arrival time
          // must decrease for the new track to be efficient
//          if ( iternities.isEmpty() || iternities.get( iternities.size() - 1 ).arrivaltime > iternity.arrivaltime )
          if ( efficientSubset( iternities, iternity.offsets, iternity.track.cost ) != null )
          {
            iternities.add( iternity );
            if ( fastStop )
            {
              break;
            }
System.out.println( "*** added track to result list !**** ");
          }
        }
System.out.println( "*** finishedOffsets = " + finishedOffsets );
          
      }
      for ( OsmLinkP link = currentNode.getFirstLink(); link != null; link = link.getNext( currentNode ) )
      {
        if ( continueOnLineOnly )
        {
          if ( ! ( currentLink instanceof ScheduledLink && link instanceof ScheduledLink ) ) continue;
          if ( ((ScheduledLink) currentLink).line != ((ScheduledLink) link ).line )  continue;
        }

/*        // pre-check target closure
        if ( link.getTarget( currentNode ).filterAndCloseNode( offsets, false ) == null )
        {
          continue;
        }
*/
        if ( !rc.transitonly || link instanceof ScheduledLink )
        {
          addNextTripsForLink( trip, currentNode, currentLink, link, offsets, 0 );
        }
      }
    }
    return iternities;
  }

  private OffsetSet efficientSubset( List<Iternity> iternities, OffsetSet offsets, int cost  )
  {
    for( Iternity it : iternities )
    {
       // determine a time-diff from a cost-diff
       int dcost = cost - it.track.cost;
       int dtime = (int) ( dcost * .06 / rc.cost1speed + 1. ); // 22km/h
       offsets = offsets.sweepWith( it.offsets, dtime );
       if ( offsets == null )
       {
         return null;
       }
    }
    return offsets;
  }


  private void addToOpenSet( ScheduledTrip nextTrip )
  {
    int distance = nextTrip.getTargetNode().calcDistance( end );
    nextTrip.adjustedCost = nextTrip.cost + (int) ( distance * rc.pass1coefficient + 0.5 );
    openSet.add( nextTrip.adjustedCost, nextTrip );
  }

  private void addNextTripsForLink( ScheduledTrip trip, OsmNodeP currentNode, OsmLinkP currentLink, OsmLinkP link, OffsetSet offsets, int level )
  {
    OsmNodeP node = link.getTarget( currentNode );
    if ( node == null )
    {
      System.out.println( "ups2: " + link );
      return;
    }

    if ( node == trip.originNode )
    {
      link.filterAndClose( offsets, -1 ); // invalidate reverse link
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
      // System.out.println( "next trip for link: " + link + " at offset " +
      // offsets );

      ScheduledLink slink = (ScheduledLink) link;
      ScheduledLine line = slink.line;

      // line change delay
      long delay = 0L;
      if ( currentLink instanceof ScheduledLink )
      {
        delay = ( (ScheduledLink) currentLink ).line == line ? 0L : (long) ( rc.changetime * 1000. ); // 3 minutes
      }
      long changePenalty = delay > 0 ? 60000L : 0L;

      List<ScheduledDeparture> nextDepartures = line.getScheduledDepartures( slink.indexInLine, time0 + trip.arrival + delay, offsets );
      for ( ScheduledDeparture nextDeparture : nextDepartures )
      {
        ScheduledTrip nextTrip = new ScheduledTrip( nextDeparture.offsets, link, currentNode, trip );
        long waitTime = nextDeparture.waitTime + delay;
        long rideTime = nextDeparture.rideTime;

        nextTrip.cost = trip.cost + (int) ( ( rideTime + changePenalty + waitTime * rc.waittimeadjustment ) * rc.cost1speed / 3600. ); // 22km/h
        nextTrip.departure = trip.arrival + waitTime;
        nextTrip.arrival = nextTrip.departure + rideTime;

        addToOpenSet( nextTrip );

        // System.out.println( "found: " + nextTrip );
      }
    }
    else if ( link.isWayLink() )
    {
      // get costfactor
      rc.expctxWay.evaluate( link.isReverse( currentNode ), link.descriptionBitmap );

      // *** penalty for distance
      float costfactor = rc.expctxWay.getCostfactor();
      if ( costfactor > 9999. )
      {
        return;
      }
      int waycost = (int) ( distance * costfactor + 0.5f );

      // *** add initial cost if factor changed
      float costdiff = costfactor - trip.lastcostfactor;
      if ( costdiff > 0.0005 || costdiff < -0.0005 )
      {
        waycost += (int) rc.expctxWay.getInitialcost();
      }

      if ( node.getNodeDecsription() != null )
      {
        rc.expctxNode.evaluate( rc.expctxWay.getNodeAccessGranted() != 0., node.getNodeDecsription() );
        float initialcost = rc.expctxNode.getInitialcost();
        if ( initialcost >= 1000000. )
        {
          return;
        }
        waycost += (int) initialcost;
      }

      // *** penalty for turning angles
      if ( trip.originNode != null )
      {
        // penalty proportional to direction change
        double angle = rc.calcAngle( trip.originNode.ilon, trip.originNode.ilat, currentNode.ilon, currentNode.ilat, node.ilon, node.ilat );
        double cos = 1. - rc.getCosAngle();

        int turncost = (int) ( cos * rc.expctxWay.getTurncost() + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
        waycost += turncost;
      }

      ScheduledTrip nextTrip = new ScheduledTrip( offsets, link, currentNode, trip );

      // *** penalty for elevation
      short ele2 = node.selev;
      short ele1 = trip.selev;
      int elefactor = 250000;
      if ( ele2 == Short.MIN_VALUE )
        ele2 = ele1;
      nextTrip.selev = ele2;
      if ( ele1 != Short.MIN_VALUE )
      {
        nextTrip.ehbd = trip.ehbd + ( ele1 - ele2 ) * elefactor - distance * rc.downhillcutoff;
        nextTrip.ehbu = trip.ehbu + ( ele2 - ele1 ) * elefactor - distance * rc.uphillcutoff;
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
          int elevationCost = reduce / rc.downhillcostdiv;
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
          int elevationCost = reduce / rc.uphillcostdiv;
          waycost += elevationCost;
        }
      }
      else if ( nextTrip.ehbu < 0 )
      {
        nextTrip.ehbu = 0;
      }

      nextTrip.lastcostfactor = costfactor;
      nextTrip.cost = trip.cost + (int) ( waycost * rc.additionalcostfactor + 0.5 );
      nextTrip.departure = trip.arrival;
      nextTrip.arrival = nextTrip.departure + (long) ( waycost * 3600. / rc.cost1speed ); // 22km/h
      addToOpenSet( nextTrip );
    }
    else
    // connecting link
    {
      ScheduledTrip nextTrip = new ScheduledTrip( offsets, link, currentNode, trip );

      long delay = (long) ( rc.buffertime * 1000. ); // 2 min
      nextTrip.cost = trip.cost + (int) ( delay * rc.waittimeadjustment * rc.cost1speed / 3600. );
      nextTrip.departure = trip.arrival;
      nextTrip.arrival = nextTrip.departure + delay;

      addToOpenSet( nextTrip );
    }
  }

  private Iternity compileTrip( ScheduledTrip trip, int offset )
  {
    Iternity iternity = new Iternity();

    OsmTrack track = new OsmTrack();
    ScheduledTrip current = trip;
    ScheduledLine lastLine = new ScheduledLine();
    ScheduledLine dummyLine = new ScheduledLine();
    List<ScheduledTrip> list = new ArrayList<ScheduledTrip>();

    int distance = 0;
    long departure = 0;
    
    ScheduledTrip itrip = null;

    String profile = extractProfile( rc.localFunction );

    SimpleDateFormat df = new SimpleDateFormat( "dd.MM HH:mm" );
    // time0 = df.parse(startTime).getTime();


    OsmNodeP nextNode = null;
    while (current != null)
    {
      departure = current.departure;
    
      // System.out.println( "trip=" + current );
      OsmNodeP node = current.getTargetNode();
      OsmPathElement pe = OsmPathElement.create( node.ilon, node.ilat, node.selev, null, false );
      track.addNode( pe );

      if ( nextNode != null )
      {
        distance += node.calcDistance( nextNode );
      }

      boolean isScheduled = current.link instanceof ScheduledLink;
      boolean isConnection = current.link.descriptionBitmap == null && !isScheduled;
      ScheduledLine line = isScheduled ? ( (ScheduledLink) current.link ).line : isConnection ? dummyLine : null;

      if ( line != lastLine && !isConnection )
      {
        itrip = new ScheduledTrip();
        itrip.departure = current.departure;
        itrip.arrival = current.arrival;
        itrip.originNode = current.originNode;
        itrip.link = current.link;

        if ( isScheduled && list.size() > 0 )
        {
          list.get( list.size() - 1 ).originNode = current.getTargetNode();
        }
        list.add( itrip );
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

    for ( int i = list.size() - 1; i >= 0; i-- )
    {
      current = list.get( i );
      String lineName = profile;

      boolean isScheduled = current.link instanceof ScheduledLink;
      if ( isScheduled )
      {
        lineName = ( (ScheduledLink) current.link ).line.name;
      }
      String stationName = "*position*";
      if ( current.originNode instanceof StationNode )
      {
        stationName = ( (StationNode) current.originNode ).name;
      }
      String nextStationName = "*position*";
      if ( i > 0 && list.get( i - 1 ).originNode instanceof StationNode )
      {
        nextStationName = ( (StationNode) list.get( i - 1 ).originNode ).name;
      }
      if ( i == 0 && current.link.targetNode instanceof StationNode )
      {
        nextStationName = ( (StationNode)current.link.targetNode ).name;
      }
      {
        Date d0 = new Date( time0 + 60000L * offset + current.departure );
        Date d1 = new Date( time0 + 60000L * offset + current.arrival );
        if ( iternity.details.size() > 0 )
          iternity.details.add( "" );
        iternity.details.add( "depart: " + df.format( d0 ) + " " + stationName );
        iternity.details.add( "                    --- " + lineName + " ---" );
        iternity.details.add( "arrive: " + df.format( d1 ) + " " + nextStationName );
        
        if ( !iternity.lines.contains( lineName )  )
        {
          iternity.lines.add( lineName );
        }    
      }
    }

    iternity.track = track;
    iternity.arrivaltime = time0 + 60000L * offset + trip.arrival;
    iternity.departtime = time0 + 60000L * offset + departure;
    
    return iternity;
  }

  private String extractProfile( String s )
  {
    int idx = s.lastIndexOf( '/' );
    if ( idx >= 0 )
      s = s.substring( idx + 1 );
    idx = s.indexOf( '.' );
    if ( idx >= 0 )
      s = s.substring( 0, idx );
    return s;
  }
}
