package btools.mapcreator;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

/**
 * NodeFilter does 1 step in map-processing:
 * <p>
 * - filters out unused nodes according to the way file
 *
 * @author ab
 */
public class NodeFilter implements WayListener {

  protected DenseLongMap nodeBitmap;

  public void init() {
    nodeBitmap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap(512) : new TinyDenseLongMap();
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    int nnodes = data.nodes.size();
    for (int i = 0; i < nnodes; i++) {
      nodeBitmap.put(data.nodes.get(i), 0);
    }
  }

  public boolean isRelevant(NodeData n) {
    // check if node passes bitmap
    return nodeBitmap.getInt(n.nid) == 0; // 0 -> bit set, -1 -> unset
  }
}
