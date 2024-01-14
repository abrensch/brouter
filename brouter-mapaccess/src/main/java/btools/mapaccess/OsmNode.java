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

  public final void addLink(OsmLink link, boolean isReverse, OsmNode tn) {
    if (link == firstLink) {
      throw new IllegalArgumentException("UUUUPS");
    }

    if (isReverse) {
      link.n1 = tn;
      link.n2 = this;
      link.next = tn.firstLink;
      link.previous = firstLink;
      tn.firstLink = link;
      firstLink = link;
    } else {
      link.n1 = this;
      link.n2 = tn;
      link.next = firstLink;
      link.previous = tn.firstLink;
      tn.firstLink = link;
      firstLink = link;
    }
  }

  public final int calcDistance(OsmPos p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(iLon, iLat, p.getILon(), p.getILat())));
  }

  public String toString() {
    return "n_" + (iLon - 180000000) + "_" + (iLat - 90000000);
  }

  public void addLink(int linklon, int linklat, byte[] description, OsmNodesMap hollowNodes, boolean isReverse) {
    if (linklon == iLon && linklat == iLat) {
      return; // skip self-ref
    }

    OsmNode tn = null; // find the target node
    OsmLink link = null;

    // ...in our known links
    for (OsmLink l = firstLink; l != null; l = l.getNext(this)) {
      OsmNode t = l.getTarget(this);
      if (t.iLon == linklon && t.iLat == linklat) {
        tn = t;
        if (isReverse || (l.descriptionBitmap == null && !l.isReverse(this))) {
          link = l; // the correct one that needs our data
          break;
        }
      }
    }
    if (tn == null) { // .. not found, then check the hollow nodes
      tn = hollowNodes.get(linklon, linklat); // target node
      if (tn == null) { // node not yet known, create a new hollow proxy
        tn = new OsmNode(linklon, linklat);
        tn.setHollow();
        hollowNodes.put(tn);
        addLink(link = tn, isReverse, tn); // technical inheritance: link instance in node
      }
    }
    if (link == null) {
      addLink(link = new OsmLink(), isReverse, tn);
    }
    if (!isReverse) {
      link.descriptionBitmap = description;
    }
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

  public final void unlinkLink(OsmLink link) {
    OsmLink n = link.clear(this);

    if (link == firstLink) {
      firstLink = n;
      return;
    }
    OsmLink l = firstLink;
    while (l != null) {
      // if ( l.isReverse( this ) )
      if (l.n1 != this && l.n1 != null) { // isReverse inline
        OsmLink nl = l.previous;
        if (nl == link) {
          l.previous = n;
          return;
        }
        l = nl;
      } else if (l.n2 != this && l.n2 != null) {
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
