package btools.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link DenseAreaMap}, the round-trip-only dense-area model used by GREEDY
 * via-steering. No segment data: boxes are built either from a hand-made polygon or from a
 * synthetic desirability grid.
 */
public class DenseAreaMapTest {

  @Test
  public void containsIsTrueInsideABoxAndFalseOutside() {
    // a 10000 x 10000 microdeg square box
    OsmNogoPolygon square = new OsmNogoPolygon(true);
    square.addVertex(1_000_000, 1_500_000);
    square.addVertex(1_010_000, 1_500_000);
    square.addVertex(1_010_000, 1_510_000);
    square.addVertex(1_000_000, 1_510_000);
    square.calcBoundingCircle();

    DenseAreaMap map = new DenseAreaMap(Collections.singletonList(square));

    assertTrue("a point in the middle of the box is inside", map.contains(1_005_000, 1_505_000));
    assertFalse("a point far from the box is outside", map.contains(2_000_000, 2_000_000));
  }

  private static final int CELL = 5000;

  /** Grid cell key, matching RoutingEngine's encoding: (ilon/CELL)*1e6 + (ilat/CELL). */
  private static long cellKey(int cx, int cy) {
    return (long) cx * 1_000_000L + cy;
  }

  @Test
  public void fromDesirabilityGridBuildsABoxOverADenseCluster() {
    // a compact 4x4 block of high-node-count cells (a "town"), everything else absent
    Map<Long, double[]> grid = new HashMap<>();
    for (int cx = 200; cx <= 203; cx++) {
      for (int cy = 300; cy <= 303; cy++) {
        grid.put(cellKey(cx, cy), new double[]{50, 0, 0, 0}); // slot[0] = node count
      }
    }

    DenseAreaMap map = DenseAreaMap.fromDesirabilityGrid(
      grid, CELL, /*densePercentile*/ 0.5, /*minDenseNodes*/ 12,
      /*minCells*/ 2, /*maxCells*/ 20, /*tileCells*/ 5);

    assertNotNull("dense cluster yields a box", map);
    // a point inside cell (201,301) is inside the built box
    assertTrue("box covers the cluster interior", map.contains(201 * CELL + 2500, 301 * CELL + 2500));
    // a point far away is outside
    assertFalse("box does not cover distant ground", map.contains(900 * CELL, 900 * CELL));
  }

  @Test
  public void fromDesirabilityGridReturnsNullWhenNoDenseArea() {
    // empty grid -> no map
    assertNull("empty grid yields no map",
      DenseAreaMap.fromDesirabilityGrid(new HashMap<>(), CELL, 0.5, 12, 2, 20, 5));

    // a scatter of low-node-count cells (all below the absolute floor) -> no dense area
    Map<Long, double[]> sparse = new HashMap<>();
    for (int cx = 200; cx <= 210; cx++) {
      sparse.put(cellKey(cx, 300), new double[]{1, 0, 0, 0});
    }
    assertNull("sparse grid yields no map",
      DenseAreaMap.fromDesirabilityGrid(sparse, CELL, 0.5, 12, 2, 20, 5));
  }
}
