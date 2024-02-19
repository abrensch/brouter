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
    if ( !outTileDir.exists() && !outTileDir.mkdir() ) {
      throw new RuntimeException( "directory " + outTileDir + " cannot be created" );
    }
    this.outTileDir = outTileDir;
  }

  public BitOutputStream createOutStream(File outFile) throws IOException {
    return new BitOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

  protected BitOutputStream getOutStreamForTile(int tileIndex) throws IOException {
    if (tileOutStreams[tileIndex] == null) {
      tileOutStreams[tileIndex] = createOutStream(new File(outTileDir, getNameForTile(tileIndex)));
    }
    return tileOutStreams[tileIndex];
  }

  protected static File fileFromTemplate(File template, File dir, String suffix) {
    String filename = template.getName();
    filename = filename.substring(0, filename.length() - 3) + suffix;
    return new File(dir, filename);
  }

  protected String getNameForTile(int tileIndex) {
    throw new RuntimeException( "getNameForTile not implemented" );
  }

  protected void closeTileOutStreams() throws IOException {
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
