package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import btools.util.DenseLongMap;
import btools.util.DiffCoderDataOutputStream;
import btools.util.TinyDenseLongMap;

/**
 * NodeFilter does 1 step in map-processing:
 * <p>
 * - filters out unused nodes according to the way file
 *
 * @author ab
 */
public class NodeFilter extends MapCreatorBase {
  private DiffCoderDataOutputStream nodesOutStream;
  private File nodeTilesOut;
  protected DenseLongMap nodebitmap;

  public void init() throws Exception {
    nodebitmap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap(512) : new TinyDenseLongMap();
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    int nnodes = data.nodes.size();
    for (int i = 0; i < nnodes; i++) {
      nodebitmap.put(data.nodes.get(i), 0);
    }
  }

  @Override
  public void nodeFileStart(File nodefile) throws Exception {
    String filename = nodefile.getName();
    File outfile = new File(nodeTilesOut, filename);
    nodesOutStream = new DiffCoderDataOutputStream(new BufferedOutputStream(new FileOutputStream(outfile)));
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    if (isRelevant(n)) {
      n.writeTo(nodesOutStream);
    }
  }

  public boolean isRelevant(NodeData n) {
    // check if node passes bitmap
    return nodebitmap.getInt(n.nid) == 0; // 0 -> bit set, -1 -> unset
  }

  @Override
  public void nodeFileEnd(File nodeFile) throws Exception {
    nodesOutStream.close();
  }
}
