/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;

public abstract class OsmPrePath
{
  protected OsmNode sourceNode;
  protected OsmNode targetNode;
  protected OsmLink link;
  
  public OsmPrePath next;

  public void init( OsmPath origin, OsmLink link, RoutingContext rc )
  {
    this.link = link;
    this.sourceNode = origin.getTargetNode();
    this.targetNode = link.getTarget( sourceNode );
    initPrePath(origin, rc );
  }

  protected abstract void initPrePath(OsmPath origin, RoutingContext rc );
}
