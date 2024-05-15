package btools.router;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */

public class OsmPathElement implements OsmPos {
  private int ilat; // latitude
  private int ilon; // longitude
  private short selev; // longitude

  public MessageData message = null; // description

  public int cost;

  // interface OsmPos
  public final int getILat() {
    return ilat;
  }

  public final int getILon() {
    return ilon;
  }

  public final short getSElev() {
    return selev;
  }

  public final void setSElev(short s) {
    selev = s;
  }

  public final double getElev() {
    return selev / 4.;
  }

  public final float getTime() {
    return message == null ? 0.f : message.time;
  }

  public final void setTime(float t) {
    if (message != null) {
      message.time = t;
    }
  }

  public final float getEnergy() {
    return message == null ? 0.f : message.energy;
  }

  public final void setEnergy(float e) {
    if (message != null) {
      message.energy = e;
    }
  }

  public final void setAngle(float e) {
    if (message != null) {
      message.turnangle = e;
    }
  }

  public final long getIdFromPos() {
    return ((long) ilon) << 32 | ilat;
  }

  public final int calcDistance(OsmPos p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat())));
  }

  public OsmPathElement origin;

  // construct a path element from a path
  public static final OsmPathElement create(OsmPath path) {
    OsmNode n = path.getTargetNode();
    OsmPathElement pe = create(n.getILon(), n.getILat(), n.getSElev(), path.originElement);
    pe.cost = path.cost;
    pe.message = path.message;
    return pe;
  }

  public static final OsmPathElement create(int ilon, int ilat, short selev, OsmPathElement origin) {
    OsmPathElement pe = new OsmPathElement();
    pe.ilon = ilon;
    pe.ilat = ilat;
    pe.selev = selev;
    pe.origin = origin;
    return pe;
  }

  protected OsmPathElement() {
  }

  public String toString() {
    return ilon + "_" + ilat;
  }

  public boolean positionEquals(OsmPathElement e) {
    return this.ilat == e.ilat && this.ilon == e.ilon;
  }

  public void writeToStream(DataOutput dos) throws IOException {
    dos.writeInt(ilat);
    dos.writeInt(ilon);
    dos.writeShort(selev);
    dos.writeInt(cost);
  }

  public static OsmPathElement readFromStream(DataInput dis) throws IOException {
    OsmPathElement pe = new OsmPathElement();
    pe.ilat = dis.readInt();
    pe.ilon = dis.readInt();
    pe.selev = dis.readShort();
    pe.cost = dis.readInt();
    return pe;
  }
}
