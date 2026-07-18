package btools.router.roundtrip;

import java.util.List;

import btools.router.OsmPathElement;

/**
 * Shared low-level loop-geometry primitives. Deduplication seam (2026-07-18
 * index sweep): the cumulative-arc-distance prefix array was built inline at
 * eight production sites and the undirected edge key existed as three
 * byte-identical copies — one drifting copy would silently desynchronize the
 * gate, the scorer, and the reuse classifier, which all reason over the same
 * geometry.
 *
 * <p>Deliberately NOT consolidated here: the test-side copies
 * ({@code SelfIntersectionGridEquivalenceTest}'s prefix helper,
 * {@code RoundTripFixture}'s edge hash) stay independent — a test that
 * recomputes its oracle through the production helper would prove nothing.
 */
public final class LoopGeometry {

  private LoopGeometry() {
  }

  /**
   * Cumulative arc distances along the polyline: {@code cum[0] == 0},
   * {@code cum[k]} = meters from node 0 to node k; {@code cum[n-1]} is the
   * perimeter of a closed loop.
   */
  public static double[] cumulativeDistances(List<OsmPathElement> nodes) {
    int n = nodes.size();
    double[] cum = new double[n];
    for (int k = 1; k < n; k++) {
      cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    return cum;
  }

  /**
   * Undirected edge key for the segment between two consecutive track nodes,
   * from the nodes' position ids. Order-independent (min/max) so a retraced
   * edge maps to the same key in both directions.
   */
  public static long edgeKey(OsmPathElement a, OsmPathElement b) {
    long idA = a.getIdFromPos();
    long idB = b.getIdFromPos();
    long lo = Math.min(idA, idB);
    long hi = Math.max(idA, idB);
    return lo ^ (hi * 0x9E3779B97F4A7C15L);
  }
}
