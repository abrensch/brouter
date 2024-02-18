/**
 * common base class for the map-filters
 *
 * @author ab
 */
package btools.mapcreator;

import btools.statcoding.BitOutputStream;

import java.io.*;

/**
 * ItemCutter is the abstract base for all classes
 * that distribute osm items to (sub-)tiles.
 *
 * It does the handling of the file-handles
 * where the items are written to.
 */
public abstract class ItemCutter {
  private final BitOutputStream[] tileOutStreams = new BitOutputStream[64];
  protected final File outTileDir;

  protected ItemCutter(File outTileDir) {
    if ( outTileDir != null && !outTileDir.exists() && !outTileDir.mkdir() ) {
      throw new RuntimeException( "directory " + outTileDir + " cannot be created" );
    }
    this.outTileDir = outTileDir;
  }

  public BitOutputStream createOutStream(File outFile) throws IOException {
    return new BitOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

  protected BitOutputStream getOutStreamForTile(int tileIndex) throws Exception {
    if (tileOutStreams[tileIndex] == null) {
      tileOutStreams[tileIndex] = createOutStream(new File(outTileDir, getNameForTile(tileIndex)));
    }
    return tileOutStreams[tileIndex];
  }

  protected abstract String getNameForTile(int tileIndex);

  protected void closeTileOutStreams() throws Exception {
    for (int i = 0; i<tileOutStreams.length; i++ ) {
      BitOutputStream bos = tileOutStreams[i];
      if (bos != null) {
        bos.encodeVarBytes( 0L );
        bos.close();
        tileOutStreams[i] = null;
      }
    }
  }
}
