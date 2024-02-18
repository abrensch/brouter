/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.OsmFile;
import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

import java.io.*;

/**
 * NodeCutter does two step in map-processing:
 * <p>
 * - distribute nodes into 45*30 temp files
 * - build a memory map with the tile index for each node
 */
public class NodeCutter extends ItemCutter {
  private final DenseLongMap tileIndexMap;

  public NodeCutter(File tmpDir ) {
    super( new File( tmpDir, "nodes" ) );

    // *** read all nodes into tileIndexMap
    tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();
  }

  public int getTileIndexForNid(long nid) {
    return tileIndexMap.getInt(nid);
  }

  public void nextNode(NodeData n) throws Exception {
    // write node to file
    int tileIndex = getTileIndex(n.iLon, n.iLat);
    if (tileIndex >= 0) {
      n.writeTo(getOutStreamForTile(tileIndex));
      tileIndexMap.put(n.nid, tileIndex);
    }
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

  public String getBaseNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 45 - 180;
    int lat = (tileIndex % 6) * 30 - 90;
    return OsmFile.getBaseName( lon,lat);
  }

}
