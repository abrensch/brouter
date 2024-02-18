package btools.mapcreator;

import java.io.File;
import java.io.IOException;

/**
 * RestrictionCutter does 1 step in map-processing:
 * <p>
 * - distribute restrictions into 45*30 temp files
 */
public class RestrictionCutter extends ItemCutter {
  private final NodeCutter nodeCutter;

  public RestrictionCutter(File tmpDir, NodeCutter nodeCutter)  {
    super( new File( tmpDir, "restrictions" ) );
    this.nodeCutter = nodeCutter;
  }

  public void nextRestriction(RestrictionData data) throws IOException {
    int tileIndex = nodeCutter.getTileIndexForNid(data.viaNid);
    if (tileIndex != -1) {
      data.writeTo(getOutStreamForTile(tileIndex));
    }
  }

  protected String getNameForTile(int tileIndex) {
    return nodeCutter.getBaseNameForTile(tileIndex) + ".rtl";
  }

}
