/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.CheapRuler;

public class OsmNode extends OsmLink {
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
   * Bit flags (see the *_BIT constants)
   */
  private short bits;

  /**
   * The node-tags, if any
   */
  public byte[] nodeDescription;

  public TurnRestriction firstRestriction;

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  public final static int BORDER_BIT = 4;
  public final static int TRANSFERNODE_BIT = 8;
  public final static int TR_TARGET_BIT = 16;
  public final static int DP_SURVIVOR_BIT = 64;
  public final static int HOLLOW_BIT = 128;

  public boolean hasBits( int mask ) {
    return (bits & mask ) != 0;
  }

  public void setBits( int mask ) {
    bits |= mask;
  }

  public void clearBits( int mask ) {
    bits &= ~mask;
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

  /**
   * Checks whether this node has at least one link whose target node
   * is still hollow (i.e. only its id is known, its data not yet decoded).
   *
   * @return true if at least one link target is hollow, false otherwise
   */
  public boolean hasHollowLinkTargets() {
    for (OsmLink link = firstLink; link != null; link = link.getNext(this)) {
      if (link.getTarget(this).isHollow()) {
        return true;
      }
    }
    return false;
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

  /**
   * Create a link to the specified target node.
   * The link is bidirectional, but has a logical direction
   * pointing from this node to the target node, so for the reverse
   * direction e.g. the one-way information in the given wayDescription
   * must be inverted.
   *
   * There's technical inheritance between OsmNode and OsmLink, so if
   * one of this or the target node has it's "own" link yet unused, then
   * this is used instead of creating a new OsmLink instance. This way
   * a linear chain of nodes connected by links uses just one object
   * instance per node.
   *
   * @param wayDescription the way description for the link
   * @param target the target node to link to
   * @return the created OsmLink instance
   */
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

  public final int calcDistance(OsmNode p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(iLon, iLat, p.getILon(), p.getILat())));
  }

  public String toString() {
    return "n_" + (iLon - 180000000) + "_" + (iLat - 90000000);
  }


  public final boolean isHollow() {
    return hasBits(HOLLOW_BIT);
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
      if ( l.isReverse( this ) ) {
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
