/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.GraphLink;
import btools.mapaccess.GraphNode;

public abstract class OsmPrePath {
  protected GraphNode sourceNode;
  protected GraphNode targetNode;
  protected GraphLink link;

  public OsmPrePath next;

  public void init(OsmPath origin, GraphLink link, RoutingContext rc) {
    this.link = link;
    this.sourceNode = origin.getTargetNode();
    this.targetNode = link.getTarget(sourceNode);
    initPrePath(origin, rc);
  }

  protected abstract void initPrePath(OsmPath origin, RoutingContext rc);
}
