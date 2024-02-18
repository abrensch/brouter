package btools.mapcreator;

import btools.mapaccess.OsmFile;
import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

import java.io.File;

/**
 * NodeCutter5 does 3 steps in map-processing:
 * <p>
 * - filter out non-relevant nodes
 * - cuts the 45*30 node tiles into 5*5 pieces
 * - build a memory map with the (sub-)tile index for each node
 *
 * @author ab
 */
public class NodeCutter5 extends ItemCutter implements ItemListener {
  private final DenseLongMap tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();

  private final DenseLongMap nodeFilter;

  private int lonOffset = -1;
  private int latOffset = -1;

  public NodeCutter5(File tmpDir, DenseLongMap nodeFilter) {
    super( new File( tmpDir, "nodes55" ) );
    this.nodeFilter = nodeFilter;
  }

  @Override
  public void nextNode(NodeData n) throws Exception {

    boolean isRelevant = nodeFilter.getInt(n.nid) == 0; // 0 -> bit set, -1 -> unset
    if (!isRelevant) {
      return;
    }

    int tileIndex = getTile55Index(n.iLon, n.iLat);
    n.writeTo(getOutStreamForTile(tileIndex));
    tileIndexMap.put(n.nid, tileIndex);
  }

  public int getTileIndexForNid(long nid) {
    return tileIndexMap.getInt(nid);
  }


  protected String getNameForTile(int tileIndex) {
    return getBaseNameForTile55(tileIndex) + ".n5d";
  }

  private int getTile55Index(int ilon, int ilat) {
    int lonoff = (ilon / 45000000) * 45;
    int latoff = (ilat / 30000000) * 30;
    if (lonOffset == -1) lonOffset = lonoff;
    if (latOffset == -1) latOffset = latoff;
    if (lonoff != lonOffset || latoff != latOffset)
      throw new IllegalArgumentException("inconsistent node: " + ilon + " " + ilat);

    int lon = (ilon / 5000000) % 9;
    int lat = (ilat / 5000000) % 6;
    return lon * 6 + lat;
  }

  public String getBaseNameForTile55(int tileIndex) {
    int lon = (tileIndex / 6) * 5 + lonOffset - 180;
    int lat = (tileIndex % 6) * 5 + latOffset - 90;
    return OsmFile.getBaseName( lon,lat);
  }

}
