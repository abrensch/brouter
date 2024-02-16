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
  private DenseLongMap tileIndexMap;

  private NodeFilter nodeFilter;

  public NodeCutter5(File tmpDir, NodeFilter nodeFilter) {
    super( new File( tmpDir, "nodes55" ) );
    this.nodeFilter = nodeFilter;
  }

  @Override
  public void nodeFileStart(File nodefile) {
    fileStart();

    tileIndexMap = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap() : new TinyDenseLongMap();
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    if (!nodeFilter.isRelevant(n)) {
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
