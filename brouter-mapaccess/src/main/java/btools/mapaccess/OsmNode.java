/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.CheapRuler;

public class OsmNode extends OsmLink implements OsmPos {
  /**
   * The latitude
   */
  public int iLat;

  /**
   * The longitude
   */
  public int iLon;

  /**
   * The elevation
   */
  public short sElev = Short.MIN_VALUE;

  /**
   * The node-tags, if any
   */
  public byte[] nodeDescription;

  public TurnRestriction firstRestriction;

  public int visitID;

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  public final static int BORDER_BIT = 4;
  public final static int TRANSFERNODE_BIT = 8;
  public final static int TR_TARGET_BIT = 16;
  public final static int DP_SURVIVOR_BIT = 64;

  public boolean hasBits( int mask ) {
    return (visitID & mask ) != 0;
  }

  public void setBits( int mask ) {
    visitID |= mask;
  }

  public void addTurnRestriction(TurnRestriction tr) {
    tr.next = firstRestriction;
    firstRestriction = tr;
  }

  /**
   * The links to other nodes
   */
  public OsmLink firstLink;

  public OsmLink getFirstLink() {
    return firstLink;
  }

  public OsmNode() {
  }

  public OsmNode(int iLon, int iLat) {
    this.iLon = iLon;
    this.iLat = iLat;
  }

  public OsmNode(long id) {
    iLon = (int) (id >> 32);
    iLat = (int) (id & 0xffffffff);
  }


  // interface OsmPos
  public final int getILat() {
    return iLat;
  }

  public final int getILon() {
    return iLon;
  }

  public final short getSElev() {
    return sElev;
  }

  public final double getElev() {
    return sElev / 4.;
  }

  public int linkCount() {
    int cnt = 0;
    OsmLink l = firstLink;
    while ( l != null ) {
      cnt++;
      l = l.getNext(this);
    }
    return cnt;
  }

  public OsmLink linkForTarget( int targetLon, int targetLat ) {
    for (OsmLink l = firstLink; l != null; l = l.getNext(this)) {
      OsmNode t = l.getTarget(this);
      if (t.iLon == targetLon && t.iLat == targetLat) {
        return l;
      }
    }
    return null;
  }

  // populate and return the inherited link, if available,
  // else create a new one
  public OsmLink createLink(byte[] wayDescription, OsmNode target) {

    if ( target == this ) {
      throw new RuntimeException(" self ref!");
    }
    OsmLink link = isLinkUnused() ? this : (target.isLinkUnused() ? target : new OsmLink() );
    link.wayDescription = wayDescription;
    link.sourceNode = this;
    link.targetNode = target;
    link.next = firstLink;
    link.previous = target.firstLink;
    target.firstLink = link;
    firstLink = link;
    return link;
  }

  public final int calcDistance(OsmPos p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(iLon, iLat, p.getILon(), p.getILat())));
  }

  public String toString() {
    return "n_" + (iLon - 180000000) + "_" + (iLat - 90000000);
  }


  public final boolean isHollow() {
    return sElev == -12345;
  }

  public final void setHollow() {
    sElev = -12345;
  }

  public final long getIdFromPos() {
    return ((long) iLon) << 32 | iLat;
  }

  public void vanish() {
    if (!isHollow()) {
      OsmLink l = firstLink;
      while (l != null) {
        OsmNode target = l.getTarget(this);
        OsmLink nextLink = l.getNext(this);
        if (!target.isHollow()) {
          unlinkLink(l);
          if (!l.isLinkUnused()) {
            target.unlinkLink(l);
          }
        }
        l = nextLink;
      }
    }
  }

  public boolean linkStillValid( OsmLink link ) {
    OsmLink l = firstLink;
    while ( l != null ) {
      if ( l == link ) {
        return true;
      }
      l = l.getNext(this);
    }
    return false;
  }

  public final void unlinkLink(OsmLink link) {
    OsmLink n = link.clear(this);

    if (link == firstLink) {
      firstLink = n;
      return;
    }
    OsmLink l = firstLink;
    while (l != null) {
      // if ( l.isReverse( this ) )
      if (l.sourceNode != this && l.sourceNode != null) { // isReverse inline
        OsmLink nl = l.previous;
        if (nl == link) {
          l.previous = n;
          return;
        }
        l = nl;
      } else if (l.targetNode != this && l.targetNode != null) {
        OsmLink nl = l.next;
        if (nl == link) {
          l.next = n;
          return;
        }
        l = nl;
      } else {
        throw new IllegalArgumentException("unlinkLink: unknown source");
      }
    }
  }


  @Override
  public final boolean equals(Object o) {
    return ((OsmNode) o).iLon == iLon && ((OsmNode) o).iLat == iLat;
  }

  @Override
  public final int hashCode() {
    return iLon + iLat;
  }
}
