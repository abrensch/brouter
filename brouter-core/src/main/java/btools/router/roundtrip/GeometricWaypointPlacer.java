package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.util.CheapRuler;

/**
 * Geometric round-trip via placement from probe envelopes and isochrone
 * frontiers: isochrone/probe direction merge, frontier-entry decoding,
 * iso-asymmetry bearing selection, and the envelope/isochrone placement paths.
 * The circle fallback and the isochrone expansion itself remain engine
 * primitives; this class logs only through {@link EngineIO}.
 */
public final class GeometricWaypointPlacer {

  private final EngineIO io;

  public GeometricWaypointPlacer(EngineIO io) {
    this.io = io;
  }

  /** Reference road-geometry indirectness the geometric loop-radius calibration is tuned to. */
  private static final double REFERENCE_GEOM_INDIRECTNESS = 1.25;

  /**
   * Assumed road/air indirectness for a direction with NO observed isochrone
   * geometry (probe-only entries, and the no-iso-data fallback). Deliberately
   * equal to {@link #REFERENCE_GEOM_INDIRECTNESS}: an unknown direction is
   * assumed to behave like the calibration baseline, so the count of probe-only
   * directions doesn't perturb the global indirectness compensation. Validate
   * against the loop-quality corpus when changing.
   */
  private static final double DEFAULT_PROBE_INDIRECTNESS = REFERENCE_GEOM_INDIRECTNESS;

  /** Clamp range on the indirectness compensation factor (±20% of geometric base). */
  private static final double IND_COMPENSATION_MIN = 0.80;

  private static final double IND_COMPENSATION_MAX = 1.20;

  /** ISOCHRONE direction-bulge strength: the per-direction placement radius is
   *  scaled by 1 + alpha*cos(theta - heading), a mean-preserving cardioid toward
   *  the requested heading (0 = legacy even ring). Non-final only so tests can
   *  drive both ends — not a runtime knob. */
  static double isochroneDirBulgeAlpha = 0.35;

  /**
   * Merge isochrone frontier data with probe directions for gap-filling.
   * Isochrone entries are 6-element {@code [direction, airDist, cost, hits, ilon,
   * ilat]} (last two = road-native frontier coord); probe-only entries are
   * 4-element {@code [direction, searchRadius, searchRadius*1.3, 0]} (estimated
   * cost, no road-native data, hits=0). Existing isochrone entries dominate
   * overlapping probe directions.
   *
   * @param frontier        isochrone entries (may be null)
   * @param probeDirections probe viable directions in degrees (may be null)
   * @param searchRadius    fallback distance for probe-only directions
   * @return merged entries (6-element isochrone-sourced, 4-element probe-only);
   *         {@code null} if both inputs empty
   */
  public static double[][] mergeIsochroneWithProbe(double[][] frontier, double[] probeDirections, double searchRadius) {
    Map<Integer, double[]> merged = new HashMap<>();

    if (frontier != null) {
      for (double[] entry : frontier) {
        int bucket = (int) Math.round(entry[0]);
        merged.put(bucket, entry);
      }
    }

    // Add probe directions where isochrone has no data
    if (probeDirections != null) {
      for (double dir : probeDirections) {
        int bucket = (int) Math.round(dir);
        boolean covered = false;
        for (int key : merged.keySet()) {
          if (PlacementGeometry.angleDiff(key, bucket) <= 5) {
            covered = true;
            break;
          }
        }
        if (!covered) {
          // Probe-only: use searchRadius as airDist, estimate cost with default indirectness.
          // hits=0 marks this as "probed but not observed by isochrone" — lower confidence.
          merged.put(bucket, new double[]{dir, searchRadius, searchRadius * DEFAULT_PROBE_INDIRECTNESS, 0});
        }
      }
    }

    if (merged.isEmpty()) return null;

    List<double[]> result = new ArrayList<>(merged.values());
    Collections.sort(result, (a, b) -> Double.compare(a[0], b[0]));
    return result.toArray(new double[0][]);
  }

  /** Frontier-entry layout indices for the 6-element isochrone form. */
  private static final int FRONTIER_IDX_ILON = 4;

  private static final int FRONTIER_IDX_ILAT = 5;

  private static final int FRONTIER_LENGTH_ROAD_NATIVE = 6;

  /**
   * Extract the road-native coordinate ({@code [ilon, ilat]}) from a frontier
   * entry, or {@code null} if it carries none. The frontier coord is the
   * cost-budget-envelope node — a fallback for callers that don't pass the full
   * candidate pool; production placement prefers {@link #nearestCandidateByAirDist}.
   *
   * <p>Isochrone entries are 6-element; probe-only entries from
   * {@link #mergeIsochroneWithProbe} are 4-element with no road-native data.
   */
  static int[] frontierRoadNativeCoord(double[] entry) {
    if (entry == null || entry.length < FRONTIER_LENGTH_ROAD_NATIVE) return null;
    return new int[]{(int) entry[FRONTIER_IDX_ILON], (int) entry[FRONTIER_IDX_ILAT]};
  }

  public void placeWaypointsFromIsochrone(List<OsmNodeNamed> waypoints, double[][] frontierData,
                                   List<IsoCandidate> isoCandidates,
                                   double searchRadius, double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    int needed = targetPoints - 1;
    if (needed < 2) needed = 2;

    // Group the road-native candidate pool by bucket so per-direction placement
    // can pick the candidate (frontier-max or 25/50/75 contour) whose
    // air-distance is closest to the indirectness-compensated target — preserves
    // the loop-size scaling while keeping the waypoint on a real road.
    Map<Integer, List<IsoCandidate>> candidatesByBucket;
    if (isoCandidates != null && !isoCandidates.isEmpty()) {
      candidatesByBucket = new HashMap<>();
      for (IsoCandidate c : isoCandidates) {
        List<IsoCandidate> bucket = candidatesByBucket.get(c.bucket);
        if (bucket == null) {
          bucket = new ArrayList<>();
          candidatesByBucket.put(c.bucket, bucket);
        }
        bucket.add(c);
      }
    } else {
      candidatesByBucket = Collections.emptyMap();
    }

    // Pre-filter the frontier data:
    //   1. Drop sea-blocked / dead-end directions whose airDist is far below the
    //      target placement radius. Selecting these would put a waypoint in the
    //      ocean (coastal_nice 50km failure mode) or at a one-shot dead-end
    //      (rural_lozere garbage signal).
    //   2. Drop low-population buckets (hits < 3) — likely one-shot dead-ends.
    //   3. Keep at least 4 directions even if filtering would leave fewer, so
    //      the loop can still be constructed.
    double minFrontierReach = searchRadius * 0.4;
    int minHits = 3;
    List<double[]> usable = new ArrayList<>();
    for (double[] entry : frontierData) {
      double airDist = entry[1];
      int hits = entry.length > 3 ? (int) entry[3] : 1;
      if (airDist >= minFrontierReach && hits >= minHits) {
        usable.add(entry);
      }
    }
    if (usable.size() < 4) {
      // Signal too thin — relax filters and take whatever we have.
      usable.clear();
      for (double[] entry : frontierData) {
        if (entry[1] >= searchRadius * 0.2) usable.add(entry);
      }
    }

    int n = usable.size();
    if (needed > n) needed = n;
    if (needed < 2) needed = 2;

    double[] directions = new double[n];
    Map<Double, double[]> dirToData = new HashMap<>(); // dir -> entry array
    for (int i = 0; i < n; i++) {
      double[] entry = usable.get(i);
      directions[i] = entry[0];
      dirToData.put(entry[0], entry);
    }

    double[] selected;
    if (needed >= n) {
      selected = directions;
    } else {
      selected = PlacementGeometry.selectSpreadDirections(directions, needed, startDirection);
    }
    selected = PlacementGeometry.sortDirectionsForLoop(selected, startDirection);

    // Base radius from polygon geometry (legacy v1.7.8-compatible), then
    // compensated by observed road-geometry indirectness. The cost/airDist
    // ratio from the isochrone equals (roadDist/airDist) × profileCostFactor.
    // To isolate the road geometry part we estimate profileCostFactor from the
    // minimum observed indirectness across all usable directions (the easiest
    // road's indirectness ≈ pure profile costfactor on flat direct road).
    double geomBase = searchRadius * PlacementGeometry.computeRadiusScale(selected, targetPoints);

    // Per-direction observed cost/airDist ratio. Median (not mean) so a single
    // outlier direction doesn't dominate redistribution.
    double[] selectedInd = new double[selected.length];
    for (int i = 0; i < selected.length; i++) {
      double[] data = dirToData.get(selected[i]);
      double airDist = data[1];
      double cost = data[2];
      selectedInd[i] = (airDist > 50) ? Math.max(1.0, cost / airDist) : 1.5;
    }
    double[] sortedInd = selectedInd.clone();
    Arrays.sort(sortedInd);
    double medianInd = Math.max(1.0, sortedInd[selectedInd.length / 2]);

    // Estimate profile-only cost factor from the easiest direction across ALL
    // observed directions (not just selected), so directional pre-filter doesn't
    // bias it. Min reasonable value: 1.0 (already clamped during data read).
    double minObservedInd = Double.MAX_VALUE;
    for (double[] entry : usable) {
      double aD = entry[1], c = entry[2];
      if (aD > 50) {
        double ind = Math.max(1.0, c / aD);
        if (ind < minObservedInd) minObservedInd = ind;
      }
    }
    if (minObservedInd == Double.MAX_VALUE) minObservedInd = DEFAULT_PROBE_INDIRECTNESS;
    double profileCostFactor = Math.max(1.0, minObservedInd);
    // Pure road-geometry indirectness (road meters per air meter), profile-free.
    double geomInd = medianInd / profileCostFactor;
    // Compensate the base radius: in indirect terrain (high geomInd) shrink so
    // the actual routed loop matches target distance. Conservative ±20%.
    double indCompensation = REFERENCE_GEOM_INDIRECTNESS / Math.max(1.0, geomInd);
    indCompensation = Math.max(IND_COMPENSATION_MIN, Math.min(IND_COMPENSATION_MAX, indCompensation));
    double baseRadius = geomBase * indCompensation;

    // Per-direction redistribution factors. Indirect dirs (mountains) → factor <
    // 1 → closer; direct dirs (valley floor) → factor > 1 → farther. Normalize
    // so the average factor = 1.0 (mean-preserving).
    double[] rawFactors = new double[selected.length];
    double factorSum = 0;
    for (int i = 0; i < selected.length; i++) {
      rawFactors[i] = medianInd / selectedInd[i];
      factorSum += rawFactors[i];
    }
    double normalization = selected.length / factorSum;

    // Directional bulge: bias the placement radius toward startDirection so the
    // loop heads that way (a cardioid) instead of encircling evenly — this is
    // what lets ISOCHRONE honour the requested direction, which the bare
    // even-spread frontier sampling cannot. Mean-preserving: the per-direction
    // factors are renormalised to average 1.0, so the loop's overall size — and
    // therefore the distance gate — is unchanged; only its shape shifts toward
    // the heading. isochroneDirBulgeAlpha=0 reproduces the legacy even ring.
    double dirBulgeAlpha = isochroneDirBulgeAlpha;
    double[] dirBulge = new double[selected.length];
    double dirBulgeSum = 0;
    for (int i = 0; i < selected.length; i++) {
      dirBulge[i] = 1.0 + dirBulgeAlpha * Math.cos(Math.toRadians(selected[i] - startDirection));
      dirBulgeSum += dirBulge[i];
    }
    double dirBulgeNorm = dirBulgeSum > 0 ? selected.length / dirBulgeSum : 1.0;

    double maxDist = searchRadius * 1.5;
    double minDist = searchRadius * 0.15;
    int roadNativeCount = 0;
    int syntheticCount = 0;
    double bucketSize = 360.0 / 36; // matches runIsochroneExpansion
    for (int i = 0; i < selected.length; i++) {
      double factor = Math.max(0.5, Math.min(2.0,
        rawFactors[i] * normalization * dirBulge[i] * dirBulgeNorm));
      double airDist = baseRadius * factor;
      airDist = Math.max(minDist, Math.min(maxDist, airDist));

      // Pick the road-native candidate in this bucket whose air-distance is
      // closest to airDist — but only if it's within ±2× of the target,
      // otherwise the candidate (typically the cost-budget-envelope frontier-max)
      // would defeat the per-direction indirectness compensation. When out of
      // tolerance, synthesize at the exact target and let matchWaypointsToNodes
      // snap to the nearest road. Frontier-entry coord (entry[4..5]) is a
      // legacy fallback for callers without a candidate pool.
      int bucketIdx = ((int) (selected[i] / bucketSize)) % 36;
      if (bucketIdx < 0) bucketIdx += 36;
      IsoCandidate bestCand = nearestCandidateByAirDist(candidatesByBucket.get(bucketIdx), airDist);
      boolean candAcceptable = bestCand != null
        && bestCand.airDistanceFromStart >= airDist * 0.5
        && bestCand.airDistanceFromStart <= airDist * 2.0;
      int[] pos;
      if (candAcceptable) {
        pos = new int[]{bestCand.ilon, bestCand.ilat};
        roadNativeCount++;
      } else if (bestCand == null) {
        int[] frontierCoord = frontierRoadNativeCoord(dirToData.get(selected[i]));
        if (frontierCoord != null) {
          pos = frontierCoord;
          roadNativeCount++;
        } else {
          pos = CheapRuler.destination(start.ilon, start.ilat, airDist, selected[i]);
          syntheticCount++;
        }
      } else {
        pos = CheapRuler.destination(start.ilon, start.ilat, airDist, selected[i]);
        syntheticCount++;
      }
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + (i + 1);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    io.logInfo("placeWaypointsFromIsochrone: " + selected.length + " waypoints"
      + " (" + roadNativeCount + " road-native, " + syntheticCount + " synthetic)"
      + ", baseRadius=" + (int) baseRadius + "m"
      + ", medianInd=" + String.format("%.2f", medianInd)
      + ", searchRadius=" + (int) searchRadius + "m");
  }

  /**
   * Place waypoints from the reachability envelope at a scaled search radius.
   * Selects N max-spread directions from the viable set, then scales the radius
   * to match v1.7.8's expected loop distance.
   *
   * <p><b>Known parity gap (P5):</b> unlike {@link #placeWaypointsFromIsochrone},
   * this fallback applies only the geometric {@code computeRadiusScale} correction,
   * not terrain-indirectness compensation (it has no per-direction isochrone cost
   * data to derive it from). It is reached when the merged frontier has &lt;3
   * usable directions — indirect terrain (mountains/coast), where unadjusted radii
   * tend to overshoot. A conservative {@link #DEFAULT_PROBE_INDIRECTNESS}-based
   * shrink here is a candidate improvement but needs loop-quality-corpus validation.
   */
  public void placeWaypointsFromEnvelope(List<OsmNodeNamed> waypoints, double[] viableDirections,
                                  double searchRadius, double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    int n = viableDirections.length;
    int needed = targetPoints - 1;
    if (needed > n) needed = n;
    if (needed < 2) needed = 2;

    double[] selected;
    if (needed >= n) {
      selected = viableDirections;
    } else {
      selected = PlacementGeometry.selectSpreadDirections(viableDirections, needed, startDirection);
    }

    selected = PlacementGeometry.sortDirectionsForLoop(selected, startDirection);

    // Scale radius so the loop perimeter matches v1.7.8's expected distance.
    // v1.7.8 uses buildPointsFromCircle which creates a narrow arc (108-152°).
    // The probe's wider direction spread produces longer loops at the same radius.
    double adjustedRadius = searchRadius * PlacementGeometry.computeRadiusScale(selected, targetPoints);

    for (int i = 0; i < selected.length; i++) {
      int[] pos = CheapRuler.destination(start.ilon, start.ilat, adjustedRadius, selected[i]);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + (i + 1);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    io.logInfo("placeWaypointsFromEnvelope: " + selected.length + " waypoints, radius "
      + (int) searchRadius + "m -> " + (int) adjustedRadius + "m (scale "
      + String.format("%.2f", adjustedRadius / searchRadius) + ")");
  }

  /**
   * Phase 2.0 of the closure-aware planning spec — isochrone-asymmetry initial
   * bearing. Examines the 36-bucket frontier table and selects the most-reaching
   * sector (lowest {@code cost / airDist}) subject to quality thresholds. Returns
   * {@link IsoAsymmetryBias#NONE} when no bucket clears them (caller falls back to
   * legacy direction selection). Package-private + static for unit testing.
   */
  public static IsoAsymmetryBias computeIsoAsymmetryBearing(double[][] frontier, double searchRadius) {
    if (frontier == null || frontier.length == 0) return IsoAsymmetryBias.NONE;
    final double minAirDist = 0.6 * searchRadius;
    final int minHits = 3;
    int bestIdx = -1;
    double bestIndirectness = Double.POSITIVE_INFINITY;
    for (int i = 0; i < frontier.length; i++) {
      double[] b = frontier[i];
      if (b == null || b.length < 4) continue;
      double airDist = b[1];
      double cost = b[2];
      int hits = (int) b[3];
      if (airDist < minAirDist || hits < minHits || airDist <= 0) continue;
      double indirectness = cost / airDist;
      if (indirectness < bestIndirectness) {
        bestIndirectness = indirectness;
        bestIdx = i;
      }
      // Tie-break: lowest bucket index wins (already enforced by strict <).
    }
    if (bestIdx < 0) return IsoAsymmetryBias.NONE;
    double[] best = frontier[bestIdx];
    return new IsoAsymmetryBias(true, best[0], bestIndirectness,
        (int) best[3], (int) best[1]);
  }

  /**
   * Pick the candidate in {@code bucketCandidates} whose air-distance from start
   * is closest to {@code targetAirDist}, or {@code null} if the bucket is empty.
   * Used by {@link #placeWaypointsFromIsochrone} to keep the
   * indirectness-compensated radius while landing on a road-native point (each
   * bucket carries one frontier-max + up to three contour candidates at distinct
   * cost depths, so a close airDist match is usually available).
   */
  static IsoCandidate nearestCandidateByAirDist(List<IsoCandidate> bucketCandidates, double targetAirDist) {
    if (bucketCandidates == null || bucketCandidates.isEmpty()) return null;
    IsoCandidate best = null;
    double bestDiff = Double.MAX_VALUE;
    for (IsoCandidate c : bucketCandidates) {
      double diff = Math.abs(c.airDistanceFromStart - targetAirDist);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = c;
      }
    }
    return best;
  }

  /**
   * Phase 2.1 of the closure-aware planning spec — frontier-axis detection for
   * axis-aware retry when the user's explicit direction conflicts with the
   * terrain's natural orientation. Computes the principal axis of the
   * reachable-frontier displacement vectors (good-quality buckets only, same
   * thresholds as Phase 2.0). Returns an axis bearing in [0, 180) and the
   * eigenvalue ratio (primary / secondary); the axis is "strong" when the ratio
   * exceeds {@link #PHASE_2_1_STRONG_AXIS_RATIO} (markedly elongated region: Inn
   * Valley, coast roads, ridge tops).
   */
  public static FrontierAxis computeFrontierAxis(double[][] frontier, double searchRadius) {
    if (frontier == null || frontier.length < 6) return FrontierAxis.NONE;
    final double minAirDist = 0.6 * searchRadius;
    final int minHits = 3;
    double sumX2 = 0, sumY2 = 0, sumXY = 0;
    int n = 0;
    for (double[] b : frontier) {
      if (b == null || b.length < 4) continue;
      double airDist = b[1];
      int hits = (int) b[3];
      if (airDist < minAirDist || hits < minHits) continue;
      // Compass bearing → (east, north) Cartesian.
      double rad = Math.toRadians(b[0]);
      double x = airDist * Math.sin(rad); // east
      double y = airDist * Math.cos(rad); // north
      sumX2 += x * x;
      sumY2 += y * y;
      sumXY += x * y;
      n++;
    }
    if (n < 4) return FrontierAxis.NONE;
    double a = sumX2 / n;
    double bb = sumY2 / n;
    double c = sumXY / n;
    double trace = a + bb;
    double det = a * bb - c * c;
    double disc = Math.sqrt(Math.max(0, trace * trace - 4 * det));
    double lambda1 = (trace + disc) / 2;
    double lambda2 = (trace - disc) / 2;
    if (lambda2 <= 0 || lambda1 <= 0) return FrontierAxis.NONE;
    double strength = lambda1 / lambda2;
    // Closed-form principal-axis angle for a 2x2 symmetric covariance:
    // principalAngle = 0.5 * atan2(2c, a-b), in math convention (CCW from
    // east). Robust to c ≈ 0 (avoids the fragile eigenvector-from-eigenvalue
    // path which divides by tiny numbers). Convert to compass bearing
    // (CW from north): bearing = 90° − math_angle.
    double principalAngleDeg = 0.5 * Math.toDegrees(Math.atan2(2 * c, a - bb));
    double bearing = (90 - principalAngleDeg + 360) % 360;
    if (bearing >= 180) bearing -= 180; // canonical [0, 180), axis is bidirectional
    return new FrontierAxis(strength >= PHASE_2_1_STRONG_AXIS_RATIO, bearing, strength);
  }

  /** Phase 2.1: eigenvalue ratio above which we treat the reachable region
   *  as having a strong terrain axis. 3.0 corresponds to the reachable
   *  region being ~1.7x as elongated along the principal axis as
   *  perpendicular (sqrt(3) ≈ 1.73). Tunable; lower values fire more often. */
  static final double PHASE_2_1_STRONG_AXIS_RATIO = 3.0;

  /** Phase 2.1: half-angle (degrees) of the "near-perpendicular" cone.
   *  User direction within 30° of perpendicular to the axis triggers retry. */
  static final double PHASE_2_1_PERPENDICULAR_TOL = 30.0;

  /** Phase 2.1: whether a user-supplied bearing is within
   *  {@link #PHASE_2_1_PERPENDICULAR_TOL} of perpendicular to the given
   *  axis. Both arguments are in compass degrees; axis canonical [0, 180). */
  public static boolean isPerpendicularToAxis(double userBearing, double axisBearing) {
    double userMod = ((userBearing % 180) + 180) % 180;
    double axisMod = ((axisBearing % 180) + 180) % 180;
    double diff = Math.abs(userMod - axisMod);
    if (diff > 90) diff = 180 - diff;
    return diff >= (90 - PHASE_2_1_PERPENDICULAR_TOL);
  }

  /** Phase 2.1: pick the axis-aligned bearing (axis or axis+180) whose
   *  half-plane is closer to the user's original direction. Used to retry
   *  with a direction that respects both terrain (axis) and rough user
   *  intent (the original half-plane). Tie-break: prefer the lower bearing. */
  public static double chooseAxisBearing(double axisBearing, double userBearing) {
    double opt1 = ((axisBearing % 180) + 180) % 180;       // canonical axis
    double opt2 = (opt1 + 180) % 360;                       // opposing direction
    double user = ((userBearing % 360) + 360) % 360;
    double d1 = angularDiff(opt1, user);
    double d2 = angularDiff(opt2, user);
    if (d1 < d2) return opt1;
    if (d2 < d1) return opt2;
    return Math.min(opt1, opt2);
  }

  static double angularDiff(double a, double b) {
    double d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
  }
}
