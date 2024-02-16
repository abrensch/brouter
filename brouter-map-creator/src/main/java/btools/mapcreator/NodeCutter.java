/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

import java.io.*;

public class NodeCutter extends ItemCutter implements NodeListener {
  private final DenseLongMap tileIndexMap;

  public NodeCutter(File tmpDir ) {
    super( new File( tmpDir, "nodes" ) );

    // *** read all nodes into tileIndexMap
    tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();
  }

  public int getTileIndexForNid(long nid) {
    return tileIndexMap.getInt(nid);
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    // write node to file
    int tileIndex = getTileIndex(n.iLon, n.iLat);
    if (tileIndex >= 0) {
      n.writeTo(getOutStreamForTile(tileIndex));
      tileIndexMap.put(n.nid, tileIndex);
    }
  }

  @Override
  public void nodeFileEnd(File nodeFile) throws Exception {
    closeTileOutStreams();
  }

  private int getTileIndex(int ilon, int ilat) {
    int lon = ilon / 45000000;
    int lat = ilat / 30000000;
    if (lon < 0 || lon > 7 || lat < 0 || lat > 5) {
      System.out.println("warning: ignoring illegal pos: " + ilon + "," + ilat);
      return -1;
    }
    return lon * 6 + lat;
  }

  protected String getNameForTile(int tileIndex) {
    return getBaseNameForTile(tileIndex) +  ".ntl";
  }
}
