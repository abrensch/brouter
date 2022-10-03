/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;


public class OsmLinkP {
  /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public byte[] descriptionBitmap;

  /**
   * The target is either the next link or the target node
   */
  protected OsmNodeP sourceNode;
  protected OsmNodeP targetNode;

  protected OsmLinkP previous;
  protected OsmLinkP next;


  public OsmLinkP(OsmNodeP source, OsmNodeP target) {
    sourceNode = source;
    targetNode = target;
  }

  protected OsmLinkP() {
  }

  public final boolean counterLinkWritten() {
    return descriptionBitmap == null;
  }

  /**
   * Set the relevant next-pointer for the given source
   */
  public void setNext(OsmLinkP link, OsmNodeP source) {
    if (sourceNode == source) {
      next = link;
    } else if (targetNode == source) {
      previous = link;
    } else {
      throw new IllegalArgumentException("internal error: setNext: unknown source");
    }
  }

  /**
   * Get the relevant next-pointer for the given source
   */
  public OsmLinkP getNext(OsmNodeP source) {
    if (sourceNode == source) {
      return next;
    } else if (targetNode == source) {
      return previous;
    } else {
      throw new IllegalArgumentException("internal error: gextNext: unknown source");
    }
  }

  /**
   * Get the relevant target-node for the given source
   */
  public OsmNodeP getTarget(OsmNodeP source) {
    if (sourceNode == source) {
      return targetNode;
    } else if (targetNode == source) {
      return sourceNode;
    } else {
      throw new IllegalArgumentException("internal error: getTarget: unknown source");
    }
  }

  /**
   * Check if reverse link for the given source
   */
  public boolean isReverse(OsmNodeP source) {
    if (sourceNode == source) {
      return false;
    } else if (targetNode == source) {
      return true;
    } else {
      throw new IllegalArgumentException("internal error: isReverse: unknown source");
    }
  }

}
