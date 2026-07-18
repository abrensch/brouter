package btools.router.roundtrip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import btools.router.OsmPathElement;

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
      int brute = RoundTripQualityGate.bruteForceSegmentPairCrossings(nodes, cum, perim, ceiling, null);
      // Call the grid variant via the dispatching entry point is not possible
      // per-size, so exercise both directly through the package-private API:
      int dispatched = RoundTripQualityGate.countSegmentPairCrossings(nodes, cum, perim, ceiling);
      assertEquals("dispatched count must equal brute force (n=" + nodes.size()
        + ", ceiling=" + ceiling + ")", brute, dispatched);
    }
    // Visitor parity — the scorer path (LoopQualityMetrics.detectCrossings)
    // classifies each crossing by enclosed arc through this callback, so the
    // grid must fire the same SET of (i, j) pairs as the brute oracle (order
    // may differ; compare as sets, below any ceiling).
    Set<Long> brutePairs = collectPairs(nodes, cum, perim, true);
    Set<Long> dispatchedPairs = collectPairs(nodes, cum, perim, false);
    assertEquals("visitor pair set must match brute force (n=" + nodes.size() + ")",
      brutePairs, dispatchedPairs);
  }

  private static Set<Long> collectPairs(List<OsmPathElement> nodes, double[] cum,
                                        double perim, boolean brute) {
    Set<Long> pairs = new HashSet<>();
    RoundTripQualityGate.SegmentCrossingVisitor v =
      (i, j) -> pairs.add((((long) i) << 32) | j);
    if (brute) {
      RoundTripQualityGate.bruteForceSegmentPairCrossings(nodes, cum, perim, Integer.MAX_VALUE, v);
    } else {
      RoundTripQualityGate.countSegmentPairCrossings(nodes, cum, perim, Integer.MAX_VALUE, v);
    }
    return pairs;
  }

  /**
   * End-to-end scorer-path pin: detectCrossings' {total, smallLoop} on a
   * grid-sized track must equal the classification computed from the
   * brute-force oracle's pairs. The fixture is a big rectangle-ish loop
   * (>512 segments, so the grid dispatches) carrying one small alpha-lasso
   * (enclosed arc well under {@code SMALL_LOOP_MAX_ARC_METERS}) and one
   * structural figure-eight crossing (enclosed arc well over it); no node id
   * repeats, so the junction-revisit and corridor phases contribute zero and
   * the comparison is exact.
   */
  @Test
  public void detectCrossingsMatchesBruteClassification() {
    List<OsmPathElement> nodes = new ArrayList<>();
    int x0 = 180000000;
    int y0 = 50000000;
    // East along y0 (300 segments), with a small alpha-lasso spliced in at 60km-units.
    for (int i = 0; i <= 150; i++) nodes.add(node(x0 + i * 400, y0));
    int ax = x0 + 150 * 400;
    nodes.add(node(ax + 3000, y0));          // continue east
    nodes.add(node(ax + 3000, y0 + 3000));   // up
    nodes.add(node(ax + 1500, y0 + 3000));   // west
    nodes.add(node(ax + 1500, y0 - 1500));   // down — crosses the inbound segment (small lasso)
    nodes.add(node(ax + 4500, y0 - 1500));   // east
    nodes.add(node(ax + 4500, y0));          // back to the main line
    for (int i = 0; i <= 150; i++) nodes.add(node(ax + 4500 + i * 400, y0));
    int xe = ax + 4500 + 150 * 400;
    // North (100 segments), then west back above the outbound line (300 segments).
    for (int i = 1; i <= 100; i++) nodes.add(node(xe, y0 + i * 400));
    int yn = y0 + 100 * 400;
    for (int i = 1; i <= 300; i++) nodes.add(node(xe - i * 400, yn));
    int xw = xe - 300 * 400;
    // South through the outbound line (figure-eight crossing, large enclosed arc),
    // then a tail east so the crossing sits outside both 500m exemption windows.
    // Off-grid offsets (+100/+200) keep the south run from coinciding with any
    // outbound NODE — a shared node would divert the crossing to the
    // node-revisit phase, and a node exactly ON the outbound segment would read
    // as a collinear touch (ccw=0), not a crossing.
    int xc = x0 + 30100;
    for (int i = 1; i <= 150; i++) nodes.add(node(xc, yn + 200 - i * 400));
    int ys = yn + 200 - 150 * 400;
    for (int i = 1; i <= 60; i++) nodes.add(node(xc + i * 400, ys));

    double[] cum = cumDistances(nodes);
    double perim = cum[nodes.size() - 1];
    int[] expected = new int[2];
    int segTotal = RoundTripQualityGate.bruteForceSegmentPairCrossings(
      nodes, cum, perim, Integer.MAX_VALUE, (i, j) -> {
        double arc = cum[j] - cum[i];
        if (Math.min(arc, perim - arc) <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS) {
          expected[1]++;
        }
      });
    assertTrue("fixture must exceed the grid-dispatch threshold", nodes.size() - 1 >= 512);
    assertTrue("fixture must cross at least twice", segTotal >= 2);
    assertTrue("fixture must stay under the metrics ceiling for exact comparison", segTotal <= 64);
    assertTrue("fixture must contain a small-lasso crossing", expected[1] >= 1);
    assertTrue("fixture must contain a large-arc crossing", expected[1] < segTotal);

    int[] actual = LoopQualityMetrics.detectCrossings(nodes);
    assertEquals("detectCrossings total must match the brute oracle", segTotal, actual[0]);
    assertEquals("smallLoop classification must match the brute oracle", expected[1], actual[1]);
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

  /**
   * Ceiling-truncated scans: pair SETS may differ between grid and brute (the
   * enumeration order differs, so each stops on a different 65-crossing
   * subset), but the COUNTS must agree — both report ceiling+1. This is the
   * documented limit of the visitor contract; classification consumers only
   * read per-pair data below the ceiling.
   */
  @Test
  public void ceilingTruncatedScansAgreeOnCountNotNecessarilyPairs() {
    // The comb fixture crosses far more than the ceiling used here.
    List<OsmPathElement> nodes = new ArrayList<>();
    int baseLon = 180000000;
    int baseLat = 50000000;
    for (int i = 0; i <= 600; i++) nodes.add(node(baseLon + i * 100, baseLat));
    for (int i = 600; i >= 0; i -= 3) {
      nodes.add(node(baseLon + i * 100, baseLat + ((i / 3) % 2 == 0 ? 5000 : -5000)));
    }
    double[] cum = cumDistances(nodes);
    double perim = cum[nodes.size() - 1];
    int ceiling = 10;
    int[] bruteFired = new int[1];
    int[] gridFired = new int[1];
    int brute = RoundTripQualityGate.bruteForceSegmentPairCrossings(
      nodes, cum, perim, ceiling, (i, j) -> bruteFired[0]++);
    int dispatched = RoundTripQualityGate.countSegmentPairCrossings(
      nodes, cum, perim, ceiling, (i, j) -> gridFired[0]++);
    assertEquals("both scans must stop at ceiling+1", ceiling + 1, brute);
    assertEquals("count parity holds under truncation", brute, dispatched);
    assertEquals("visitor fires once per counted crossing (brute)", brute, bruteFired[0]);
    assertEquals("visitor fires once per counted crossing (grid)", dispatched, gridFired[0]);
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
      nodes, cum, cum[nodes.size() - 1], Integer.MAX_VALUE, null);
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
