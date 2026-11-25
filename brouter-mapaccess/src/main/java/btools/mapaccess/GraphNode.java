/**
 * Container for a node.
 *
 * Inheriting from GraphLink is just technical inheritance
 * to keep the memory footprint of the Graph small
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.CheapRuler;

import java.util.Set;

public class GraphNode extends GraphLink {
  /**
   * The longitude (0...360.000.000 -1 )
   */
  public final int iLon;

  /**
   * The latitude (0...180.000.000 -1 )
   */
  public final int iLat;

  /**
   * The elevation in quarter meters (Short.MIN_VALUE if unknown)
   */
  public short sElev = Short.MIN_VALUE;

  /**
   * The links to other nodes
   */
  public GraphLink firstLink;

  /**
   * The node-tags, if any
   */
  public byte[] nodeDescription;

  /**
   * Bit flags (see the *_BIT constants)
   */
  private short bits;


  /**
   * The turn restrictions with this node as via-node, if any
   */
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


  public GraphNode(int iLon, int iLat) {
    if (iLon < 0 || iLon >= 360000000) {
      throw new IllegalArgumentException("iLon out of range (0...359999999): " + iLon);
    }
    if (iLat < 0 || iLat >= 180000000) {
      throw new IllegalArgumentException("iLat out of range (0...179999999): " + iLat);
    }
    this.iLon = iLon;
    this.iLat = iLat;
  }

  public GraphNode(long id) {
    this((int) (id >> 32), (int) (id & 0xffffffffL));
  }



  public final double getElev() {
    return sElev / 4.;
  }

  public int linkCount() {
    int cnt = 0;
    GraphLink l = firstLink;
    while ( l != null ) {
      cnt++;
      l = l.getNext(this);
    }
    return cnt;
  }

  /**
   * Find any link to the given position.
   * Return the first link found, or null if none exists.
   */
  public GraphLink anyLinkForTarget( int targetLon, int targetLat ) {
    for (GraphLink l = firstLink; l != null; l = l.getNext(this)) {
      GraphNode t = l.getTarget(this);
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
   * There's technical inheritance between GraphNode and GraphLink, so if
   * one of this or the target node has it's "own" link yet unused, then
   * this is used instead of creating a new GraphLink instance. This way
   * a linear chain of nodes connected by links uses just one object
   * instance per node.
   *
   * @param wayDescription the way description for the link
   * @param target the target node to link to
   * @return the created GraphLink instance
   */
  public GraphLink createLink(byte[] wayDescription, GraphNode target) {

    if ( target == this ) {
      throw new RuntimeException(" self ref!");
    }
    GraphLink link = isLinkUnused() ? this : (target.isLinkUnused() ? target : new GraphLink() );
    link.wayDescription = wayDescription;
    link.sourceNode = this;
    link.targetNode = target;
    link.next = firstLink;
    link.previous = target.firstLink;
    target.firstLink = link;
    firstLink = link;
    return link;
  }

  public final int calcDistance(GraphNode p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(iLon, iLat, p.iLon, p.iLat)));
  }

  public String toString() {
    return "n_" + (iLon - 180000000) + "_" + (iLat - 90000000);
  }


  public final boolean isHollow() {
    return hasBits(HOLLOW_BIT);
  }

  /**
   * The unique id of this node, derived from its position.
   */
  public final long getIdFromPos() {
    return idFromPos(iLon, iLat);
  }

  public void vanish() {
    if (!isHollow()) {
      GraphLink l = firstLink;
      while (l != null) {
        GraphNode target = l.getTarget(this);
        GraphLink nextLink = l.getNext(this);
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

  public boolean linkStillValid( GraphLink link ) {
    GraphLink l = firstLink;
    while ( l != null ) {
      if ( l == link ) {
        return true;
      }
      l = l.getNext(this);
    }
    return false;
  }

  /**
   * Remove the given link from the link chain of <b>this</b> node only (one-sided unlink).
   *
   * @param link the link to remove, must be in this node's chain
   * @throws IllegalArgumentException if the link is not part of this node's chain
   */
  public final void unlinkLink(GraphLink link) {
    GraphLink n = link.clear(this);

    if (link == firstLink) {
      firstLink = n;
      return;
    }
    GraphLink l = firstLink;
    while (l != null) {
      if ( l.isReverse( this ) ) {
        GraphLink nl = l.previous;
        if (nl == link) {
          l.previous = n;
          return;
        }
        l = nl;
      } else if (l.targetNode != this && l.targetNode != null) {
        GraphLink nl = l.next;
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


  /**
   * The unique id for the given position (also used as map key).
   */
  public static long idFromPos(int iLon, int iLat) {
    return ((long) iLon << 32) | (iLat & 0xFFFFFFFFL);
  }
}
