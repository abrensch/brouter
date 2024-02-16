package btools.mapcreator;

import java.io.File;

/**
 * RestrictionCutter5 does 1 step in map-processing:
 * <p>
 * - cut the 45*30 restriction files into 5*5 pieces
 *
 * @author ab
 */
public class RestrictionCutter5 extends ItemCutter {
  private NodeCutter5 nodeCutter5;

  public RestrictionCutter5(File tmpDir, NodeCutter5 nodeCutter5) {
    super( new File( tmpDir, "restrictions55" ) );
    this.nodeCutter5 = nodeCutter5;
  }

  public void finish() throws Exception {
    closeTileOutStreams();
  }


  public void nextRestriction(RestrictionData data) throws Exception {
    int tileIndex = nodeCutter5.getTileIndexForNid(data.viaNid);
    if (tileIndex != -1) {
      System.out.println( "tileIndex=" + tileIndex);
      data.writeTo(getOutStreamForTile(tileIndex));
    }
  }

  protected String getNameForTile(int tileIndex) {
    return getBaseNameForTile55(tileIndex) + ".rt5";
  }
}
