package btools.router;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Equivalence proof for the spatial-grid segment-pair crossing scan: on any
 * polyline the grid variant must return EXACTLY the brute-force count (the
 * historical all-pairs scan, kept as the oracle). The grid replaces the scan
 * on tracks above the size threshold, where the O(n²) oracle ran up to ~380×
 * per greedy plan — so this parity is what makes that optimization
 * result-preserving.
 */
public class SelfIntersectionGridEquivalenceTest {

  private static OsmPathElement node(int ilon, int ilat) {
    return OsmPathElement.create(ilon, ilat, (short) 0, null);
  }

  private static double[] cumDistances(List<OsmPathElement> nodes) {
    double[] cum = new double[nodes.size()];
    for (int k = 1; k < nodes.size(); k++) {
      cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    return cum;
  }

  private static void assertParity(List<OsmPathElement> nodes) {
    double[] cum = cumDistances(nodes);
    double perim = cum[nodes.size() - 1];
    for (int ceiling : new int[]{1, 5, 20, Integer.MAX_VALUE}) {
      int brute = RoundTripQualityGate.bruteForceSegmentPairCrossings(nodes, cum, perim, ceiling);
      // Call the grid variant via the dispatching entry point is not possible
      // per-size, so exercise both directly through the package-private API:
      int dispatched = RoundTripQualityGate.countSegmentPairCrossings(nodes, cum, perim, ceiling);
      assertEquals("dispatched count must equal brute force (n=" + nodes.size()
        + ", ceiling=" + ceiling + ")", brute, dispatched);
    }
  }

  /** Random jittered walks, both below and above the grid-dispatch threshold. */
  @Test
  public void randomWalksMatchBruteForce() {
    Random rnd = new Random(42);
    int[] sizes = {60, 300, 700, 1500};
    for (int size : sizes) {
      for (int trial = 0; trial < 5; trial++) {
        List<OsmPathElement> nodes = new ArrayList<>(size);
        int ilon = 180000000;
        int ilat = 50000000;
        for (int i = 0; i < size; i++) {
          // ~30m steps with heavy jitter so the walk frequently self-crosses.
          ilon += rnd.nextInt(801) - 400;
          ilat += rnd.nextInt(801) - 400;
          nodes.add(node(ilon, ilat));
        }
        assertParity(nodes);
      }
    }
  }

  /** Deliberate many-crossing comb shape spanning multiple grid cells. */
  @Test
  public void crossingCombMatchesBruteForce() {
    List<OsmPathElement> nodes = new ArrayList<>();
    int baseLon = 180000000;
    int baseLat = 50000000;
    // Long horizontal spine.
    for (int i = 0; i <= 600; i++) {
      nodes.add(node(baseLon + i * 100, baseLat));
    }
    // Zigzag back across the spine repeatedly.
    for (int i = 600; i >= 0; i -= 3) {
      nodes.add(node(baseLon + i * 100, baseLat + ((i / 3) % 2 == 0 ? 5000 : -5000)));
    }
    assertParity(nodes);
    // Sanity: this fixture genuinely crosses a lot.
    double[] cum = cumDistances(nodes);
    int count = RoundTripQualityGate.bruteForceSegmentPairCrossings(
      nodes, cum, cum[nodes.size() - 1], Integer.MAX_VALUE);
    assertTrue("comb fixture must self-cross", count > 10);
  }

  /** A clean large loop (no crossings) must count 0 in both variants. */
  @Test
  public void cleanLargeLoopCountsZero() {
    List<OsmPathElement> nodes = new ArrayList<>();
    int cx = 180000000;
    int cy = 50000000;
    int r = 90000; // ~10km
    int steps = 900;
    for (int i = 0; i <= steps; i++) {
      double a = 2 * Math.PI * i / steps;
      nodes.add(node(cx + (int) (r * Math.cos(a)), cy + (int) (r * Math.sin(a))));
    }
    double[] cum = cumDistances(nodes);
    double perim = cum[nodes.size() - 1];
    assertEquals(0, RoundTripQualityGate.countSegmentPairCrossings(
      nodes, cum, perim, Integer.MAX_VALUE));
    assertParity(nodes);
  }
}
