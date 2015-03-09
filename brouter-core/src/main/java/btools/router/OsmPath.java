/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.*;

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
  
  private OsmNode sourcenode;
  private OsmLink link;
  public OsmPathElement originElement;

  private OsmLinkHolder nextForLink = null;

  public int treedepth = 0;

  // the position of the waypoint just before
  // this path position (for angle calculation)
  public int originLon;
  public int originLat;

  // the costfactor of the segment just before this paths position
  public float lastCostfactor;

  public String message;

  OsmPath()
  {
  }

  OsmPath( OsmLink link )
  {
    this();
    this.link = link;
    this.selev = link.targetNode.getSElev();
  }

  OsmPath( OsmNode sourcenode, OsmPath origin, OsmLink link, OsmTrack refTrack, boolean recordTransferNodes, RoutingContext rc )
  {
    this();
    this.originElement = new OsmPathElement( origin );
    this.link = link;
    this.sourcenode = sourcenode;
    this.cost = origin.cost;
    this.ehbd = origin.ehbd;
    this.ehbu = origin.ehbu;
    this.lastCostfactor = origin.lastCostfactor;
    addAddionalPenalty(refTrack, recordTransferNodes, origin, link, rc );
  }

  private void addAddionalPenalty(OsmTrack refTrack, boolean recordTransferNodes, OsmPath origin, OsmLink link, RoutingContext rc )
  {
	if ( link.descriptionBitmap == null ) throw new IllegalArgumentException( "null description for class: " + link.getClass() );
	  
    rc.nogomatch = false;

    // extract the 3 positions of the first section
    int lon0 = origin.originLon;
    int lat0 = origin.originLat;

    OsmNode p1 = origin.link.targetNode;
    int lon1 = p1.getILon();
    int lat1 = p1.getILat();
    short ele1 = origin.selev;

    int linkdisttotal = 0;

    MessageData msgData = new MessageData();

    OsmTransferNode transferNode = link.decodeFirsttransfer();
    OsmNode targetNode = link.targetNode;
    for(;;)
    {
      originLon = lon1;
      originLat = lat1;

      int lon2;
      int lat2;
      short ele2;
      byte[] description;

      if ( transferNode == null )
      {
        lon2 = targetNode.ilon;
        lat2 = targetNode.ilat;
        ele2 = targetNode.selev;
        description = link.descriptionBitmap;
    	if ( description == null ) throw new IllegalArgumentException( "null description for class: " + link.getClass() );
      }
      else
      {
        lon2 = transferNode.ilon;
        lat2 = transferNode.ilat;
        ele2 = transferNode.selev;
        description = transferNode.descriptionBitmap;
    	if ( description == null ) throw new IllegalArgumentException( "null description for class: " + transferNode.getClass() + "/" + link.getClass() + " counterlinkwritten=" + link.counterLinkWritten );
      }

      rc.messageHandler.setCurrentPos( lon2, lat2 );
      boolean sameData = rc.expctxWay.evaluate( link.counterLinkWritten, description, rc.messageHandler );
      
      // if way description changed, store message
      if ( msgData.wayKeyValues != null && !sameData )
      {
        originElement.message = msgData.toMessage();
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
          if ( recordTransferNodes )
          {
            if (  rc.wayfraction > 0. )
            {
              originElement = new OsmPathElement( rc.ilonshortest, rc.ilatshortest, ele2, null );
            }
            else
            {
              originElement = null; // prevent duplicate point
            }
          }
        }
      }

      msgData.linkdist += dist;
      linkdisttotal += dist;


      // *** penalty for turning angles
      if ( origin.originElement != null )
      {
        // penalty proportional to direction change
        double cos = rc.calcCosAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
        int turncost = (int)(cos * rc.expctxWay.getTurncost() + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
        cost += turncost;
        msgData.linkturncost += turncost;
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
           msgData.linkelevationcost += elevationCost;
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
          msgData.linkelevationcost += elevationCost;
        }
      }
      else if ( ehbu < 0 )
      {
        ehbu = 0;
      }

      // *** penalty for distance
      float cfup = rc.expctxWay.getUphillCostfactor();
      float cfdown = rc.expctxWay.getDownhillCostfactor();
      float cf = rc.expctxWay.getCostfactor();

      cfup = cfup == 0.f ? cf : cfup;
      cfdown = cfdown == 0.f ? cf : cfdown;
      
      float costfactor = cfup*upweight + cf*(1.f - upweight - downweight) + cfdown*downweight;
      float fcost = dist * costfactor + 0.5f;
      if ( costfactor > 9999. || fcost + cost >= 2000000000. )
      {
        cost = -1;
        return;
      }
      int waycost = (int)(fcost);
      cost += waycost;

      // *** add initial cost if factor changed
      float newcostfactor = (cfup + cfdown + cf)/3;
      float costdiff = newcostfactor - lastCostfactor;
      if ( costdiff > 0.0005 || costdiff < -0.0005 )
      {
          lastCostfactor = newcostfactor;
          float initialcost = rc.expctxWay.getInitialcost();
          int iicost = (int)initialcost;
          msgData.linkinitcost += iicost;
          cost += iicost;
      }

      if ( recordTransferNodes )
      {
        msgData.costfactor = costfactor;
        msgData.lon = lon2;
        msgData.lat = lat2;
        msgData.ele = ele2;
        msgData.wayKeyValues = rc.expctxWay.getKeyValueDescription( link.counterLinkWritten, description );
      }

      if ( stopAtEndpoint )
      {
        if ( recordTransferNodes )
        {
          originElement = new OsmPathElement( rc.ilonshortest, rc.ilatshortest, ele2, originElement );
          originElement.cost = cost;
          originElement.message = msgData.toMessage();
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
        if ( refTrack != null && refTrack.containsNode( targetNode ) && refTrack.containsNode( origin.link.targetNode ) )
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
        originElement = new OsmPathElement( lon2, lat2, ele2, originElement );
        originElement.cost = cost;
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
        rc.messageHandler.setCurrentPos( targetNode.ilon, targetNode.ilat );
        rc.expctxNode.evaluate( nodeAccessGranted , targetNode.nodeDescription, rc.messageHandler );
        float initialcost = rc.expctxNode.getInitialcost();
        if ( initialcost >= 1000000. )
        {
          cost = -1;
          return;
        }
        int iicost = (int)initialcost;
        msgData.linknodecost += iicost;

        cost += iicost;

        if ( recordTransferNodes )
        {
          msgData.nodeKeyValues = rc.expctxNode.getKeyValueDescription( nodeAccessGranted, targetNode.nodeDescription );
        }
    }

    message = msgData.toMessage();

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
    return sourcenode;
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
