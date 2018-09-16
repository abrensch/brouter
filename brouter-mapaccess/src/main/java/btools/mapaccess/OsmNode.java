/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.codec.MicroCache;
import btools.codec.MicroCache2;
import btools.expressions.BExpressionContextWay;
import btools.util.ByteArrayUnifier;
import btools.util.IByteArrayUnifier;

public class OsmNode extends OsmLink implements OsmPos
{
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

  /**
   * The node-tags, if any
   */
  public byte[] nodeDescription;
  
  public TurnRestriction firstRestriction;

  /**
   * The links to other nodes
   */
  public OsmLink firstlink = null;

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


  // interface OsmPos
  public final int getILat()
  {
    return ilat;
  }

  public final int getILon()
  {
    return ilon;
  }

  public final short getSElev()
  {
    return selev;
  }

  public final double getElev()
  {
    return selev / 4.;
  }

  private void addLink( OsmLink link, boolean isReverse, OsmNode tn )
  {
    if ( isReverse )
    {
      link.n1 = tn;
      link.n2 = this;
      link.next = tn.firstlink;
      link.previous = firstlink;
      tn.firstlink = link;
      firstlink = link;
    }
    else
    {
      link.n1 = this;
      link.n2 = tn;
      link.next = firstlink;
      link.previous = tn.firstlink;
      tn.firstlink = link;
      firstlink = link;
    }
  }

  public final int calcDistance( OsmPos p )
  {
    double l = ( ilat - 90000000 ) * 0.00000001234134;
    double l2 = l * l;
    double l4 = l2 * l2;
    double coslat = 1. - l2 + l4 / 6.;

    double dlat = ( ilat - p.getILat() );
    double dlon = ( ilon - p.getILon() ) * coslat;
    double d = Math.sqrt( dlat * dlat + dlon * dlon ) * 0.110984; //  6378000. / 57.3;
    return (int) ( d + 1.0 );
  }

  public String toString()
  {
    return "" + getIdFromPos();
  }

  public final void parseNodeBody( MicroCache mc, OsmNodesMap hollowNodes, IByteArrayUnifier expCtxWay )
  {
    if ( mc instanceof MicroCache2 )
    {
      parseNodeBody2( (MicroCache2) mc, hollowNodes, expCtxWay );
    }
    else
      throw new IllegalArgumentException( "unknown cache version: " + mc.getClass() );
  }

  public final void parseNodeBody2( MicroCache2 mc, OsmNodesMap hollowNodes, IByteArrayUnifier expCtxWay )
  {
    ByteArrayUnifier abUnifier = hollowNodes.getByteArrayUnifier();
    
    // read turn restrictions
    while( mc.readBoolean() )
    {
      TurnRestriction tr = new TurnRestriction();
      tr.exceptions =  mc.readShort();
      tr.isPositive =  mc.readBoolean();
      tr.fromLon = mc.readInt();
      tr.fromLat = mc.readInt();
      tr.toLon = mc.readInt();
      tr.toLat = mc.readInt();
      tr.next = firstRestriction;
      firstRestriction = tr;
    }

    selev = mc.readShort();
    int nodeDescSize = mc.readVarLengthUnsigned();
    nodeDescription = nodeDescSize == 0 ? null : mc.readUnified( nodeDescSize, abUnifier );
    
    OsmLink link0 = firstlink;

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
        description = mc.readUnified( descSize, expCtxWay );
      }
      byte[] geometry = mc.readDataUntil( endPointer );

      if ( linklon == ilon && linklat == ilat )
      {
        continue; // skip self-ref
      }

      OsmNode tn = null; // find the target node
      OsmLink link = null;

      // ...in our known links
      for ( OsmLink l = link0; l != null; l = l.getNext( this ) )
      {
        OsmNode t = l.getTarget( this );
        if ( t.ilon == linklon && t.ilat == linklat )
        {
          tn = t;
          if ( isReverse || ( l.descriptionBitmap == null && !l.isReverse( this ) ) )
          {
            link = l; // the correct one that needs our data
            break;
          }
        }
      }
      if ( tn == null ) // .. not found, then check the hollow nodes
      {
        tn = hollowNodes.get( linklon, linklat ); // target node
        if ( tn == null ) // node not yet known, create a new hollow proxy
        {
          tn = new OsmNode( linklon, linklat );
          tn.setHollow();
          hollowNodes.put( tn );
          addLink( link = tn, isReverse, tn ); // technical inheritance: link instance in node
        }
      }
      if ( link == null )
      {
        addLink( link = new OsmLink(), isReverse, tn );
      }
      if ( !isReverse )
      {
        link.descriptionBitmap = description;
        link.geometry = geometry;
      }
    }
    hollowNodes.remove( this );
  }


  public final boolean isHollow()
  {
    return selev == -12345;
  }

  public final void setHollow()
  {
    selev = -12345;
  }

  public final long getIdFromPos()
  {
    return ( (long) ilon ) << 32 | ilat;
  }

  public final void unlinkLink( OsmLink link )
  {
    OsmLink n = link.clear( this );
  
    if ( link == firstlink )
    {
      firstlink = n;
      return;
    }
    OsmLink l = firstlink;
    while( l != null )
    {
      // if ( l.isReverse( this ) )
      if ( l.n1 != this && l.n1 != null ) // isReverse inline
      {
        OsmLink nl = l.previous;
        if ( nl == link )
        {
          l.previous = n;
          return;
        }
        l = nl;
      }
      else
      {
        OsmLink nl = l.next;
        if ( nl == link )
        {
          l.next = n;
          return;
        }
        l = nl;
      }
    }
  }


  @Override
  public final boolean equals( Object o )
  {
    if ( o instanceof OsmNode )
    {
      OsmNode n = (OsmNode) o;
      return n.ilon == ilon && n.ilat == ilat;
    }
    return false;
  }

  @Override
  public final int hashCode()
  {
    return ilon + ilat;
  }
}
