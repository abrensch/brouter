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

  public static void main(String[] args) throws Exception {
    System.out.println("*** NodeFilter: Filter way related nodes");
    if (args.length != 3) {
      System.out.println("usage: java NodeFilter <node-tiles-in> <way-file-in> <node-tiles-out>");
      return;
    }

    new NodeFilter().process(new File(args[0]), new File(args[1]), new File(args[2]));
  }

  public void init() throws Exception {
    nodebitmap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap(512) : new TinyDenseLongMap();
  }

  public void process(File nodeTilesIn, File wayFileIn, File nodeTilesOut) throws Exception {
    init();

    this.nodeTilesOut = nodeTilesOut;

    // read the wayfile into a bitmap of used nodes
    new WayIterator(this, false).processFile(wayFileIn);

    // finally filter all node files
    new NodeIterator(this, true).processDir(nodeTilesIn, ".tls");
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
