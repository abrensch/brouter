package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import btools.router.OsmPathElement;
import btools.router.OsmTrack;

/**
 * One immutable crossing analysis of a loop track, computed once and shared by
 * the quality gate, {@link RouteChoiceScore}, {@link LoopQualityMetrics}, and
 * the orchestrator's advisory decoration (round-2 review finding: the three
 * used to run the same segment-pair + node-revisit + corridor scans
 * independently, several full-track passes per candidate).
 *
 * <p>Semantics are the historical {@code LoopQualityMetrics.detectCrossings} /
 * {@code crossingPoints} pair, built on the gate's own primitives
 * ({@link RoundTripQualityGate#countSegmentPairCrossings},
 * {@link RoundTripQualityGate#isTransverseRevisit},
 * {@link RoundTripQualityGate#countCorridorCrossings}), so gate and report can
 * never drift:
 * <ul>
 *   <li>{@link #selfIntersections}: segment-pair crossings + transverse node
 *       revisits + shared-corridor crossings, ceiling-bounded at
 *       {@link #CROSSING_SCAN_CEILING}. For any track at or below the gate's
 *       chaos thresholds this equals the standalone
 *       {@link RoundTripQualityGate#countSelfIntersections} exactly; above ~20
 *       crossings the standalone scan truncates earlier, but every such track
 *       is already a STRUCTURAL chaos reject under both counts (tier bounds are
 *       5 and 10), so only diagnostic text can differ.</li>
 *   <li>{@link #smallLoopCrossings}: of the segment-pair + node crossings,
 *       those enclosing a short arc (small detour lassos). Corridor crossings
 *       are structural shared-run knots and deliberately never counted here.</li>
 *   <li>{@link #crossingPoints}: {@code {lonDeg, latDeg, enclosedArcMeters}}
 *       per segment-pair/node crossing, for the lasso surcharge and map
 *       markers. Corridor crossings carry no location (historical contract of
 *       {@code crossingPoints}).</li>
 * </ul>
 *
 * <p>The planner's per-candidate tentative counting deliberately does NOT use
 * this class — it stays on {@link RoundTripQualityGate#countSelfIntersections}
 * with its tighter ceiling, preserving the routed-score penalty values
 * bit-exactly on the hot path.
 */
final class LoopAnalysis {

  /** Shared scan ceiling — the historical metrics/report bound; counts only
   *  need to be exact below the gate's chaos thresholds, far under this. */
  static final int CROSSING_SCAN_CEILING = 64;

  static final LoopAnalysis EMPTY =
    new LoopAnalysis(0, 0, Collections.emptyList());

  final int selfIntersections;
  final int smallLoopCrossings;
  final List<double[]> crossingPoints;

  private LoopAnalysis(int selfIntersections, int smallLoopCrossings,
                       List<double[]> crossingPoints) {
    this.selfIntersections = selfIntersections;
    this.smallLoopCrossings = smallLoopCrossings;
    this.crossingPoints = crossingPoints;
  }

  static LoopAnalysis of(OsmTrack track) {
    return track == null ? EMPTY : of(track.nodes);
  }

  static LoopAnalysis of(List<OsmPathElement> nodes) {
    int n = nodes == null ? 0 : nodes.size();
    if (n < 4) return EMPTY;
    double[] cum = LoopGeometry.cumulativeDistances(nodes);
    double perim = cum[n - 1];
    int[] smallLoop = new int[1];
    List<double[]> pts = new ArrayList<>();
    int total = RoundTripQualityGate.countSegmentPairCrossings(nodes, cum, perim,
      CROSSING_SCAN_CEILING, (i, j) -> {
        double arc = cum[j] - cum[i];
        double enclosed = Math.min(arc, perim - arc);
        if (enclosed <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS) smallLoop[0]++;
        pts.add(intersectionLonLat(nodes.get(i), nodes.get(i + 1),
          nodes.get(j), nodes.get(j + 1), enclosed));
      });
    if (total > CROSSING_SCAN_CEILING) {
      return new LoopAnalysis(total, smallLoop[0], Collections.unmodifiableList(pts));
    }
    // Crossings AT a shared junction node — invisible to the CCW scan (its
    // shared-endpoint exclusion), yet the dominant real-world case on a road
    // network. Same transversality test and exemptions as the gate.
    total += RoundTripQualityGate.transverseNodeRevisits(nodes, cum,
      CROSSING_SCAN_CEILING - total, (k1, k) -> {
        double arc = cum[k] - cum[k1];
        double enclosed = Math.min(arc, perim - arc);
        if (enclosed <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS) smallLoop[0]++;
        pts.add(new double[]{nodes.get(k).getILon() / 1e6 - 180.0,
          nodes.get(k).getILat() / 1e6 - 90.0, enclosed});
      });
    if (total <= CROSSING_SCAN_CEILING) {
      // Shared-corridor crossings: counted toward the total only — structural
      // shared-run knots, not small detour lassos, and no location marker
      // (run on FULL-resolution nodes; see countCorridorCrossings).
      total += RoundTripQualityGate.countCorridorCrossings(nodes);
    }
    return new LoopAnalysis(total, smallLoop[0], Collections.unmodifiableList(pts));
  }

  /** Parametric intersection of two (known-crossing) segments, in degrees. */
  private static double[] intersectionLonLat(OsmPathElement p1, OsmPathElement p2,
                                             OsmPathElement p3, OsmPathElement p4, double enclosed) {
    double x1 = p1.getILon(), y1 = p1.getILat();
    double dx1 = p2.getILon() - x1, dy1 = p2.getILat() - y1;
    double x3 = p3.getILon(), y3 = p3.getILat();
    double dx2 = p4.getILon() - x3, dy2 = p4.getILat() - y3;
    double denom = dx1 * dy2 - dy1 * dx2;
    double t = denom == 0 ? 0.5 : ((x3 - x1) * dy2 - (y3 - y1) * dx2) / denom;
    double lon = (x1 + t * dx1) / 1e6 - 180.0;
    double lat = (y1 + t * dy1) / 1e6 - 90.0;
    return new double[]{lon, lat, enclosed};
  }
}
