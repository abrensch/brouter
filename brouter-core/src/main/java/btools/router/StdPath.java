/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;
import btools.mapaccess.TurnRestriction;

final class StdPath extends OsmPath
{
  /**
   * The elevation-hysteresis-buffer (0-10 m)
   */
  private int ehbd; // in micrometer
  private int ehbu; // in micrometer

  @Override
  public void init( OsmPath orig )
  {
    StdPath origin = (StdPath)orig;
    this.ehbd = origin.ehbd;
    this.ehbu = origin.ehbu;
  }

  @Override
  protected void resetState()
  {
    ehbd = 0;
    ehbu = 0;
  }

  @Override
  protected double processWaySection( RoutingContext rc, double distance, double delta_h, double elevation, double angle, double cosangle, boolean isStartpoint, int nsection, int lastpriorityclassifier )
  {
    // calculate the costfactor inputs
    float turncostbase = rc.expctxWay.getTurncost();
    float cfup = rc.expctxWay.getUphillCostfactor();
    float cfdown = rc.expctxWay.getDownhillCostfactor();
    float cf = rc.expctxWay.getCostfactor();
    cfup = cfup == 0.f ? cf : cfup;
    cfdown = cfdown == 0.f ? cf : cfdown;

    int dist = (int)distance; // legacy arithmetics needs int

    // penalty for turning angle
    int turncost = (int)((1.-cosangle) * turncostbase + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
    if ( message != null )
    {
      message.linkturncost += turncost;
      message.turnangle = (float)angle;
    }

    double sectionCost = turncost;

    // *** penalty for elevation
    // only the part of the descend that does not fit into the elevation-hysteresis-buffers
    // leads to an immediate penalty

    int delta_h_micros = (int)(1000000. * delta_h);
    ehbd += -delta_h_micros - dist * rc.downhillcutoff;
    ehbu +=  delta_h_micros - dist * rc.uphillcutoff;

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
        sectionCost += elevationCost;
        if ( message != null )
        {
          message.linkelevationcost += elevationCost;
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
        sectionCost += elevationCost;
        if ( message != null )
        {
          message.linkelevationcost += elevationCost;
        }
      }
    }
    else if ( ehbu < 0 )
    {
      ehbu = 0;
    }

    // get the effective costfactor (slope dependent)
    float costfactor = cfup*upweight + cf*(1.f - upweight - downweight) + cfdown*downweight;

    if ( message != null )
    {
      message.costfactor = costfactor;
    }

    sectionCost += dist * costfactor + 0.5f;
      
    return sectionCost;
  }

  @Override
  protected double processTargetNode( RoutingContext rc )
  {
    // finally add node-costs for target node
    if ( targetNode.nodeDescription != null )
    {
      boolean nodeAccessGranted = rc.expctxWay.getNodeAccessGranted() != 0.;
      rc.expctxNode.evaluate( nodeAccessGranted , targetNode.nodeDescription );
      float initialcost = rc.expctxNode.getInitialcost();
      if ( initialcost >= 1000000. )
      {
        return -1.;
      }
      if ( message != null )
      {
        message.linknodecost += (int)initialcost;
        message.nodeKeyValues = rc.expctxNode.getKeyValueDescription( nodeAccessGranted, targetNode.nodeDescription );
      }
      return initialcost;
    }
    return 0.;
  }


//  @Override
  protected void xxxaddAddionalPenalty(OsmTrack refTrack, boolean detailMode, OsmPath origin, OsmLink link, RoutingContext rc )
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
      boolean checkTRs = false;
      if ( isFirstSection )
      {
        isFirstSection = false;

        // TODO: TRs for inverse routing would need inverse TR logic,
        // inverse routing for now just for target island check, so don't care (?)
        // in detail mode (=final pass) no TR to not mess up voice hints
        checkTRs = rc.considerTurnRestrictions && !rc.inverseDirection && !detailMode;
      }

      if ( checkTRs )
      {
        boolean hasAnyPositive = false;
        boolean hasPositive = false;
        boolean hasNegative = false;
        TurnRestriction tr = sourceNode.firstRestriction;
        while( tr != null )
        {
          boolean trValid = ! (tr.exceptBikes() && rc.bikeMode);
          if ( trValid && tr.fromLon == lon0 && tr.fromLat == lat0 )
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
      boolean stopAtEndpoint = false;
      if ( rc.shortestmatch )
      {
        if ( rc.isEndpoint )
        {
          stopAtEndpoint = true;
          ele2 = interpolateEle( ele1, ele2, rc.wayfraction );
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
              ele1 = interpolateEle( ele1, ele2, 1. - rc.wayfraction );
              originElement = OsmPathElement.create( rc.ilonshortest, rc.ilatshortest, ele1, null, rc.countTraffic );
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
        double angle = rc.calcAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
        double cos = 1. - rc.getCosAngle();
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

      int elefactor = 250000;
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

  @Override
  public int elevationCorrection( RoutingContext rc )
  {
    return ( rc.downhillcostdiv > 0 ? ehbd/rc.downhillcostdiv : 0 )
         + ( rc.uphillcostdiv > 0 ? ehbu/rc.uphillcostdiv : 0 );
  }

  @Override
  public boolean definitlyWorseThan( OsmPath path, RoutingContext rc )
  {
    StdPath p = (StdPath)path;

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
 
}
