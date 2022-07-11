package btools.mapcreator;

import java.io.File;

/**
 * NodeCutter does 1 step in map-processing:
 * <p>
 * - cuts the 45*30 node tiles into 5*5 pieces
 *
 * @author ab
 */
public class NodeCutter extends MapCreatorBase {
  private int lonoffset;
  private int latoffset;

  public static void main(String[] args) throws Exception {
    System.out.println("*** NodeCutter: Cut big node-tiles into 5x5 tiles");
    if (args.length != 2) {
      System.out.println("usage: java NodeCutter <node-tiles-in> <node-tiles-out>");
      return;
    }
    new NodeCutter().process(new File(args[0]), new File(args[1]));
  }

  public void init(File nodeTilesOut) {
    this.outTileDir = nodeTilesOut;
  }

  public void process(File nodeTilesIn, File nodeTilesOut) throws Exception {
    init(nodeTilesOut);

    new NodeIterator(this, true).processDir(nodeTilesIn, ".tlf");
  }

  @Override
  public void nodeFileStart(File nodefile) throws Exception {
    lonoffset = -1;
    latoffset = -1;
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    n.writeTo(getOutStreamForTile(getTileIndex(n.ilon, n.ilat)));
  }

  @Override
  public void nodeFileEnd(File nodeFile) throws Exception {
    closeTileOutStreams();
  }

  private int getTileIndex(int ilon, int ilat) {
    int lonoff = (ilon / 45000000) * 45;
    int latoff = (ilat / 30000000) * 30;
    if (lonoffset == -1) lonoffset = lonoff;
    if (latoffset == -1) latoffset = latoff;
    if (lonoff != lonoffset || latoff != latoffset)
      throw new IllegalArgumentException("inconsistent node: " + ilon + " " + ilat);

    int lon = (ilon / 5000000) % 9;
    int lat = (ilat / 5000000) % 6;
    if (lon < 0 || lon > 8 || lat < 0 || lat > 5)
      throw new IllegalArgumentException("illegal pos: " + ilon + "," + ilat);
    return lon * 6 + lat;
  }


  protected String getNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 5 + lonoffset - 180;
    int lat = (tileIndex % 6) * 5 + latoffset - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".n5d";
  }

}
