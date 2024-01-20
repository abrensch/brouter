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

  private OsmLinkHolder reverselinkholder = null;
  private OsmLinkHolder firstlinkholder = null;

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
    /* if ( n2 != null && n2 != source )
    {
      return n2;
    }
    else if ( n1 != null && n1 != source )
    {
      return n1;
    }
    else
    {
      new Throwable( "ups" ).printStackTrace();
      throw new IllegalArgumentException( "internal error: getTarget: unknown source; " + source + " n1=" + n1 + " n2=" + n2 );
    } */
  }

  /**
   * Get the relevant next-pointer for the given source
   */
  public final OsmLink getNext(OsmNode source) {
    return targetNode != source && targetNode != null ? next : previous;
    /* if ( n2 != null && n2 != source )
    {
      return next;
    }
    else if ( n1 != null && n1 != source )
    {
      return previous;
    }
    else
    {
      throw new IllegalArgumentException( "internal error: gextNext: unknown source" );
    } */
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
      firstlinkholder = null;
    } else if (sourceNode != null && sourceNode != source) {
      n = previous;
      previous = null;
      sourceNode = null;
      reverselinkholder = null;
    } else {
      throw new IllegalArgumentException("internal error: setNext: unknown source");
    }
    if (sourceNode == null && targetNode == null) {
      wayDescription = null;
    }
    return n;
  }

  public final void setFirstLinkHolder(OsmLinkHolder holder, OsmNode source) {
    if (targetNode != null && targetNode != source) {
      firstlinkholder = holder;
    } else if (sourceNode != null && sourceNode != source) {
      reverselinkholder = holder;
    } else {
      throw new IllegalArgumentException("internal error: setFirstLinkHolder: unknown source");
    }
  }

  public final OsmLinkHolder getFirstLinkHolder(OsmNode source) {
    if (targetNode != null && targetNode != source) {
      return firstlinkholder;
    } else if (sourceNode != null && sourceNode != source) {
      return reverselinkholder;
    } else {
      throw new IllegalArgumentException("internal error: getFirstLinkHolder: unknown source");
    }
  }

  public final boolean isReverse(OsmNode source) {
    return sourceNode != source && sourceNode != null;
    /* if ( n2 != null && n2 != source )
    {
      return false;
    }
    else if ( n1 != null && n1 != source )
   {
      return true;
    }
    else
    {
      throw new IllegalArgumentException( "internal error: isReverse: unknown source" );
    } */
  }

  public final boolean isBidirectional() {
    return sourceNode != null && targetNode != null;
  }

  public final boolean isLinkUnused() {
    return sourceNode == null && targetNode == null;
  }

  public final void addLinkHolder(OsmLinkHolder holder, OsmNode source) {
    OsmLinkHolder firstHolder = getFirstLinkHolder(source);
    if (firstHolder != null) {
      holder.setNextForLink(firstHolder);
    }
    setFirstLinkHolder(holder, source);
  }

}
