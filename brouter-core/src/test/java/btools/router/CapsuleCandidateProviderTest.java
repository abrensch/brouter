package btools.router;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.router.RoundTripCandidateProvider.CandidatePoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit coverage for the urban-capsule loop-planning prototype's candidate steering.
 *
 * <p>Builds a synthetic 7×7 density/elevation grid (cell key → {@code [nodeCount,
 * prefSum, eleSum, eleCount]}) matching what {@code RoutingEngine} accumulates: a
 * dense 3×3 core (a "city"), a sparse outer frame, and elevation that rises with
 * the row. Then it asserts that {@link CapsuleCandidateProvider} classifies cells
 * into dense / portal / open and rewards higher ground — without needing real OSM
 * tiles.
 */
public class CapsuleCandidateProviderTest {

  /** Must match {@code RoutingEngine.DESIRABILITY_CELL}. */
  private static final int CELL = 5000;
  private static final int CX0 = 100, CY0 = 200;

  private static long key(int ox, int oy) {
    return (long) (CX0 + ox) * 1_000_000L + (CY0 + oy);
  }

  private static int ilon(int ox) { return (CX0 + ox) * CELL + CELL / 2; }

  private static int ilat(int oy) { return (CY0 + oy) * CELL + CELL / 2; }

  /** Inner 3×3 (ox,oy ∈ 2..4) dense (40 nodes); rest sparse (3). Elevation rises with oy. */
  private Map<Long, double[]> buildGrid() {
    Map<Long, double[]> grid = new HashMap<>();
    for (int ox = 0; ox < 7; ox++) {
      for (int oy = 0; oy < 7; oy++) {
        boolean dense = ox >= 2 && ox <= 4 && oy >= 2 && oy <= 4;
        double nodes = dense ? 40 : 3;
        double meanEle = 200 + oy * 10; // higher oy = higher ground
        // [nodeCount, prefSum, eleSum, eleCount]
        grid.put(key(ox, oy), new double[]{nodes, nodes * 0.5, meanEle * nodes, nodes});
      }
    }
    return grid;
  }

  private static CandidatePoint at(int ox, int oy) {
    CandidatePoint cp = new CandidatePoint();
    cp.ilon = ilon(ox);
    cp.ilat = ilat(oy);
    return cp;
  }

  /** Returns a fixed candidate list regardless of step geometry. */
  private static final class StubFallback implements RoundTripCandidateProvider {
    private final List<CandidatePoint> pts;

    StubFallback(List<CandidatePoint> pts) { this.pts = pts; }

    @Override
    public List<CandidatePoint> candidatesForStep(
        int fromIlon, int fromIlat, double airRadius, int step, int totalSteps,
        int startIlon, int startIlat, double startDirection, OsmTrack refTrack) {
      return pts;
    }
  }

  @Test
  public void classifiesDenseBoundaryAndOpenCells() {
    Map<Long, double[]> grid = buildGrid();
    CandidatePoint dense = at(3, 3);  // city core
    CandidatePoint portal = at(1, 3); // sparse, adjacent to the core
    CandidatePoint open = at(0, 0);   // sparse, two cells from any dense cell

    List<CandidatePoint> list = new ArrayList<>();
    list.add(dense);
    list.add(portal);
    list.add(open);
    CapsuleCandidateProvider p = new CapsuleCandidateProvider(grid, new StubFallback(list), CELL);

    assertEquals("the 3×3 core is the capsule", 9, p.denseCellCount());
    assertEquals("the ring around the core is the portal band", 16, p.boundaryCellCount());

    p.candidatesForStep(0, 0, 1000, 1, 5, 0, 0, -1, null);

    assertEquals("dense interior gets no capsule reward", 0.0, dense.capsuleReward, 1e-9);
    assertEquals("boundary portal gets full reward", 1.0, portal.capsuleReward, 1e-9);
    assertEquals("open countryside gets the mild reward", 0.5, open.capsuleReward, 1e-9);
  }

  @Test
  public void rewardsHigherGround() {
    Map<Long, double[]> grid = buildGrid();
    CandidatePoint low = at(0, 0);  // valley floor (oy=0)
    CandidatePoint high = at(0, 6); // high ground (oy=6), still open

    List<CandidatePoint> list = new ArrayList<>();
    list.add(low);
    list.add(high);
    CapsuleCandidateProvider p = new CapsuleCandidateProvider(grid, new StubFallback(list), CELL);

    assertTrue("elevation lever active when cells carry a real spread", p.elevationActive());

    p.candidatesForStep(0, 0, 1000, 1, 5, 0, 0, -1, null);

    assertTrue("higher ground earns a higher elevation reward",
      high.elevationReward > low.elevationReward);
    assertEquals("top-percentile ground saturates at 1", 1.0, high.elevationReward, 1e-9);
    assertEquals("valley floor earns no elevation reward", 0.0, low.elevationReward, 1e-9);
  }

  @Test
  public void noElevationRewardWhenGridHasNoSpread() {
    // All cells at the same elevation → no flat-terrain lever to pull against.
    Map<Long, double[]> grid = new HashMap<>();
    for (int ox = 0; ox < 7; ox++) {
      for (int oy = 0; oy < 7; oy++) {
        boolean dense = ox >= 2 && ox <= 4 && oy >= 2 && oy <= 4;
        double nodes = dense ? 40 : 3;
        grid.put(key(ox, oy), new double[]{nodes, nodes * 0.5, 250.0 * nodes, nodes});
      }
    }
    CandidatePoint cp = at(0, 0);
    List<CandidatePoint> list = new ArrayList<>();
    list.add(cp);
    CapsuleCandidateProvider p = new CapsuleCandidateProvider(grid, new StubFallback(list), CELL);

    assertFalse("flat grid → elevation lever off", p.elevationActive());
    p.candidatesForStep(0, 0, 1000, 1, 5, 0, 0, -1, null);
    assertEquals(0.0, cp.elevationReward, 1e-9);
  }

  @Test
  public void ruralStartClassifiesNothingAsCapsule() {
    // Sparse everywhere (no city): the absolute floor must veto the percentile,
    // so nothing is masked and there are no portals.
    Map<Long, double[]> grid = new HashMap<>();
    for (int ox = 0; ox < 7; ox++) {
      for (int oy = 0; oy < 7; oy++) {
        grid.put(key(ox, oy), new double[]{4, 2.0, 250.0 * 4, 4});
      }
    }
    CapsuleCandidateProvider p =
      new CapsuleCandidateProvider(grid, new StubFallback(new ArrayList<>()), CELL);
    assertEquals(0, p.denseCellCount());
    assertEquals(0, p.boundaryCellCount());
  }
}
