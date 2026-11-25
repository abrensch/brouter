/**
 * Container for link between two nodes
 *
 * @author ab
 */
package btools.mapaccess;


public class GraphLink {
  /**
   * The description bitmap contains the waytags (valid for both directions)
   */
  public byte[] wayDescription;

  // a link logically knows only its target, but for the reverse link, source and target are swapped
  protected GraphNode sourceNode;
  protected GraphNode targetNode;

  // same for the next-link-for-node pointer: previous applies to the reverse link
  protected GraphLink previous;
  protected GraphLink next;

  protected GraphLink() {
  }

  public GraphLink(GraphNode source, GraphNode target) {
    sourceNode = source;
    targetNode = target;
  }

  /**
   * Get the relevant target-node for the given source
   */
  public final GraphNode getTarget(GraphNode source) {
    return targetNode != source && targetNode != null ? targetNode : sourceNode;
  }

  /**
   * Get the relevant next-pointer for the given source
   */
  public final GraphLink getNext(GraphNode source) {
    return targetNode != source && targetNode != null ? next : previous;
  }

  /**
   * Reset this link for the given direction
   *
   * @return the next-in-chain link for the given source, or null if this was the last link
   */
  protected final GraphLink clear(GraphNode source) {
    GraphLink n;
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

  public final boolean isReverse(GraphNode source) {
    return sourceNode != source && sourceNode != null;
  }

  public final boolean isBidirectional() {
    return sourceNode != null && targetNode != null;
  }

  public final boolean isLinkUnused() {
    return sourceNode == null && targetNode == null;
  }
}
