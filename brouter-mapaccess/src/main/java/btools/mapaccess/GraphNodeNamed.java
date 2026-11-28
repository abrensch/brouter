/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

public class GraphNodeNamed extends GraphNode {
  public String name;
  public double radius; // radius of nogopoint (in meters)

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

