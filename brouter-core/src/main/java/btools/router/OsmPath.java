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

  // the classifier of the segment just before this paths position
  protected float lastClassifier;

  protected int priorityclassifier;

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
    int classifiermask = (int)rc.expctxWay.getClassifierMask();

    // *** add initial cost if the classifier changed
    float newClassifier = rc.expctxWay.getInitialClassifier();
    float classifierDiff = newClassifier - lastClassifier;
    if ( classifierDiff > 0.0005 || classifierDiff < -0.0005 )
    {
      lastClassifier = newClassifier;
      float initialcost = rc.expctxWay.getInitialcost();
      int iicost = (int)initialcost;
      if ( message != null )
      {
        message.linkinitcost += iicost;
      }
      cost += iicost;
    }

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

      // check turn restrictions (n detail mode (=final pass) no TR to not mess up voice hints)
      if ( nsection == 0 && rc.considerTurnRestrictions && !detailMode )
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
          cost = -1;
          return;
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
      boolean isStartpoint = lon0 == -1 && lat0 == -1;
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


      double sectionCost = processWaySection( rc, dist, delta_h, angle, cosangle, isStartpoint, nsection, lastpriorityclassifier );
      if ( ( sectionCost < 0. || costfactor > 9998. && !detailMode ) || sectionCost + cost >= 2000000000. )
      {
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

  protected abstract double processWaySection( RoutingContext rc, double dist, double delta_h, double angle, double cosangle, boolean isStartpoint, int nsection, int lastpriorityclassifier );

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
