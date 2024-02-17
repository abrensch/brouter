/**
 * common base class for the map-filters
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.OsmFile;
import btools.statcoding.BitOutputStream;

import java.io.*;
import java.util.Arrays;

public abstract class ItemCutter {
  private final BitOutputStream[] tileOutStreams = new BitOutputStream[64];
  protected final File outTileDir;

  private int lonOffset = -1;
  private int latOffset = -1;

  protected ItemCutter(File outTileDir) {
    if ( outTileDir != null && !outTileDir.exists() && !outTileDir.mkdir() ) {
      throw new RuntimeException( "directory " + outTileDir + " cannot be created" );
    }
    this.outTileDir = outTileDir;
  }

  protected BitOutputStream createOutStream(File outFile) throws IOException {
    return new BitOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

  protected BitOutputStream getOutStreamForTile(int tileIndex) throws Exception {
    if (tileOutStreams[tileIndex] == null) {
      tileOutStreams[tileIndex] = createOutStream(new File(outTileDir, getNameForTile(tileIndex)));
    }
    BitOutputStream bos = tileOutStreams[tileIndex];
    bos.encodeVarBytes(1L);
    return tileOutStreams[tileIndex];
  }

  protected String getNameForTile(int tileIndex) {
    throw new IllegalArgumentException("getNameForTile not implemented");
  }

  protected void closeTileOutStreams() throws Exception {
    for (BitOutputStream bos : tileOutStreams) {
      if (bos != null) {
        bos.encodeVarBytes( 0L );
        bos.close();
      }
    }
  }

  protected int getTile55Index(int ilon, int ilat) {
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

  protected String getBaseNameForTile55(int tileIndex) {
    int lon = (tileIndex / 6) * 5 + lonOffset - 180;
    int lat = (tileIndex % 6) * 5 + latOffset - 90;
    return OsmFile.getBaseName( lon,lat);
  }

  public String getBaseNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 45 - 180;
    int lat = (tileIndex % 6) * 30 - 90;
    return OsmFile.getBaseName( lon,lat);
  }


}
