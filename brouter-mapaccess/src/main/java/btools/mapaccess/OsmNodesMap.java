/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.HashMap;
import java.util.Map;


public final class OsmNodesMap {
  private Map<OsmNode, OsmNode> hmap = new HashMap<>(4096);

  private OsmNode testKey = new OsmNode();

  public int nodesCreated;
  public long maxmem;
  private long currentmaxmem = 4000000; // start with 4 MB

  public void countNodes(OsmNode[] nodes) {
    for (int i = 0; i < nodes.length; i++) {
      OsmNode n = nodes[i];
      if (n != null && n.firstLink != null) {
        nodesCreated++;
      }
    }
  }


  public boolean isInMemoryBounds(int npaths, boolean extend) {
    long total = nodesCreated * 95L + npaths * 200L;

    if (extend) {
      total += 100000;

      // when extending, try to have 1 MB  space
      long delta = total + 1900000 - currentmaxmem;
      if (delta > 0) {
        currentmaxmem += delta;
        if (currentmaxmem > maxmem) {
          currentmaxmem = maxmem;
        }
      }
    }
    return total <= currentmaxmem;
  }

  /**
   * Get a node from the map
   *
   * @return the node for the given id if exist, else null
   */
  public OsmNode get(int ilon, int ilat) {
    testKey.iLon = ilon;
    testKey.iLat = ilat;
    return hmap.get(testKey);
  }


  public void remove(OsmNode node) {
    hmap.remove(node);
  }

  /**
   * Put a node into the map
   *
   * @return the previous node if that id existed, else null
   */
  public OsmNode put(OsmNode node) {
    return hmap.put(node, node);
  }
}
