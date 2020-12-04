package btools.mapcreator;

import org.junit.Test;

public class PosUnifierTest {

  @Test
  public void testHgtFileNameComposition(){
    int[] lats = {-90,-89,-1,-0,0,1,89,90,   0,0,-1,-1};
    int[] lons = {-180,-179,-1,-0,0,1,179,180,   0,-1,-1,0};
    String [] expected = {
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
    for (int i = 0; i < expected.length; i++){
      String tile = unifier.hgtFileName(lons[i], lats[i]);
      assert (tile.length() == 7);
      assert (tile.equals(expected[i]));
      System.out.println(tile);
    }
  }

  @Test
  public void testIndexingOfDoubleValues(){
    double[] coords = {-180.0, -179.5, -1.5, -1.0, -0.5, 0.0, 0.1, 1.5, 179.9, 180.0}; // 180.0 exactly: there is no tile 180E
    int [] expected = {-180, -180, -2, -1, -1, 0, 0, 1, 179, 179};
    PosUnifier unifier = new PosUnifier();
    for (int i = 0; i < expected.length; i++) {
      assert(unifier.indexHgt(coords[i]) == expected[i]);
    }
  }

  @Test
  public void testConversionCoordinates(){
    double coord = -180.0;
    PosUnifier unifier = new PosUnifier();
    while(coord < 180.0){
      NodeData nd = new NodeData(1, coord, coord);
      assert(Math.abs(unifier.doublelat(nd.ilat) - coord) < 1E-5);
      assert(Math.abs(unifier.doublelon(nd.ilon) - coord) < 1E-5);
      coord += 0.00001;
    }
  }
}
