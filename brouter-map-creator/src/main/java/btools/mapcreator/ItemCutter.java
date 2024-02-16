/**
 * common base class for the map-filters
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.OsmFile;
import btools.util.DiffCoderDataOutputStream;

import java.io.*;

public abstract class ItemCutter {
  private DiffCoderDataOutputStream[] tileOutStreams;
  protected final File outTileDir;

  private int lonOffset;
  private int latOffset;

  protected ItemCutter(File outTileDir) {
    if ( outTileDir != null && !outTileDir.exists() && !outTileDir.mkdir() ) {
      throw new RuntimeException( "directory " + outTileDir + " cannot be created" );
    }
    this.outTileDir = outTileDir;
  }

  protected DiffCoderDataOutputStream createOutStream(File outFile) throws IOException {
    return new DiffCoderDataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

  protected DiffCoderDataOutputStream getOutStreamForTile(int tileIndex) throws Exception {
    if (tileOutStreams == null) {
      tileOutStreams = new DiffCoderDataOutputStream[64];
    }

    if (tileOutStreams[tileIndex] == null) {
      tileOutStreams[tileIndex] = createOutStream(new File(outTileDir, getNameForTile(tileIndex)));
    }
    return tileOutStreams[tileIndex];
  }

  protected String getNameForTile(int tileIndex) {
    throw new IllegalArgumentException("getNameForTile not implemented");
  }

  protected void closeTileOutStreams() throws Exception {
    if (tileOutStreams == null) {
      return;
    }
    for (int tileIndex = 0; tileIndex < tileOutStreams.length; tileIndex++) {
      if (tileOutStreams[tileIndex] != null) tileOutStreams[tileIndex].close();
      tileOutStreams[tileIndex] = null;
    }
  }

  public void fileStart() {
    lonOffset = -1;
    latOffset = -1;
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
