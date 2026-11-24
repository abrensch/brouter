package btools.router;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import btools.mapaccess.GraphLink;
import btools.mapaccess.GraphNode;
import btools.util.CheapRuler;

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */

public class OsmPathElement {
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

  public final int calcDistance(OsmPathElement p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat())));
  }

  public OsmPathElement origin;

  // way description and logical direction of the link used to reach this element
  // (to tell apart parallel links)
  public byte[] wayDescription;
  public boolean linkReverse;

  /**
   * Check if the given link, when taken from the given source node,
   * is equivalent to the link used to reach this element
   * (same way description and same logical direction)
   */
  public boolean isSameLink(GraphLink link, GraphNode source) {
    return true; // doesn't work yet (decoding-details differ!)
    // a null description only occurs for the start element (no real link), treated as wildcard
    // return wayDescription == null
    //  || ( Arrays.equals(wayDescription, link.wayDescription ) && linkReverse == link.isReverse(source ));
  }

  // construct a path element from a path
  public static final OsmPathElement create(OsmPath path) {
    GraphNode n = path.getTargetNode();
    OsmPathElement pe = create(n.iLon, n.iLat, n.sElev, path.originElement);
    pe.cost = path.cost;
    pe.message = path.message;
    pe.wayDescription = path.link == null ? null : path.link.wayDescription;
    pe.linkReverse = path.link != null && path.sourceNode != null && path.link.isReverse(path.sourceNode);
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
}
