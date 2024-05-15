/**
 * Container for an osm node (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.codec.MicroCache;
import btools.codec.MicroCache2;

public class OsmNodeP extends OsmLinkP {
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
  public final static int BORDER_BIT = 4;
  public final static int TRAFFIC_BIT = 8;
  public final static int ANY_WAY_BIT = 16;
  public final static int MULTI_WAY_BIT = 32;
  public final static int DP_SURVIVOR_BIT = 64;

  public byte bits = 0;

  // interface OsmPos
  public int getILat() {
    return ilat;
  }

  public int getILon() {
    return ilon;
  }

  public short getSElev() {
    // if all bridge or all tunnel, elevation=no-data
    return (bits & NO_BRIDGE_BIT) == 0 || (bits & NO_TUNNEL_BIT) == 0 ? Short.MIN_VALUE : selev;
  }

  public double getElev() {
    return selev / 4.;
  }

  // populate and return the inherited link, if available,
  // else create a new one
  public OsmLinkP createLink(OsmNodeP source) {
    if (sourceNode == null && targetNode == null) {
      // inherited instance is available, use this
      sourceNode = source;
      targetNode = this;
      source.addLink(this);
      return this;
    }
    OsmLinkP link = new OsmLinkP(source, this);
    addLink(link);
    source.addLink(link);
    return link;
  }

  // memory-squeezing-hack: OsmLinkP's "previous" also used as firstlink..

  public void addLink(OsmLinkP link) {
    link.setNext(previous, this);
    previous = link;
  }

  public OsmLinkP getFirstLink() {
    return sourceNode == null && targetNode == null ? previous : this;
  }

  public byte[] getNodeDecsription() {
    return null;
  }

  public RestrictionData getFirstRestriction() {
    return null;
  }

  public void writeNodeData(MicroCache mc) throws IOException {
    boolean valid = true;
    if (mc instanceof MicroCache2) {
      valid = writeNodeData2((MicroCache2) mc);
    } else
      throw new IllegalArgumentException("unknown cache version: " + mc.getClass());
    if (valid) {
      mc.finishNode(getIdFromPos());
    } else {
      mc.discardNode();
    }
  }

  public void checkDuplicateTargets() {
    Map<OsmNodeP, OsmLinkP> targets = new HashMap<>();

    for (OsmLinkP link0 = getFirstLink(); link0 != null; link0 = link0.getNext(this)) {
      OsmLinkP link = link0;
      OsmNodeP origin = this;
      OsmNodeP target = null;

      // first pass just to see if that link is consistent
      while (link != null) {
        target = link.getTarget(origin);
        if (!target.isTransferNode()) {
          break;
        }
        // next link is the one (of two), does does'nt point back
        for (link = target.getFirstLink(); link != null; link = link.getNext(target)) {
          if (link.getTarget(target) != origin)
            break;
        }
        origin = target;
      }
      if (link == null) continue;
      OsmLinkP oldLink = targets.put(target, link0);
      if (oldLink != null) {
        unifyLink(oldLink);
        unifyLink(link0);
      }
    }
  }

  private void unifyLink(OsmLinkP link) {
    if (link.isReverse(this)) return;
    OsmNodeP target = link.getTarget(this);
    if (target.isTransferNode()) {
      target.incWayCount();
    }
  }

  public boolean writeNodeData2(MicroCache2 mc) throws IOException {
    boolean hasLinks = false;

    // write turn restrictions
    RestrictionData r = getFirstRestriction();
    while (r != null) {
      if (r.isValid() && r.fromLon != 0 && r.toLon != 0) {
        mc.writeBoolean(true); // restriction follows
        mc.writeShort(r.exceptions);
        mc.writeBoolean(r.isPositive());
        mc.writeInt(r.fromLon);
        mc.writeInt(r.fromLat);
        mc.writeInt(r.toLon);
        mc.writeInt(r.toLat);
      }
      r = r.next;
    }
    mc.writeBoolean(false); // end restritions

    mc.writeShort(getSElev());
    mc.writeVarBytes(getNodeDecsription());

    // buffer internal reverse links
    List<OsmNodeP> internalReverse = new ArrayList<>();

    for (OsmLinkP link0 = getFirstLink(); link0 != null; link0 = link0.getNext(this)) {
      OsmLinkP link = link0;
      OsmNodeP origin = this;
      OsmNodeP target = null;

      List<OsmNodeP> linkNodes = new ArrayList<>();
      linkNodes.add(this);

      // first pass just to see if that link is consistent
      while (link != null) {
        target = link.getTarget(origin);
        linkNodes.add(target);

        if (!target.isTransferNode()) {
          break;
        }
        // next link is the one (of two), does does'nt point back
        for (link = target.getFirstLink(); link != null; link = link.getNext(target)) {
          if (link.getTarget(target) != origin)
            break;
        }

        if (link != null && link.descriptionBitmap != link0.descriptionBitmap) {
          throw new IllegalArgumentException("assertion failed: description change along transfer nodes");
        }

        origin = target;
      }
      if (link == null)
        continue; // dead end
      if (target == this)
        continue; // self-ref
      hasLinks = true;

      // internal reverse links later
      boolean isReverse = link0.isReverse(this);
      if (isReverse) {
        if (mc.isInternal(target.ilon, target.ilat)) {
          internalReverse.add(target);
          continue;
        }
      }

      byte[] description = link0.descriptionBitmap;

      // write link data
      int sizeoffset = mc.writeSizePlaceHolder();
      mc.writeVarLengthSigned(target.ilon - ilon);
      mc.writeVarLengthSigned(target.ilat - ilat);
      mc.writeModeAndDesc(isReverse, description);
      if (!isReverse && linkNodes.size() > 2) { // write geometry for forward links only
        DPFilter.doDPFilter(linkNodes);
        origin = this;
        for (int i = 1; i < linkNodes.size() - 1; i++) {
          OsmNodeP tranferNode = linkNodes.get(i);
          if ((tranferNode.bits & DP_SURVIVOR_BIT) != 0) {
            mc.writeVarLengthSigned(tranferNode.ilon - origin.ilon);
            mc.writeVarLengthSigned(tranferNode.ilat - origin.ilat);
            mc.writeVarLengthSigned(tranferNode.getSElev() - origin.getSElev());
            origin = tranferNode;
          }
        }
      }
      mc.injectSize(sizeoffset);
    }

    while (internalReverse.size() > 0) {
      int nextIdx = 0;
      if (internalReverse.size() > 1) {
        int max32 = Integer.MIN_VALUE;
        for (int i = 0; i < internalReverse.size(); i++) {
          int id32 = mc.shrinkId(internalReverse.get(i).getIdFromPos());
          if (id32 > max32) {
            max32 = id32;
            nextIdx = i;
          }
        }
      }
      OsmNodeP target = internalReverse.remove(nextIdx);
      int sizeoffset = mc.writeSizePlaceHolder();
      mc.writeVarLengthSigned(target.ilon - ilon);
      mc.writeVarLengthSigned(target.ilat - ilat);
      mc.writeModeAndDesc(true, null);
      mc.injectSize(sizeoffset);
    }
    return hasLinks;
  }

  public String toString2() {
    return (ilon - 180000000) + "_" + (ilat - 90000000) + "_" + (selev / 4);
  }

  public long getIdFromPos() {
    return ((long) ilon) << 32 | ilat;
  }

  public boolean isBorderNode() {
    return (bits & BORDER_BIT) != 0;
  }

  public boolean hasTraffic() {
    return (bits & TRAFFIC_BIT) != 0;
  }

  /**
   * Not really count the ways, just detect if more than one
   */
  public void incWayCount() {
    if ((bits & ANY_WAY_BIT) != 0) {
      bits |= MULTI_WAY_BIT;
    }
    bits |= ANY_WAY_BIT;
  }

  public boolean isTransferNode() {
    return (bits & BORDER_BIT) == 0 && (bits & MULTI_WAY_BIT) == 0 && _linkCnt() == 2;
  }

  private int _linkCnt() {
    int cnt = 0;

    for (OsmLinkP link = getFirstLink(); link != null; link = link.getNext(this)) {
      cnt++;
    }
    return cnt;
  }

}
