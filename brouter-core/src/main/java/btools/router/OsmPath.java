/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.*;

final class OsmPath implements OsmLinkHolder
{
  // double-linked lists for the openSet
  public OsmPath nextInSet;
  public OsmPath prevInSet;
  public OsmPath nextInIndexSet;
  public OsmPath prevInIndexSet;
  
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

  private static final int MAX_EHB = 10000000;

  public int adjustedCost = 0;

  public void setAirDistanceCostAdjustment( int costAdjustment )
  {
    adjustedCost = cost + costAdjustment;
  }
  
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
    int linkdist = 0;
    int linkelevationcost = 0;
    int linkturncost = 0;

    OsmTransferNode transferNode = link.decodeFirsttransfer();
    OsmNode targetNode = link.targetNode;
    String lastMessage = null;
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
      if ( lastMessage != null && !sameData )
      {
        originElement.message = lastMessage;
        linkdist = 0;
        linkelevationcost = 0;
        linkturncost = 0;
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

      linkdist += dist;
      linkdisttotal += dist;


      // *** penalty for way-change
      if ( origin.originElement != null )
      {
        // penalty proportional to direction change
        double cos = rc.calcCosAngle( lon0, lat0, lon1, lat1, lon2, lat2 );
        int turncost = (int)(cos * rc.expctxWay.getTurncost() + 0.2 ); // e.g. turncost=90 -> 90 degree = 90m penalty
        cost += turncost;
        linkturncost += turncost;
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

      if ( ehbd > MAX_EHB )
      {
         if ( rc.downhillcostdiv > 0 )
         {
           int elevationCost = (ehbd-MAX_EHB)/rc.downhillcostdiv;
           cost += elevationCost;
           linkelevationcost += elevationCost;
         }
         ehbd = MAX_EHB;
      }
      else if ( ehbd < 0 )
      {
        ehbd = 0;
      }

      if ( ehbu > MAX_EHB )
      {
         if ( rc.uphillcostdiv > 0 )
         {
           int elevationCost = (ehbu-MAX_EHB)/rc.uphillcostdiv;
           cost += elevationCost;
           linkelevationcost += elevationCost;
         }
         ehbu = MAX_EHB;
      }
      else if ( ehbu < 0 )
      {
        ehbu = 0;
      }

      // *** penalty for distance
      float costfactor = rc.expctxWay.getCostfactor();
      float fcost = dist * costfactor + 0.5f;
      if ( costfactor >= 10000. || fcost + cost >= 2000000000. )
      {
        cost = -1;
        return;
      }
      int waycost = (int)(fcost);
      cost += waycost;

      // *** add initial cost if factor changed
      float costdiff = costfactor - lastCostfactor;
      if ( costdiff > 0.0005 || costdiff < -0.0005 )
      {
          lastCostfactor = costfactor;
          float initialcost = rc.expctxWay.getInitialcost();
          int iicost = (int)initialcost;
          cost += iicost;
      }

      if ( recordTransferNodes )
      {
        int iCost = (int)(rc.expctxWay.getCostfactor()*1000 + 0.5f);
        lastMessage = (lon2-180000000) + "\t"
                    + (lat2-90000000) + "\t"
                    + ele2/4 + "\t"
                    + linkdist + "\t"
                    + iCost + "\t"
                    + linkelevationcost
                    + "\t" + linkturncost
                    + rc.expctxWay.getCsvDescription( link.counterLinkWritten, description );
      }

      if ( stopAtEndpoint )
      {
        if ( recordTransferNodes )
        {
          originElement = new OsmPathElement( rc.ilonshortest, rc.ilatshortest, ele2, originElement );
          originElement.cost = cost;
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
        message = lastMessage;
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
        rc.messageHandler.setCurrentPos( targetNode.ilon, targetNode.ilat );
        rc.expctxNode.evaluate( rc.expctxWay.getNodeAccessGranted() != 0. , targetNode.nodeDescription, rc.messageHandler );
        float initialcost = rc.expctxNode.getInitialcost();
        if ( initialcost >= 1000000. )
        {
          cost = -1;
          return;
        }
        int iicost = (int)initialcost;
        cost += iicost;
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
