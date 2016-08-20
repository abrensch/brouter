/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.codec.MicroCache;
import btools.codec.MicroCache2;
import btools.util.ByteArrayUnifier;

public class OsmNode implements OsmPos
{
  public OsmNode()
  {
  }

  public OsmNode( int ilon, int ilat )
  {
    this.ilon = ilon;
    this.ilat = ilat;
  }

  public OsmNode( long id )
  {
    ilon = (int) ( id >> 32 );
    ilat = (int) ( id & 0xffffffff );
  }

  /**
   * The latitude
   */
  public int ilat;

  /**
   * The longitude
   */
  public int ilon;

  /**
   * The elevation
   */
  public short selev;

  public byte[] nodeDescription;

  // interface OsmPos
  public int getILat()
  {
    return ilat;
  }

  public int getILon()
  {
    return ilon;
  }

  public short getSElev()
  {
    return selev;
  }

  public double getElev()
  {
    return selev / 4.;
  }

  /**
   * The links to other nodes
   */
  public OsmLink firstlink = null;

  // preliminry in forward order to avoid regressions
  public void addLink( OsmLink link )
  {
    if ( firstlink == null )
    {
      firstlink = link;
    }
    else
    {
      OsmLink l = firstlink;
      while (l.next != null)
        l = l.next;
      l.next = link;
    }
  }

  private OsmLink getCompatibleLink( int ilon, int ilat, boolean counterLinkWritten, int state )
  {
    for ( OsmLink l = firstlink; l != null; l = l.next )
    {
      if ( counterLinkWritten == l.counterLinkWritten && l.state == state )
      {
        OsmNode t = l.targetNode;
        if ( t.ilon == ilon && t.ilat == ilat )
        {
          l.state = 0;
          return l;
        }
      }
    }
    // second try ignoring counterLinkWritten
    // (border links are written in both directions)
    for ( OsmLink l = firstlink; l != null; l = l.next )
    {
      if ( l.state == state )
      {
        OsmNode t = l.targetNode;
        if ( t.ilon == ilon && t.ilat == ilat )
        {
          l.state = 0;
          return l;
        }
      }
    }
    return null;
  }

  public int calcDistance( OsmPos p )
  {
    double l = ( ilat - 90000000 ) * 0.00000001234134;
    double l2 = l * l;
    double l4 = l2 * l2;
    double coslat = 1. - l2 + l4 / 6.;

    double dlat = ( ilat - p.getILat() ) / 1000000.;
    double dlon = ( ilon - p.getILon() ) / 1000000. * coslat;
    double d = Math.sqrt( dlat * dlat + dlon * dlon ) * ( 6378000. / 57.3 );
    return (int) ( d + 1.0 );
  }

  public String toString()
  {
    return "" + getIdFromPos();
  }

  public void parseNodeBody( MicroCache mc, OsmNodesMap hollowNodes )
  {
    if ( mc instanceof MicroCache2 )
    {
      parseNodeBody2( (MicroCache2) mc, hollowNodes );
    }
    else
      throw new IllegalArgumentException( "unknown cache version: " + mc.getClass() );
  }

  public void parseNodeBody2( MicroCache2 mc, OsmNodesMap hollowNodes )
  {
    ByteArrayUnifier abUnifier = hollowNodes.getByteArrayUnifier();

    selev = mc.readShort();
    int nodeDescSize = mc.readVarLengthUnsigned();
    nodeDescription = nodeDescSize == 0 ? null : mc.readUnified( nodeDescSize, abUnifier );

    while (mc.hasMoreData())
    {
      // read link data
      int endPointer = mc.getEndPointer();
      int linklon = ilon + mc.readVarLengthSigned();
      int linklat = ilat + mc.readVarLengthSigned();
      int sizecode = mc.readVarLengthUnsigned();
      boolean isReverse = ( sizecode & 1 ) != 0;
      byte[] description = null;
      int descSize = sizecode >> 1;
      if ( descSize > 0 )
      {
        description = mc.readUnified( descSize, abUnifier );
      }
      byte[] geometry = mc.readDataUntil( endPointer );

      if ( linklon == ilon && linklat == ilat )
      {
        continue; // skip self-ref
      }

      // first check the known links for that target
      OsmLink link = getCompatibleLink( linklon, linklat, isReverse, 2 );
      if ( link == null ) // .. not found, then check the hollow nodes
      {
        long targetNodeId = ( (long) linklon ) << 32 | linklat;
        OsmNode tn = hollowNodes.get( targetNodeId ); // target node
        if ( tn == null ) // node not yet known, create a new hollow proxy
        {
          tn = new OsmNode( linklon, linklat );
          tn.setHollow();
          hollowNodes.put( tn );
        }
        link = new OsmLink();
        link.targetNode = tn;
        link.counterLinkWritten = isReverse;
        link.state = 1;
        addLink( link );
      }

      // now we have a link with a target node -> get the reverse link
      OsmLink rlink = link.targetNode.getCompatibleLink( ilon, ilat, !isReverse, 1 );
      if ( rlink == null ) // .. not found, create it
      {
        rlink = new OsmLink();
        rlink.targetNode = this;
        rlink.counterLinkWritten = !isReverse;
        rlink.state = 2;
        link.targetNode.addLink( rlink );
      }

      if ( !isReverse )
      {
        // we have the data for that link, so fill both the link ..
        link.descriptionBitmap = description;
        link.setGeometry( geometry );

        // .. and the reverse
        if ( rlink.counterLinkWritten )
        {
          rlink.descriptionBitmap = description;
          rlink.setGeometry( geometry );
        }
      }
    }
    hollowNodes.remove( this );
  }


  public boolean isHollow()
  {
    return selev == -12345;
  }

  public void setHollow()
  {
    selev = -12345;
  }

  public long getIdFromPos()
  {
    return ( (long) ilon ) << 32 | ilat;
  }

  public void unlinkLink( OsmLink link )
  {
    if ( link == firstlink )
    {
      firstlink = link.next;
      return;
    }
    for ( OsmLink l = firstlink; l != null; l = l.next )
    {
      if ( l.next == link )
      {
        l.next = link.next;
        return;
      }
    }
  }

  @Override
  public boolean equals( Object o )
  {
    if ( o instanceof OsmNode )
    {
      OsmNode n = (OsmNode) o;
      return n.ilon == ilon && n.ilat == ilat;
    }
    return false;
  }

  @Override
  public int hashCode()
  {
    return ilon + ilat;
  }
}
