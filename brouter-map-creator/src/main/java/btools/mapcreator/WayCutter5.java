package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

/**
 * WayCutter5 does 2 step in map-processing:
 * <p>
 * - cut the 45*30 way files into 5*5 pieces
 * - create a file containing all border node ids
 *
 * @author ab
 */
public class WayCutter5 extends MapCreatorBase {
  private DataOutputStream borderNidsOutStream;
  private DenseLongMap tileIndexMap;
  private File nodeTilesIn;
  private int lonoffset;
  private int latoffset;

  public RelationMerger relMerger;
  public NodeFilter nodeFilter;
  public NodeCutter nodeCutter;
  public RestrictionCutter5 restrictionCutter5;

  public static void main(String[] args) throws Exception {
    System.out.println("*** WayCutter5: Soft-Cut way-data into tiles");
    if (args.length != 4) {
      System.out.println("usage: java WayCutter5 <node-tiles-in> <way-tiles-in> <way-tiles-out> <border-nids-out>");
      return;
    }
    new WayCutter5().process(new File(args[0]), new File(args[1]), new File(args[2]), new File(args[3]));
  }

  public void process(File nodeTilesIn, File wayTilesIn, File wayTilesOut, File borderNidsOut) throws Exception {
    this.nodeTilesIn = nodeTilesIn;
    this.outTileDir = wayTilesOut;

    borderNidsOutStream = createOutStream(borderNidsOut);

    new WayIterator(this, true).processDir(wayTilesIn, ".wtl");

    borderNidsOutStream.close();
  }

  @Override
  public boolean wayFileStart(File wayfile) throws Exception {
    // read corresponding node-file into tileIndexMap
    String name = wayfile.getName();
    String nodefilename = name.substring(0, name.length() - 3) + "ntl";
    File nodefile = new File(nodeTilesIn, nodefilename);

    tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();
    lonoffset = -1;
    latoffset = -1;

    if (nodeCutter != null) {
      nodeCutter.nodeFileStart(null);
    }
    new NodeIterator(this, nodeCutter != null).processFile(nodefile);

    if (restrictionCutter5 != null) {
      String resfilename = name.substring(0, name.length() - 3) + "rtl";
      File resfile = new File("restrictions", resfilename);

      if (resfile.exists()) {
        // read restrictions for nodes in nodesMap
        DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(resfile)));
        int ntr = 0;
        try {
          for (; ; ) {
            RestrictionData res = new RestrictionData(di);
            restrictionCutter5.nextRestriction(res);
            ntr++;
          }
        } catch (EOFException eof) {
          di.close();
        }
        System.out.println("read " + ntr + " turn-restrictions");
      }
    }
    return true;
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    if (nodeFilter != null) {
      if (!nodeFilter.isRelevant(n)) {
        return;
      }
    }
    if (nodeCutter != null) {
      nodeCutter.nextNode(n);
    }

    tileIndexMap.put(n.nid, getTileIndex(n.ilon, n.ilat));
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    long waytileset = 0;
    int nnodes = data.nodes.size();
    int[] tiForNode = new int[nnodes];

    // determine the tile-index for each node
    for (int i = 0; i < nnodes; i++) {
      int tileIndex = tileIndexMap.getInt(data.nodes.get(i));
      if (tileIndex != -1) {
        waytileset |= (1L << tileIndex);
      }
      tiForNode[i] = tileIndex;
    }

    if (relMerger != null) {
      relMerger.nextWay(data);
    }

    // now write way to all tiles hit
    for (int tileIndex = 0; tileIndex < 54; tileIndex++) {
      if ((waytileset & (1L << tileIndex)) == 0) {
        continue;
      }
      data.writeTo(getOutStreamForTile(tileIndex));
    }

    // and write edge nodes to the border-nid file
    for (int i = 0; i < nnodes; i++) {
      int ti = tiForNode[i];
      if (ti != -1) {
        if ((i > 0 && tiForNode[i - 1] != ti) || (i + 1 < nnodes && tiForNode[i + 1] != ti)) {
          writeId(borderNidsOutStream, data.nodes.get(i));
        }
      }
    }
  }

  @Override
  public void wayFileEnd(File wayFile) throws Exception {
    closeTileOutStreams();
    if (nodeCutter != null) {
      nodeCutter.nodeFileEnd(null);
    }
    if (restrictionCutter5 != null) {
      restrictionCutter5.finish();
    }
  }

  public int getTileIndexForNid(long nid) {
    return tileIndexMap.getInt(nid);
  }

  private int getTileIndex(int ilon, int ilat) {
    int lonoff = (ilon / 45000000) * 45;
    int latoff = (ilat / 30000000) * 30;
    if (lonoffset == -1) lonoffset = lonoff;
    if (latoffset == -1) latoffset = latoff;
    if (lonoff != lonoffset || latoff != latoffset)
      throw new IllegalArgumentException("inconsistent node: " + ilon + " " + ilat);

    int lon = (ilon / 5000000) % 9;
    int lat = (ilat / 5000000) % 6;
    if (lon < 0 || lon > 8 || lat < 0 || lat > 5)
      throw new IllegalArgumentException("illegal pos: " + ilon + "," + ilat);
    return lon * 6 + lat;
  }


  protected String getNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 5 + lonoffset - 180;
    int lat = (tileIndex % 6) * 5 + latoffset - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".wt5";
  }
}
