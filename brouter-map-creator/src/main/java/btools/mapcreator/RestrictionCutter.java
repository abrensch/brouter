package btools.mapcreator;

import java.io.File;

/**
 * RestrictionCutter writes Restrictions to tiles
 * <p>
 * - cut the way file into 45*30 - pieces
 * - enrich ways with relation information
 *
 * @author ab
 */
public class RestrictionCutter extends ItemCutter {
  private NodeCutter nodeCutter;

  public RestrictionCutter(File tmpDir, NodeCutter nodeCutter)  {
    super( new File( tmpDir, "restrictions" ) );
    this.nodeCutter = nodeCutter;
  }

  public void finish() throws Exception {
    closeTileOutStreams();
  }

  public void nextRestriction(RestrictionData data) throws Exception {
    int tileIndex = nodeCutter.getTileIndexForNid(data.viaNid);
    if (tileIndex != -1) {
      data.writeTo(getOutStreamForTile(tileIndex));
    }
  }

  protected String getNameForTile(int tileIndex) {
    String name = nodeCutter.getNameForTile(tileIndex);
    return name.substring(0, name.length() - 3) + "rtl";
  }

}
