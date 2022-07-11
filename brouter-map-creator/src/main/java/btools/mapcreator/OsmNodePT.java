/**
 * Container for an osm node with tags or restrictions (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;


public class OsmNodePT extends OsmNodeP {
  public byte[] descriptionBits;

  public RestrictionData firstRestriction;

  public OsmNodePT() {
  }

  public OsmNodePT(OsmNodeP n) {
    ilat = n.ilat;
    ilon = n.ilon;
    selev = n.selev;
    bits = n.bits;
  }

  public OsmNodePT(byte[] descriptionBits) {
    this.descriptionBits = descriptionBits;
  }

  @Override
  public final byte[] getNodeDecsription() {
    return descriptionBits;
  }

  @Override
  public final RestrictionData getFirstRestriction() {
    return firstRestriction;
  }

  @Override
  public boolean isTransferNode() {
    return false; // always have descriptionBits so never transfernode
  }

}
