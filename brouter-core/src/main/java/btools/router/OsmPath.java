/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import java.io.IOException;

import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmLinkHolder;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;
import btools.mapaccess.TurnRestriction;

final class OsmPath implements OsmLinkHolder
{
  /**
   * The cost of that path (a modified distance)
   */
  public int cost = 0;

  /**
   * The elevation-hysteresis-buffer (0-10 m)
   */
  private int ehbd; // in micrometer
  private int ehbu; // in micrometer

  // the elevation assumed for that path can have a value
  // if the corresponding node has not
  public short selev;

  public int airdistance = 0; // distance to endpos
  
  private OsmNode sourceNode;
  private OsmNode targetNode;

  private OsmLink link;
  public OsmPathElement originElement;
  public OsmPathElement myElement;

  private float traffic;

  private OsmLinkHolder nextForLink = null;

  public int treedepth = 0;

  // the position of the waypoint just before
  // this path position (for angle calculation)
  public int originLon;
  public int originLat;

  // the classifier of the segment just before this paths position
  public float lastClassifier;

  public MessageData message;

  public void unregisterUpTree( RoutingContext rc )
  {
    try
    {
      OsmPathElement pe = originElement;
      while( pe instanceof OsmPathElementWithTraffic && ((OsmPathElementWithTraffic)pe).unregister(rc) )
      {
        pe = pe.origin;
      }
    }
    catch( IOException ioe )
    {
      throw new RuntimeException( ioe );
    }
  }

  public void registerUpTree()
  {
    if ( originElement instanceof OsmPathElementWithTraffic )
    {
      OsmPathElementWithTraffic ot = (OsmPathElementWithTraffic)originElement;
      ot.register();
      ot.addTraffic( traffic );
    }
  }

  OsmPath()
  {
  }

  OsmPath( OsmLink link )
  {
    this();
    this.link = link;
    targetNode = link.getTarget( null );
    selev = targetNode.getSElev();

    originLon = -1;
    originLat = -1;
  }

  OsmPath( OsmPath origin, OsmLink link, OsmTrack refTrack, boolean detailMode, RoutingContext rc )
  {
    this();
    if ( origin.myElement == null )
    {
      origin.myElement = OsmPathElement.create( origin, rc.countTraffic );
    }
    this.originElement = origin.myElement;
    this.link = link;
    this.sourceNode = origin.targetNode;
    this.targetNode = link.getTarget( sourceNode );
    this.cost = origin.cost;
    this.ehbd = origin.ehbd;
    this.ehbu = origin.ehbu;
    this.lastClassifier = origin.lastClassifier;
    addAddionalPenalty(refTrack, detailMode, origin, link, rc );
  }

  private void addAddionalPenalty(OsmTrack refTrack, boolean detailMode, OsmPath origin, OsmLink link, RoutingContext rc )
  {
    byte[] description = link.descriptionBitmap;
	  if ( description == null ) throw new IllegalArgumentException( "null description for: " + link );

    boolean recordTransferNodes = detailMode || rc.countTraffic;
    boolean recordMessageData = detailMode;

    rc.nogomatch = false;

    // extract the 3 positions of the first section
    int lon0 = origin.originLon;
    int lat0 = origin.originLat;

    OsmNode p1 = sourceNode;
    int lon1 = p1.getILon();
    int lat1 = p1.getILat();
    short ele1 = origin.selev;

    int linkdisttotal = 0;

    MessageData msgData = recordMessageData ? new MessageData() : null;

    boolean isReverse = link.isReverse( sourceNode );

    // evaluate the way tags
    rc.expctxWay.evaluate( rc.inverseDirection ^ isReverse, description );

    // calculate the costfactor inputs
    boolean isTrafficBackbone = cost == 0 && rc.expctxWay.getIsTrafficBackbone() > 0.f;
    float turncostbase = rc.expctxWay.getTurncost();
    float cfup = rc.expctxWay.getUphillCostfactor();
    float cfdown = rc.expctxWay.getDownhillCostfactor();
    float cf = rc.expctxWay.getCostfactor();
    cfup = cfup == 0.f ? cf : cfup;
    cfdown = cfdown == 0.f ? cf : cfdown;

    // *** add initial cost if the classifier changed
    float newClassifier = rc.expctxWay.getInitialClassifier();
    if ( newClassifier == 0. )
    {
      newClassifier = (cfup + cfdown + cf)/3;
    }
    float classifierDiff = newClassifier - lastClassifier;
    if ( classifierDiff > 0.0005 || classifierDiff < -0.0005 )
    {
      lastClassifier = newClassifier;
      float initialcost = rc.expctxWay.getInitialcost();
      int iicost = (int)initialcost;
      if ( recordMessageData )
      {
        msgData.linkinitcost += iicost;
      }
      cost += iicost;
    }

//    OsmTransferNode transferNode = link.decodeGeometry( p1, rc.byteDataReaderGeometry, rc.transferNodeCache  );

    OsmTransferNode transferNode = link.geometry == null ? null
                  : rc.geometryDecoder.decodeGeometry( link.geometry, p1, targetNode, isReverse );

    boolean isFirstSection = true;

    for(;;)
    {
      originLon = lon1;
      originLat = lat1;

      int lon2;
      int lat2;
      short ele2;

      if ( transferNode == null )
      {
        lon2 = targetNode.ilon;
        lat2 = targetNode.ilat;
        ele2 = targetNode.selev;
      }
      else
      {
        lon2 = transferNode.ilon;
        lat2 = transferNode.ilat;
        ele2 = transferNode.selev;
      }

      // check turn restrictions: do we have one with that origin?
      if ( isFirstSection && rc.considerTurnRestrictions )
      {
        isFirstSection = false;
        boolean hasAnyPositive = false;
        boolean hasPositive = false;
        boolean hasNegative = false;
        TurnRestriction tr = sourceNode.firstRestriction;
        while( tr != null )
        {
          if ( tr.fromLon == lon0 && tr.fromLat == lat0 )
          {
            if ( tr.isPositive )
            {
              hasAnyPositive = true;
            }
            if ( tr.toLon == lon2 && tr.toLat == lat2 )
            {
              if ( tr.isPositive )
              {
                hasPositive = true;
              }
              else
              {
                hasNegative = true;
              }
            }
          }
          tr = tr.next;
        }
        if ( !hasPositive && ( hasAnyPositive || hasNegative ) )
        {
          cost = -1;
          return;
        }
      }

      // if recording, new MessageData for each section (needed for turn-instructions)
      if ( recordMessageData && msgData.wayKeyValues != null )
      {
        originElement.message = msgData;
        msgData = new MessageData();
      }

      int dist = rc.calcDistance( lon1, lat1, lon2, lat2 );
      int elefactor = 250000;
      boolean stopAtEndpoint = false;
      if ( rc.shortestmatch )
      {
        elefactor = (int)(elefactor*rc.wayfraction);

        if ( rc.isEndpoint )
        {
          stopAtEndpoint = true;
        }
        else
        {
          // we just start here, reset cost
          cost = 0;
          ehbd = 0;
          ehbu = 0;
          lon0 = -1; // reset turncost-pipe
          lat0 = -1;

          if ( recordTransferNodes )
          {
            if (  rc.wayfraction > 0. )
            {
              originElement = OsmPathElement.create( rc.ilonshortest, rc.ilatshortest, ele2, null, rc.countTraffic );
            }
            else
            {
              originElement = null; // prevent duplicate point
            }
          }
        }
      }

      if ( recordMessageData )
      {
        msgData.linkdist += dist;
      }
      linkdisttotal += dist;

      // apply a start-direction if appropriate (by faking the origin position)
      if ( lon0 == -1 && lat0 == -1 )
      {
        double coslat = Math.cos( ( lat1 - 90000000 ) * 0.00000001234134 );
        if ( rc.startDirectionValid && coslat > 0. )
        {
          double dir = rc.startDirection.intValue() / 57.29578;
          lon0 = lon1 - (int) ( 1000. * Math.sin( dir ) / coslat );
          lat0 = lat1 - (int) ( 1000. * Math.cos( dir ) );
        }
      }

      // *** penalty for turning angles
      if ( !isTrafficBackbone && lon0 != -1 && lat0 != -1 )
      {
        // penalty proportional to direction change
        double cos = rc.calcCosAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
        int actualturncost = (int)(cos * turncostbase + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
        cost += actualturncost;
        if ( recordMessageData )
        {
          msgData.linkturncost += actualturncost;
          msgData.turnangle = (float)rc.calcAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
        }
      }

      // *** penalty for elevation (penalty is for descend! in a way that slow descends give no penalty)
      // only the part of the descend that does not fit into the elevation-hysteresis-buffer
      // leads to an immediate penalty

      if ( ele2 == Short.MIN_VALUE ) ele2 = ele1;
      if ( ele1 != Short.MIN_VALUE )
      {
        ehbd += (ele1 - ele2)*elefactor - dist * rc.downhillcutoff;
        ehbu += (ele2 - ele1)*elefactor - dist * rc.uphillcutoff;
      }

      float downweight = 0.f;
      if ( ehbd > rc.elevationpenaltybuffer )
      {
         downweight = 1.f;

         int excess = ehbd - rc.elevationpenaltybuffer;
         int reduce = dist * rc.elevationbufferreduce;
         if ( reduce > excess )
         {
           downweight = ((float)excess)/reduce;
           reduce = excess;
         }
         excess = ehbd - rc.elevationmaxbuffer;
         if ( reduce < excess )
         {
           reduce = excess;
         }
         ehbd -= reduce;
         if ( rc.downhillcostdiv > 0 )
         {
           int elevationCost = reduce/rc.downhillcostdiv;
           cost += elevationCost;
           if ( recordMessageData )
           {
             msgData.linkelevationcost += elevationCost;
           }
         }
      }
      else if ( ehbd < 0 )
      {
        ehbd = 0;
      }

      float upweight = 0.f;
      if ( ehbu > rc.elevationpenaltybuffer )
      {
        upweight = 1.f;

        int excess = ehbu - rc.elevationpenaltybuffer;
        int reduce = dist * rc.elevationbufferreduce;
        if ( reduce > excess )
        {
          upweight = ((float)excess)/reduce;
          reduce = excess;
        }
        excess = ehbu - rc.elevationmaxbuffer;
        if ( reduce < excess )
        {
          reduce = excess;
        }
        ehbu -= reduce;
        if ( rc.uphillcostdiv > 0 )
        {
          int elevationCost = reduce/rc.uphillcostdiv;
          cost += elevationCost;
          if ( recordMessageData )
          {
            msgData.linkelevationcost += elevationCost;
          }
        }
      }
      else if ( ehbu < 0 )
      {
        ehbu = 0;
      }

      // get the effective costfactor (slope dependent)
      float costfactor = cfup*upweight + cf*(1.f - upweight - downweight) + cfdown*downweight;
      if ( isTrafficBackbone )
      {
        costfactor = 0.f;
      }

      float fcost = dist * costfactor + 0.5f;
      if ( ( costfactor > 9998. && !detailMode ) || fcost + cost >= 2000000000. )
      {
        cost = -1;
        return;
      }
      int waycost = (int)(fcost);
      cost += waycost;

      // calculate traffic
      if ( rc.countTraffic )
      {
        int minDist = (int)rc.trafficSourceMinDist;
        int cost2 = cost < minDist ? minDist : cost;
        traffic += dist*rc.expctxWay.getTrafficSourceDensity()*Math.pow(cost2/10000.f,rc.trafficSourceExponent);
      }

      if ( recordMessageData )
      {
        msgData.costfactor = costfactor;
        msgData.priorityclassifier = (int)rc.expctxWay.getPriorityClassifier();
        msgData.classifiermask = (int)rc.expctxWay.getClassifierMask();
        msgData.lon = lon2;
        msgData.lat = lat2;
        msgData.ele = ele2;
        msgData.wayKeyValues = rc.expctxWay.getKeyValueDescription( isReverse, description );
      }

      if ( stopAtEndpoint )
      {
        if ( recordTransferNodes )
        {
          originElement = OsmPathElement.create( rc.ilonshortest, rc.ilatshortest, ele2, originElement, rc.countTraffic );
          originElement.cost = cost;
          if ( recordMessageData )
          {
            originElement.message = msgData;
          }
        }
        if ( rc.nogomatch )
        {
          cost = -1;
        }
        return;
      }

      if ( transferNode == null )
      {
        // *** penalty for being part of the reference track
        if ( refTrack != null && refTrack.containsNode( targetNode ) && refTrack.containsNode( sourceNode ) )
        {
          int reftrackcost = linkdisttotal;
          cost += reftrackcost;
        }
        selev = ele2;
        break;
      }
      transferNode = transferNode.next;

      if ( recordTransferNodes )
      {
        originElement = OsmPathElement.create( lon2, lat2, ele2, originElement, rc.countTraffic );
        originElement.cost = cost;
        originElement.addTraffic( traffic );
        traffic = 0;
      }
      lon0 = lon1;
      lat0 = lat1;
      lon1 = lon2;
      lat1 = lat2;
      ele1 = ele2;

    }

    // check for nogo-matches (after the *actual* start of segment)
    if ( rc.nogomatch )
    {
        cost = -1;
        return;
    }

    // finally add node-costs for target node
    if ( targetNode.nodeDescription != null )
    {
        boolean nodeAccessGranted = rc.expctxWay.getNodeAccessGranted() != 0.;
        rc.expctxNode.evaluate( nodeAccessGranted , targetNode.nodeDescription );
        float initialcost = rc.expctxNode.getInitialcost();
        if ( initialcost >= 1000000. )
        {
          cost = -1;
          return;
        }
        int iicost = (int)initialcost;

        cost += iicost;

        if ( recordMessageData )
        {
          msgData.linknodecost += iicost;
          msgData.nodeKeyValues = rc.expctxNode.getKeyValueDescription( nodeAccessGranted, targetNode.nodeDescription );
        }
    }
    if ( recordMessageData )
    {
      message = msgData;
    }

  }

  public int elevationCorrection( RoutingContext rc )
  {
    return ( rc.downhillcostdiv > 0 ? ehbd/rc.downhillcostdiv : 0 )
         + ( rc.uphillcostdiv > 0 ? ehbu/rc.uphillcostdiv : 0 );
  }

  public boolean definitlyWorseThan( OsmPath p, RoutingContext rc )
  {
	  int c = p.cost;
	  if ( rc.downhillcostdiv > 0 )
	  {
	    int delta = p.ehbd - ehbd;
	    if ( delta > 0 ) c += delta/rc.downhillcostdiv;
	  }
	  if ( rc.uphillcostdiv > 0 )
	  {
	    int delta = p.ehbu - ehbu;
	    if ( delta > 0 ) c += delta/rc.uphillcostdiv;
	  }
	  
	  return cost > c;
  }
 

  public OsmNode getSourceNode()
  {
    return sourceNode;
  }

  public OsmNode getTargetNode()
  {
    return targetNode;
  }

  public OsmLink getLink()
  {
    return link;
  }


  public void setNextForLink( OsmLinkHolder holder )
  {
    nextForLink = holder;
  }

  public OsmLinkHolder getNextForLink()
  {
    return nextForLink;
  }
}
