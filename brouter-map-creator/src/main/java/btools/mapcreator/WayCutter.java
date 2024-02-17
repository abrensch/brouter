package btools.mapcreator;

import java.io.File;

/**
 * WayCutter does 2 step in map-processing:
 * <p>
 * - cut the way file into 45*30 - pieces
 * - enrich ways with relation information
 *
 * @author ab
 */
public class WayCutter extends ItemCutter implements WayListener {

  private final NodeCutter nodeCutter;

  public WayCutter(File tmpDir, NodeCutter nodeCutter) {
    super( new File( tmpDir, "ways" ) );
    this.nodeCutter = nodeCutter;
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    long waytileset = 0;
    int nnodes = data.nodes.size();

    // determine the tile-index for each node
    for (int i = 0; i < nnodes; i++) {
      int tileIndex = nodeCutter.getTileIndexForNid(data.nodes.get(i));
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

  public String getNameForTile(int tileIndex) {
    return getBaseNameForTile(tileIndex) + ".wtl";
  }

}
