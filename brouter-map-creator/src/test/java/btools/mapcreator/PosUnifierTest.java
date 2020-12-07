package btools.mapcreator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PosUnifierTest {

  @Test
  public void testHgtFileNameComposition() {
    int[] lats = {-90, -89, -1, -0, 0, 1, 89, 90, 0, 0, -1, -1};
    int[] lons = {-180, -179, -1, -0, 0, 1, 179, 180, 0, -1, -1, 0};
    String[] expected = {
        "S90W180",
        "S89W179",
        "S01W001",
        "N00E000",
        "N00E000",
        "N01E001",
        "N89E179",
        "N90E180",


        "N00E000",
        "N00W001",
        "S01W001",
        "S01E000"
    };
    PosUnifier unifier = new PosUnifier();
    for (int i = 0; i < expected.length; i++) {
      String tile = unifier.hgtFileName(lons[i], lats[i]);
      assert (tile.length() == 7);
      assert (tile.equals(expected[i]));
      System.out.println(tile);
    }
  }

  @Test
  public void testIndexingOfDoubleValues() {
    double[] coords = {-180.0, -179.5, -1.5, -1.0, -0.5, 0.0, 0.1, 1.5, 179.9, 180.0}; // 180.0 exactly: there is no tile 180E
    int[] expected = {-180, -180, -2, -1, -1, 0, 0, 1, 179, 179};
    PosUnifier unifier = new PosUnifier();
    for (int i = 0; i < expected.length; i++) {
      assert (unifier.indexHgt(coords[i]) == expected[i]);
    }
  }

  @Test
  public void testConversionCoordinates() {
    double coord = -180.0;
    PosUnifier unifier = new PosUnifier();
    while (coord < 180.0) {
      NodeData nd = new NodeData(1, coord, coord);
      assert (Math.abs(unifier.doublelat(nd.ilat) - coord) < 1E-5);
      assert (Math.abs(unifier.doublelon(nd.ilon) - coord) < 1E-5);
      coord += 0.00001;
    }
  }

  // integration test - test data generation part
  // the idea here is to query PosUnifier with coordinates in ilat, ilon format,
  // then query again returned SrtmRasterObject for coordinates in double format
  // print values in .csv format so we can assert values with Asamm library for .hgt files

  //
  // ilat = (int)( ( lat + 90. )*1000000. + 0.5);
  // ilon = (int)( ( lon + 180. )*1000000. + 0.5);
  //

  private final List<Coord> coords = new ArrayList<Coord>();

  private void addSelected() {
    coords.add(new Coord(48.0, 12.0));
    coords.add(new Coord(48.0, 13.0));
    coords.add(new Coord(49.0, 12.0));
    coords.add(new Coord(49.0, 13.0));
  }

  private void addRandom(int n) {
    Random rnd = new Random();
    int count = 0;
    while (count < n) {
      coords.add(new Coord(48.0 + rnd.nextDouble(), 12.0 + rnd.nextDouble()));
      count++;
    }
  }

  @Test
  public void generateIntegrationTestData() throws Exception {
    addSelected();
    addRandom(20);
    double[] elevs = new double[coords.size()];
    PosUnifier unifier = new PosUnifier();
    unifier.resetSrtm();
    unifier.setSrtmdir(System.getenv("SRTM_FILES_ROOT_ESRI_ASAMM"));
    for (int i = 0; i < coords.size(); i++) {
      int ilat = (int) ((coords.get(i).lat + 90.) * 1000000. + 0.5);
      int ilon = (int) ((coords.get(i).lon + 180.) * 1000000. + 0.5);
      SrtmRaster raster = unifier.srtmForNode(ilon, ilat);
      raster.usingWeights = false;
      double elevation = raster.getElevation(ilon, ilat);
      elevs[i] = elevation;
    }
    // output
    for (int i = 0; i < coords.size(); i++) {
      System.out.println(coords.get(i).toString() + (elevs[i] / 4)); // ???
    }
  }

  @Test
  public void printFirstRow() throws Exception {
    PosUnifier unifier = new PosUnifier();
    unifier.resetSrtm();
    unifier.setSrtmdir(System.getenv("SRTM_FILES_ROOT_ESRI_ASAMM"));
    int ilat = (int) ((48.0 + 90.) * 1000000. + 0.5);
    int ilon = (int) ((12.0 + 180.) * 1000000. + 0.5);
    SrtmRaster raster = unifier.srtmForNode(ilon, ilat);
    raster.usingWeights = false;
    for (int i = 0; i < 1203; i++) {
      System.out.print(raster.eval_array[i] + " ");
    }
  }

  class Coord {
    protected Coord(double lat, double lon) {
      this.lat = lat;
      this.lon = lon;
    }

    double lat;
    double lon;

    @Override
    public String toString() {
      return this.lat + "," + this.lon + ",";
    }
  }
}
