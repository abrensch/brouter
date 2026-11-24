/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.GraphNode;
import btools.util.CheapRuler;

public class GraphNodeNamed extends GraphNode {
  public String name;
  public double radius; // radius of nogopoint (in meters)
  public boolean isNogo = false;

  public GraphNodeNamed(int iLon, int iLat) {
    super(iLon, iLat);
  }

  public GraphNodeNamed(GraphNode n) {
    super(n.iLon, n.iLat);
  }

  @Override
  public String toString() {
    return iLon + "," + iLat + "," + name;
  }

}
