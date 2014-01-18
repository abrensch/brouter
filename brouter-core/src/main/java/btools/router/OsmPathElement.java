/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;

final class OsmPathElement implements OsmPos
{
  private int ilat; // latitude
  private int ilon; // longitude
  private short selev; // longitude

  public String message = null; // description

  public int cost;

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

  public long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

  public int calcDistance( OsmPos p )
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
  public OsmPathElement( OsmPath path )
  {
    OsmNode n = path.getLink().targetNode;
    ilat = n.getILat();
    ilon = n.getILon();
    selev = path.selev;
    cost = path.cost;

    origin = path.originElement;
    message = path.message;
  }

  public OsmPathElement( int ilon, int ilat, short selev, OsmPathElement origin )
  {
    this.ilon = ilon;
    this.ilat = ilat;
    this.selev = selev;
    this.origin = origin;
  }
  
  private OsmPathElement()
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
