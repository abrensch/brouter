/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;


public class OsmLink {
  /**
   * The description bitmap contains the waytags (valid for both directions)
   */
  public byte[] wayDescription;

  // a link logically knows only its target, but for the reverse link, source and target are swapped
  protected OsmNode sourceNode;
  protected OsmNode targetNode;

  // same for the next-link-for-node pointer: previous applies to the reverse link
  protected OsmLink previous;
  protected OsmLink next;

  protected OsmLink() {
  }

  public OsmLink(OsmNode source, OsmNode target) {
    sourceNode = source;
    targetNode = target;
  }

  /**
   * Get the relevant target-node for the given source
   */
  public final OsmNode getTarget(OsmNode source) {
    return targetNode != source && targetNode != null ? targetNode : sourceNode;
  }

  /**
   * Get the relevant next-pointer for the given source
   */
  public final OsmLink getNext(OsmNode source) {
    return targetNode != source && targetNode != null ? next : previous;
  }

  /**
   * Reset this link for the given direction
   */
  protected final OsmLink clear(OsmNode source) {
    OsmLink n;
    if (targetNode != null && targetNode != source) {
      n = next;
      next = null;
      targetNode = null;
    } else if (sourceNode != null && sourceNode != source) {
      n = previous;
      previous = null;
      sourceNode = null;
    } else {
      throw new IllegalArgumentException("internal error: clear: bad source");
    }
    if (sourceNode == null && targetNode == null) {
      wayDescription = null;
    }
    return n;
  }

  public final boolean isReverse(OsmNode source) {
    return sourceNode != source && sourceNode != null;
  }

  public final boolean isBidirectional() {
    return sourceNode != null && targetNode != null;
  }

  public final boolean isLinkUnused() {
    return sourceNode == null && targetNode == null;
  }
}
