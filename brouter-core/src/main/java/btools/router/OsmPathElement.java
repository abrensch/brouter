package btools.router;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */

public class OsmPathElement implements OsmPos
{
  private int ilat; // latitude
  private int ilon; // longitude
  private short selev; // longitude

  public MessageData message = null; // description

  public int cost;

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

  public final long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

  public final int calcDistance( OsmPos p )
  {
    double l = (ilat-90000000) * 0.00000001234134;
    double l2 = l*l;
    double l4 = l2*l2;
    double coslat = 1.- l2 + l4 / 6.;

    double dlat = (ilat - p.getILat() )/1000000.;
    double dlon = (ilon - p.getILon() )/1000000. * coslat;
    double d = Math.sqrt( dlat*dlat + dlon*dlon ) * (6378000. / 57.);
    return (int)(d + 1.0 );
  }

  public OsmPathElement origin;

  // construct a path element from a path
  public static final OsmPathElement create( OsmPath path, boolean countTraffic )
  {
    OsmNode n = path.getLink().targetNode;
    OsmPathElement pe = create( n.getILon(), n.getILat(), path.selev, path.originElement, countTraffic );
    pe.cost = path.cost;
    pe.message = path.message;
    return pe;
  }

  public static final OsmPathElement create( int ilon, int ilat, short selev, OsmPathElement origin, boolean countTraffic )
  {
    OsmPathElement pe = countTraffic ? new OsmPathElementWithTraffic() : new OsmPathElement();
    pe.ilon = ilon;
    pe.ilat = ilat;
    pe.selev = selev;
    pe.origin = origin;
    return pe;
  }
  
  protected OsmPathElement()
  {
  }

  public void addTraffic( float traffic )
  {
  }

  public String toString()
  {
    return ilon + "_" + ilat;
  }
  
  public void writeToStream( DataOutput dos ) throws IOException
  {
    dos.writeInt( ilat );
    dos.writeInt( ilon );
    dos.writeShort( selev );
    dos.writeInt( cost );
  }

  public static OsmPathElement readFromStream( DataInput dis ) throws IOException
  {
	OsmPathElement pe = new OsmPathElement();
	pe.ilat = dis.readInt();
	pe.ilon = dis.readInt();
	pe.selev = dis.readShort();
	pe.cost = dis.readInt();
	return pe;
  }
}
