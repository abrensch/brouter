package btools.mapcreator;

import java.io.File;

/**
 * NodeCutter does 1 step in map-processing:
 * <p>
 * - cuts the 45*30 node tiles into 5*5 pieces
 *
 * @author ab
 */
public class NodeCutter extends MapCreatorBase implements NodeListener {
  private int lonOffset;
  private int latOffset;

  public void init(File nodeTilesOut) {
    this.outTileDir = nodeTilesOut;
  }

  @Override
  public void nodeFileStart(File nodefile) {
    lonOffset = -1;
    latOffset = -1;
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    n.writeTo(getOutStreamForTile(getTileIndex(n.iLon, n.iLat)));
  }

  @Override
  public void nodeFileEnd(File nodeFile) throws Exception {
    closeTileOutStreams();
  }

  private int getTileIndex(int ilon, int ilat) {
    int lonoff = (ilon / 45000000) * 45;
    int latoff = (ilat / 30000000) * 30;
    if (lonOffset == -1) lonOffset = lonoff;
    if (latOffset == -1) latOffset = latoff;
    if (lonoff != lonOffset || latoff != latOffset)
      throw new IllegalArgumentException("inconsistent node: " + ilon + " " + ilat);

    int lon = (ilon / 5000000) % 9;
    int lat = (ilat / 5000000) % 6;
    if (lon < 0 || lon > 8 || lat < 0 || lat > 5)
      throw new IllegalArgumentException("illegal pos: " + ilon + "," + ilat);
    return lon * 6 + lat;
  }


  protected String getNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 5 + lonOffset - 180;
    int lat = (tileIndex % 6) * 5 + latOffset - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".n5d";
  }

}
