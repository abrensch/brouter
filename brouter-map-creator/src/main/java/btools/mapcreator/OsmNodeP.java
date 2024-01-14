/**
 * Container for an osm node (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.TurnRestriction;

public class OsmNodeP extends OsmLinkP {
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

  public byte[] nodeDescription;

  public TurnRestriction firstRestriction;

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  public final static int DP_SURVIVOR_BIT = 64;

  private int bits = 0;

  public boolean hasBits( int mask ) {
    return (bits & mask ) != 0;
  }

  public void setBits( int mask ) {
    bits |= mask;
  }
  public short getSElev() {
    // if all bridge or all tunnel, elevation=no-data
    return hasBits(NO_BRIDGE_BIT) || hasBits(NO_TUNNEL_BIT) ? Short.MIN_VALUE : sElev;
  }

  public void addTurnRestriction(TurnRestriction tr) {
    tr.next = firstRestriction;
    firstRestriction = tr;
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

  public long getIdFromPos() {
    return ((long) iLon) << 32 | iLat;
  }

}
