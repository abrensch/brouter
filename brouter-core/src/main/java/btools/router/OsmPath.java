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

abstract class OsmPath implements OsmLinkHolder
{
  /**
   * The cost of that path (a modified distance)
   */
  public int cost = 0;

  // the elevation assumed for that path can have a value
  // if the corresponding node has not
  public short selev;

  public int airdistance = 0; // distance to endpos
  
  protected OsmNode sourceNode;
  protected OsmNode targetNode;

  protected OsmLink link;
  public OsmPathElement originElement;
  public OsmPathElement myElement;

  protected float traffic;

  private OsmLinkHolder nextForLink = null;

  public int treedepth = 0;

  // the position of the waypoint just before
  // this path position (for angle calculation)
  public int originLon;
  public int originLat;

  public double startAngle;

  // the classifier of the segment just before this paths position
  protected float lastClassifier;
  protected float lastInitialCost;

  protected int priorityclassifier;

  private static final int PATH_START_BIT = 1;
  private static final int CAN_LEAVE_DESTINATION_BIT = 2;
  private static final int IS_ON_DESTINATION_BIT = 4;
  private static final int HAD_DESTINATION_START_BIT = 8;
  protected int bitfield = PATH_START_BIT;

  private boolean getBit( int mask )
  {
    return (bitfield & mask ) != 0;
  }

  private void setBit( int mask, boolean bit )
  {
    if ( getBit( mask ) != bit )
    {
      bitfield ^= mask;
    }
  }

  public boolean didEnterDestinationArea()
  {
    return !getBit( HAD_DESTINATION_START_BIT ) && getBit( IS_ON_DESTINATION_BIT );
  }

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

  public void init( OsmLink link )
  {
    this.link = link;
    targetNode = link.getTarget( null );
    selev = targetNode.getSElev();

    originLon = -1;
    originLat = -1;
  }

  public void init( OsmPath origin, OsmLink link, OsmTrack refTrack, boolean detailMode, RoutingContext rc )
  {
    if ( origin.myElement == null )
    {
      origin.myElement = OsmPathElement.create( origin, rc.countTraffic );
    }
    this.originElement = origin.myElement;
    this.link = link;
    this.sourceNode = origin.targetNode;
    this.targetNode = link.getTarget( sourceNode );
    this.cost = origin.cost;
    this.lastClassifier = origin.lastClassifier;
    this.lastInitialCost = origin.lastInitialCost;
    this.bitfield = origin.bitfield;
    init( origin );
    addAddionalPenalty(refTrack, detailMode, origin, link, rc );
  }
  
  protected abstract void init( OsmPath orig );

  protected abstract void resetState();


  protected void addAddionalPenalty(OsmTrack refTrack, boolean detailMode, OsmPath origin, OsmLink link, RoutingContext rc )
  {
    byte[] description = link.descriptionBitmap;
    if ( description == null ) throw new IllegalArgumentException( "null description for: " + link );

    boolean recordTransferNodes = detailMode || rc.countTraffic;

    rc.nogomatch = false;

    // extract the 3 positions of the first section
    int lon0 = origin.originLon;
    int lat0 = origin.originLat;

    int lon1 = sourceNode.getILon();
    int lat1 = sourceNode.getILat();
    short ele1 = origin.selev;

    int linkdisttotal = 0;

    message = detailMode ? new MessageData() : null;

    boolean isReverse = link.isReverse( sourceNode );

    // evaluate the way tags
    rc.expctxWay.evaluate( rc.inverseDirection ^ isReverse, description );


    // calculate the costfactor inputs
    float costfactor = rc.expctxWay.getCostfactor();
    boolean isTrafficBackbone = cost == 0 && rc.expctxWay.getIsTrafficBackbone() > 0.f;
    int lastpriorityclassifier = priorityclassifier;
    priorityclassifier = (int)rc.expctxWay.getPriorityClassifier();

    // *** add initial cost if the classifier changed
    float newClassifier = rc.expctxWay.getInitialClassifier();
    float newInitialCost = rc.expctxWay.getInitialcost();
    float classifierDiff = newClassifier - lastClassifier;
    if ( newClassifier != 0. && lastClassifier != 0. && ( classifierDiff > 0.0005 || classifierDiff < -0.0005 ) )
    {
      float initialcost = rc.inverseDirection ? lastInitialCost : newInitialCost;
      if ( initialcost >= 1000000. )
      {
        cost = -1;
        return;
      }

      int iicost = (int)initialcost;
      if ( message != null )
      {
        message.linkinitcost += iicost;
      }
      cost += iicost;
    }
    lastClassifier = newClassifier;
    lastInitialCost = newInitialCost;

    // *** destination logic: no destination access in between 
    int classifiermask = (int)rc.expctxWay.getClassifierMask();
    boolean newDestination = (classifiermask & 64) != 0;
    boolean oldDestination = getBit( IS_ON_DESTINATION_BIT );
    if ( getBit( PATH_START_BIT ) )
    {
      setBit( PATH_START_BIT, false );
      setBit( CAN_LEAVE_DESTINATION_BIT, newDestination );
      setBit( HAD_DESTINATION_START_BIT, newDestination );
    }
    else
    {
      if ( oldDestination && !newDestination )
      {
        if ( getBit( CAN_LEAVE_DESTINATION_BIT ) )
        {
          setBit( CAN_LEAVE_DESTINATION_BIT, false );
        }
        else
        {
          cost = -1;
          return;
        }
      }
    }
    setBit( IS_ON_DESTINATION_BIT, newDestination );
    

    OsmTransferNode transferNode = link.geometry == null ? null
                  : rc.geometryDecoder.decodeGeometry( link.geometry, sourceNode, targetNode, isReverse );

    for(int nsection=0; ;nsection++)
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

      boolean isStartpoint = lon0 == -1 && lat0 == -1;

      // check turn restrictions (n detail mode (=final pass) no TR to not mess up voice hints)
      if ( nsection == 0 && rc.considerTurnRestrictions && !detailMode&& !isStartpoint )
      {
        boolean hasAnyPositive = false;
        boolean hasPositive = false;
        boolean hasNegative = false;
        TurnRestriction tr = sourceNode.firstRestriction;
        while( tr != null )
        {
          if ( ( tr.exceptBikes() && rc.bikeMode ) || tr.exceptMotorcars() && rc.carMode )
          {
            tr = tr.next;
            continue;
          }
          int fromLon = rc.inverseDirection ? lon2 : lon0;
          int fromLat = rc.inverseDirection ? lat2 : lat0;
          int toLon = rc.inverseDirection ? lon0 : lon2;
          int toLat = rc.inverseDirection ? lat0 : lat2;
          if ( tr.fromLon == fromLon && tr.fromLat == fromLat )
          {
            if ( tr.isPositive )
            {
              hasAnyPositive = true;
            }
            if ( tr.toLon == toLon && tr.toLat == toLat )
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
          if ( rc.considerTurnRestrictions && !detailMode )
          {
            cost = -1;
            return;
          }
        }
      }

      // if recording, new MessageData for each section (needed for turn-instructions)
      if ( message != null && message.wayKeyValues != null )
      {
        originElement.message = message;
        message = new MessageData();
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
          // we just start here, reset everything
          cost = 0;
          resetState();
          lon0 = -1; // reset turncost-pipe
          lat0 = -1;
          isStartpoint = true;

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

      if ( message != null )
      {
        message.linkdist += dist;
      }
      linkdisttotal += dist;

      // apply a start-direction if appropriate (by faking the origin position)
      if ( isStartpoint )
      {
        if ( rc.startDirectionValid )
        {
          double dir = rc.startDirection.intValue() / 57.29578;
          lon0 = lon1 - (int) ( 1000. * Math.sin( dir ) / rc.getCosLat() );
          lat0 = lat1 - (int) ( 1000. * Math.cos( dir ) );
        }
        else
        {
          lon0 = lon1 - (lon2-lon1);
          lat0 = lat1 - (lat2-lat1);
        }
      }
      double angle = rc.calcAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
      double cosangle = rc.getCosAngle();

      if ( nsection == 0 )
      {
        startAngle = angle;
      }
      else
      {
        if ( rc.suspectNodes != null &&  priorityclassifier > 20 && ! ( angle > -130. && angle < 130. ) )
        {
          long id1 = ( (long) lon1 ) << 32 | lat1;
          SuspectInfo.addSuspect(rc.suspectNodes, id1, priorityclassifier, SuspectInfo.TRIGGER_SHARP_LINK );
        } 
      }

      // *** elevation stuff
      double delta_h = 0.;
      if ( ele2 == Short.MIN_VALUE ) ele2 = ele1;
      if ( ele1 != Short.MIN_VALUE )
      {
        delta_h = (ele2 - ele1)/4.;
        if ( rc.inverseDirection )
        {
          delta_h = -delta_h;
        }
      }

      double elevation = ele2 == Short.MIN_VALUE ? 100. : ele2/4.;

      double sectionCost = processWaySection( rc, dist, delta_h, elevation, angle, cosangle, isStartpoint, nsection, lastpriorityclassifier );
      if ( ( sectionCost < 0. || costfactor > 9996. && !detailMode ) || sectionCost + cost >= 2000000000. )
      {
        if ( costfactor == 9998. && priorityclassifier == lastpriorityclassifier )
        {
          rc.foundWayBlock = SuspectInfo.addTrigger( rc.foundWayBlock, priorityclassifier, SuspectInfo.TRIGGER_BAD_ACCESS );
        }
        if ( costfactor == 9997. )
        {
          rc.foundWayBlock = SuspectInfo.addTrigger( rc.foundWayBlock, priorityclassifier, SuspectInfo.TRIGGER_UNK_ACCESS );
        }
        cost = -1;
        return;
      }

      if ( isTrafficBackbone )
      {
        sectionCost = 0.;
      }

      cost += (int)sectionCost;

      // calculate traffic
      if ( rc.countTraffic )
      {
        int minDist = (int)rc.trafficSourceMinDist;
        int cost2 = cost < minDist ? minDist : cost;
        traffic += dist*rc.expctxWay.getTrafficSourceDensity()*Math.pow(cost2/10000.f,rc.trafficSourceExponent);
      }

      if ( message != null )
      {
        message.turnangle = (float)angle;
        message.time = (float)getTotalTime();
        message.energy = (float)getTotalEnergy();
        message.priorityclassifier = priorityclassifier;
        message.classifiermask = classifiermask;
        message.lon = lon2;
        message.lat = lat2;
        message.ele = ele2;
        message.wayKeyValues = rc.expctxWay.getKeyValueDescription( isReverse, description );
      }

      if ( stopAtEndpoint )
      {
        if ( recordTransferNodes )
        {
          originElement = OsmPathElement.create( rc.ilonshortest, rc.ilatshortest, ele2, originElement, rc.countTraffic );
          originElement.cost = cost;
          if ( message != null )
          {
            originElement.message = message;
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

    // add target-node costs
    double targetCost = processTargetNode( rc );
    if ( targetCost < 0. || targetCost + cost >= 2000000000. )
    {
      if ( rc.suspectNodes != null && priorityclassifier > 20 && rc.inverseDirection == rc.inverseRouting )
      {
        rc.foundNodeBlock = true;
        SuspectInfo.addSuspect( rc.suspectNodes, targetNode.getIdFromPos(), priorityclassifier, SuspectInfo.TRIGGER_NODE_BLOCK );
      }
      cost = -1;
      return;
    }
    cost += (int)targetCost;
  }


  public short interpolateEle( short e1, short e2, double fraction )
  {
    if ( e1 == Short.MIN_VALUE || e2 == Short.MIN_VALUE )
    {
      return Short.MIN_VALUE;
    }
    return (short)( e1*(1.-fraction) + e2*fraction );
  }

  protected abstract double processWaySection( RoutingContext rc, double dist, double delta_h, double elevation, double angle, double cosangle, boolean isStartpoint, int nsection, int lastpriorityclassifier );

  protected abstract double processTargetNode( RoutingContext rc );

  public abstract int elevationCorrection( RoutingContext rc );

  public abstract boolean definitlyWorseThan( OsmPath p, RoutingContext rc );

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

  public double getTotalTime()
  {
    return 0.;
  }
  
  public double getTotalEnergy()
  {
    return 0.;
  }
}
