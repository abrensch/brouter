package btools.mapcreator;

import java.io.File;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

/**
 * WayCutter does 2 step in map-processing:
 * <p>
 * - cut the way file into 45*30 - pieces
 * - enrich ways with relation information
 *
 * @author ab
 */
public class WayCutter extends MapCreatorBase {
  private DenseLongMap tileIndexMap;

  public static void main(String[] args) throws Exception {
    System.out.println("*** WayCutter: Soft-Cut way-data into tiles");
    if (args.length != 3) {
      System.out.println("usage: java WayCutter <node-tiles-in> <way-file-in> <way-tiles-out>");

      return;
    }
    new WayCutter().process(new File(args[0]), new File(args[1]), new File(args[2]));
  }

  public void process(File nodeTilesIn, File wayFileIn, File wayTilesOut) throws Exception {
    init(wayTilesOut);

    new NodeIterator(this, false).processDir(nodeTilesIn, ".tlf");

    // *** finally process the way-file, cutting into pieces
    new WayIterator(this, true).processFile(wayFileIn);
    finish();
  }

  public void init(File wayTilesOut) throws Exception {
    this.outTileDir = wayTilesOut;

    // *** read all nodes into tileIndexMap
    tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();
  }

  public void finish() throws Exception {
    closeTileOutStreams();
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    tileIndexMap.put(n.nid, getTileIndex(n.ilon, n.ilat));
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    long waytileset = 0;
    int nnodes = data.nodes.size();

    // determine the tile-index for each node
    for (int i = 0; i < nnodes; i++) {
      int tileIndex = tileIndexMap.getInt(data.nodes.get(i));
      if (tileIndex != -1) {
        waytileset |= (1L << tileIndex);
      }
    }

    // now write way to all tiles hit
    for (int tileIndex = 0; tileIndex < 54; tileIndex++) {
      if ((waytileset & (1L << tileIndex)) == 0) {
        continue;
      }
      data.writeTo(getOutStreamForTile(tileIndex));
    }
  }


  public int getTileIndexForNid(long nid) {
    return tileIndexMap.getInt(nid);
  }

  private int getTileIndex(int ilon, int ilat) {
    int lon = ilon / 45000000;
    int lat = ilat / 30000000;
    if (lon < 0 || lon > 7 || lat < 0 || lat > 5)
      throw new IllegalArgumentException("illegal pos: " + ilon + "," + ilat);
    return lon * 6 + lat;
  }

  public String getNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 45 - 180;
    int lat = (tileIndex % 6) * 30 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".wtl";
  }

}
