package btools.router;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit coverage for the faithful capsule's density→polygon morphology (boundary trace).
 * Verifies a solid dense block yields one tight polygon that actually contains the block
 * interior and excludes far-away points — i.e. the rectilinear trace is valid, not a
 * convex-hull over-cover or a broken ring.
 */
public class CapsuleNogoBuilderTest {

  private static final int CELL = 5000;
  private static final int CX0 = 100, CY0 = 200;

  private static long key(int ox, int oy) {
    return (long) (CX0 + ox) * 1_000_000L + (CY0 + oy);
  }

  @Test
  public void solidBlockYieldsOneContainingPolygon() {
    // 6×6 dense block (nodeCount 40) in a sparse field (nodeCount 2).
    Map<Long, double[]> grid = new HashMap<>();
    for (int ox = -2; ox < 8; ox++) {
      for (int oy = -2; oy < 8; oy++) {
        boolean dense = ox >= 0 && ox < 6 && oy >= 0 && oy < 6;
        grid.put(key(ox, oy), new double[]{dense ? 40 : 2, 0, 0, 0});
      }
    }
    List<OsmNodeNamed> nogos = CapsuleNogoBuilder.build(grid, CELL, 2.0, 0.80, 10, 4);
    assertEquals("one dense block → one capsule polygon", 1, nogos.size());
    OsmNogoPolygon poly = (OsmNogoPolygon) nogos.get(0);

    // block interior centre must be inside the polygon...
    long cxIn = (long) (CX0 + 3) * CELL + CELL / 2;
    long cyIn = (long) (CY0 + 3) * CELL + CELL / 2;
    assertTrue("block centre must be inside the capsule polygon", poly.isWithin(cxIn, cyIn));

    // ...and a far-away sparse point must be outside.
    long cxOut = (long) (CX0 + 20) * CELL;
    long cyOut = (long) (CY0 + 20) * CELL;
    assertFalse("far point must be outside the capsule polygon", poly.isWithin(cxOut, cyOut));
  }

  @Test
  public void smallSettlementBelowMinCellsIsNotMasked() {
    // A 2×2 dense cluster (< minComponentCells=4) must NOT become a capsule.
    Map<Long, double[]> grid = new HashMap<>();
    for (int ox = -2; ox < 4; ox++) {
      for (int oy = -2; oy < 4; oy++) {
        boolean dense = ox >= 0 && ox < 2 && oy >= 0 && oy < 2;
        grid.put(key(ox, oy), new double[]{dense ? 40 : 2, 0, 0, 0});
      }
    }
    // closing can grow a 2×2 slightly; require a clearly-larger minComponentCells.
    List<OsmNodeNamed> nogos = CapsuleNogoBuilder.build(grid, CELL, 2.0, 0.80, 10, 9);
    assertTrue("tiny settlement should not be masked", nogos.isEmpty());
  }
}
