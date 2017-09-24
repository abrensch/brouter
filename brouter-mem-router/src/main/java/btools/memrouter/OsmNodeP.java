/**
 * Container for an osm node (pre-pocessor version)
 *
 * @author ab
 */
package btools.memrouter;

import btools.mapaccess.OsmPos;

public class OsmNodeP extends OsmLinkP implements Comparable<OsmNodeP>, OsmPos
{
 public OsmNodeP( double dlon, double dlat )
 {
   ilon = (int)(dlon * 1000000 + 180000000);
   ilat = (int)(dlat * 1000000 + 90000000);
 }
 public OsmNodeP()
 {
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

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  
  public byte wayBits = 0;

  // interface OsmPos
  @Override
  public int getILat()
  {
    return ilat;
  }

  @Override
  public int getILon()
  {
    return ilon;
  }

  @Override
  public short getSElev()
  {
    // if all bridge or all tunnel, elevation=no-data
    return ( wayBits & NO_BRIDGE_BIT ) == 0 || ( wayBits & NO_TUNNEL_BIT ) == 0 ? Short.MIN_VALUE : selev;
  }

  @Override
  public double getElev()
  {
    return selev / 4.;
  }

  // populate and return the inherited link, if available,
  // else create a new one
  public OsmLinkP createLink( OsmNodeP source )
  {
   if ( sourceNode == null && targetNode == null )
   {
     // inherited instance is available, use this
     sourceNode = source;
     targetNode = this;
     source.addLink( this );
     return this;
   }
   OsmLinkP link = new OsmLinkP( source, this );
   addLink( link );
   source.addLink( link );
   return link;
 }


  // memory-squeezing-hack: OsmLinkP's "previous" also used as firstlink..

  public void addLink( OsmLinkP link )
  {
    link.setNext( previous, this );
    previous = link;
  }

  public OsmLinkP getFirstLink()
  {
    return sourceNode == null && targetNode == null ? previous : this;
  }

  // interface OsmPos

  @Override
  public int calcDistance( OsmPos p )
  {
    double l = (ilat-90000000) * 0.00000001234134;
    double l2 = l*l;
    double l4 = l2*l2;
    double coslat = 1.- l2 + l4 / 6.;

    double dlat = (ilat - p.getILat() )/1000000.;
    double dlon = (ilon - p.getILon() )/1000000. * coslat;
    double d = Math.sqrt( dlat*dlat + dlon*dlon ) * 110984.; //  6378000. / 57.3;
    return (int)(d + 1.0 );
  }

  @Override
  public long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

   public byte[] getNodeDecsription()
   {
     return null;
   }


  public String toString2()
  {
    return (ilon-180000000) + "_" + (ilat-90000000) + "_" + (selev/4);
  }



 /**
   * Compares two OsmNodes for position ordering.
   *
   * @return -1,0,1 depending an comparson result
   */
   public int compareTo( OsmNodeP n )
   {
     long id1 = getIdFromPos();
     long id2 = n.getIdFromPos();
     if ( id1 < id2 ) return -1;
     if ( id1 > id2 ) return  1;
     return 0;
   }

  public OffsetSet filterAndCloseNode( OffsetSet in, boolean modifyGate )
  {
    return in; // do nothing (StationNode overrides)
  }
  
  public String getName()
  {
    return "<waynode>";
  }
}
