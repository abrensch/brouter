package btools.mapcreator;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

import java.io.File;

/**
 * NodeCutter5 does 1 step in map-processing:
 * <p>
 * - cuts the 45*30 node tiles into 5*5 pieces
 *
 * @author ab
 */
public class NodeCutter5 extends ItemCutter implements NodeListener {
  private final DenseLongMap tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();

  private final DenseLongMap nodeFilter;

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

  @Override
  public void nodeFileEnd(File nodeFile) throws Exception {
    closeTileOutStreams();
  }

  protected String getNameForTile(int tileIndex) {
    return getBaseNameForTile55(tileIndex) + ".n5d";
  }

}
