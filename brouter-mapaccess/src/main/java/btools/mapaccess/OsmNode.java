/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.codec.MicroCache;
import btools.codec.MicroCache2;
import btools.util.ByteArrayUnifier;
import btools.util.CheapRuler;
import btools.util.IByteArrayUnifier;

public class OsmNode extends OsmLink implements OsmPos {
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
  public short selev = Short.MIN_VALUE;

  /**
   * The node-tags, if any
   */
  public byte[] nodeDescription;

  public TurnRestriction firstRestriction;

  public int visitID;

  public void addTurnRestriction(TurnRestriction tr) {
    tr.next = firstRestriction;
    firstRestriction = tr;
  }

  /**
   * The links to other nodes
   */
  public OsmLink firstlink;

  public OsmNode() {
  }

  public OsmNode(int ilon, int ilat) {
    this.ilon = ilon;
    this.ilat = ilat;
  }

  public OsmNode(long id) {
    ilon = (int) (id >> 32);
    ilat = (int) (id & 0xffffffff);
  }


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

  public final double getElev() {
    return selev / 4.;
  }

  public final void addLink(OsmLink link, boolean isReverse, OsmNode tn) {
    if (link == firstlink) {
      throw new IllegalArgumentException("UUUUPS");
    }

    if (isReverse) {
      link.n1 = tn;
      link.n2 = this;
      link.next = tn.firstlink;
      link.previous = firstlink;
      tn.firstlink = link;
      firstlink = link;
    } else {
      link.n1 = this;
      link.n2 = tn;
      link.next = firstlink;
      link.previous = tn.firstlink;
      tn.firstlink = link;
      firstlink = link;
    }
  }

  public final int calcDistance(OsmPos p) {
    return (int) Math.max(1.0, Math.round(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat())));
  }

  public String toString() {
    return "n_" + (ilon - 180000000) + "_" + (ilat - 90000000);
  }

  public final void parseNodeBody(MicroCache mc, OsmNodesMap hollowNodes, IByteArrayUnifier expCtxWay) {
    if (mc instanceof MicroCache2) {
      parseNodeBody2((MicroCache2) mc, hollowNodes, expCtxWay);
    } else
      throw new IllegalArgumentException("unknown cache version: " + mc.getClass());
  }

  public final void parseNodeBody2(MicroCache2 mc, OsmNodesMap hollowNodes, IByteArrayUnifier expCtxWay) {
    ByteArrayUnifier abUnifier = hollowNodes.getByteArrayUnifier();

    // read turn restrictions
    while (mc.readBoolean()) {
      TurnRestriction tr = new TurnRestriction();
      tr.exceptions = mc.readShort();
      tr.isPositive = mc.readBoolean();
      tr.fromLon = mc.readInt();
      tr.fromLat = mc.readInt();
      tr.toLon = mc.readInt();
      tr.toLat = mc.readInt();
      addTurnRestriction(tr);
    }

    selev = mc.readShort();
    int nodeDescSize = mc.readVarLengthUnsigned();
    nodeDescription = nodeDescSize == 0 ? null : mc.readUnified(nodeDescSize, abUnifier);

    while (mc.hasMoreData()) {
      // read link data
      int endPointer = mc.getEndPointer();
      int linklon = ilon + mc.readVarLengthSigned();
      int linklat = ilat + mc.readVarLengthSigned();
      int sizecode = mc.readVarLengthUnsigned();
      boolean isReverse = (sizecode & 1) != 0;
      byte[] description = null;
      int descSize = sizecode >> 1;
      if (descSize > 0) {
        description = mc.readUnified(descSize, expCtxWay);
      }
      byte[] geometry = mc.readDataUntil(endPointer);

      addLink(linklon, linklat, description, geometry, hollowNodes, isReverse);
    }
    hollowNodes.remove(this);
  }

  public void addLink(int linklon, int linklat, byte[] description, byte[] geometry, OsmNodesMap hollowNodes, boolean isReverse) {
    if (linklon == ilon && linklat == ilat) {
      return; // skip self-ref
    }

    OsmNode tn = null; // find the target node
    OsmLink link = null;

    // ...in our known links
    for (OsmLink l = firstlink; l != null; l = l.getNext(this)) {
      OsmNode t = l.getTarget(this);
      if (t.ilon == linklon && t.ilat == linklat) {
        tn = t;
        if (isReverse || (l.descriptionBitmap == null && !l.isReverse(this))) {
          link = l; // the correct one that needs our data
          break;
        }
      }
    }
    if (tn == null) // .. not found, then check the hollow nodes
    {
      tn = hollowNodes.get(linklon, linklat); // target node
      if (tn == null) // node not yet known, create a new hollow proxy
      {
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
      link.geometry = geometry;
    }
  }


  public final boolean isHollow() {
    return selev == -12345;
  }

  public final void setHollow() {
    selev = -12345;
  }

  public final long getIdFromPos() {
    return ((long) ilon) << 32 | ilat;
  }

  public void vanish() {
    if (!isHollow()) {
      OsmLink l = firstlink;
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

    if (link == firstlink) {
      firstlink = n;
      return;
    }
    OsmLink l = firstlink;
    while (l != null) {
      // if ( l.isReverse( this ) )
      if (l.n1 != this && l.n1 != null) // isReverse inline
      {
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
    return ((OsmNode) o).ilon == ilon && ((OsmNode) o).ilat == ilat;
  }

  @Override
  public final int hashCode() {
    return ilon + ilat;
  }
}
