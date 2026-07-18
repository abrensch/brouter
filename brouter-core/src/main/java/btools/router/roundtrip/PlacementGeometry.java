package btools.router.roundtrip;

import btools.util.CheapAngleMeter;

/**
 * Round-trip placement math: bearing grids, angular spread selection, loop
 * ordering, probe-confidence filtering, and the v1.7.8-parity radius scale.
 * Pure static functions of their inputs — no engine state. Used by both the
 * engine-side placement paths (envelope/isochrone/legacy FAST) and
 * {@link FastWaypointPlanner}.
 */
public final class PlacementGeometry {

  private PlacementGeometry() {
  }

  // Minimum intermediate vias for a non-degenerate loop (matches the legacy
  // validateAndAdjustWaypoints floor). Below this the optimized FAST placement
  // (FastWaypointPlanner) falls back to the circle path rather than emitting
  // a start->start loop.
  public static final int MIN_ROUNDTRIP_VIAS = 3;

  /** Bearings for an encircling loop: {@code count} directions evenly around the compass. */
  public static double[] fullCircleBearings(int count) {
    double[] b = new double[count];
    for (int i = 0; i < count; i++) b[i] = i * 360.0 / count;
    return b;
  }

  /**
   * Drop single-snap directions IF at least {@code max(targetPoints, 8)}
   * multi-snap-strong directions remain. A lone snap usually marks a thin road
   * sliver or sea-edge that snaps weakly, giving a fragile waypoint. When few
   * strong directions exist (constrained terrain) the weak ones are kept to
   * avoid collapsing to a circle fallback — which also makes it a no-op for
   * directional lobes (their bearing count never reaches the threshold).
   */
  public static double[] filterByProbeConfidence(ProbeResult probe, int targetPoints) {
    if (probe == null) return null;
    if (probe.scored.isEmpty()) return probe.viableDirections;
    int strongCount = 0;
    for (ProbeDirection pd : probe.scored) if (pd.successfulProbeCount >= 2) strongCount++;
    int threshold = Math.max(targetPoints, 8);
    if (strongCount < threshold) return probe.viableDirections;
    double[] kept = new double[probe.scored.size()];
    int n = 0;
    for (ProbeDirection pd : probe.scored) {
      if (pd.successfulProbeCount >= 2) kept[n++] = pd.direction;
    }
    return java.util.Arrays.copyOf(kept, n);
  }

  /**
   * Select {@code count} directions maximizing angular spread. Greedy: start
   * closest to {@code startDirection}, then repeatedly pick the direction whose
   * minimum angular distance to the already-selected set is largest.
   */
  public static double[] selectSpreadDirections(double[] viable, int count, double startDirection) {
    int n = viable.length;
    boolean[] used = new boolean[n];
    double[] selected = new double[count];

    // Find starting direction closest to requested
    int startIdx = 0;
    double minDiff = Double.MAX_VALUE;
    for (int i = 0; i < n; i++) {
      double diff = angleDiff(viable[i], startDirection);
      if (diff < minDiff) {
        minDiff = diff;
        startIdx = i;
      }
    }
    used[startIdx] = true;
    selected[0] = viable[startIdx];

    // Greedy: pick the direction that maximizes minimum distance to selected set
    for (int s = 1; s < count; s++) {
      int bestIdx = -1;
      double bestMinDist = -1;
      for (int i = 0; i < n; i++) {
        if (used[i]) continue;
        double minDistToSelected = Double.MAX_VALUE;
        for (int j = 0; j < s; j++) {
          double d = angleDiff(viable[i], selected[j]);
          if (d < minDistToSelected) minDistToSelected = d;
        }
        if (minDistToSelected > bestMinDist) {
          bestMinDist = minDistToSelected;
          bestIdx = i;
        }
      }
      if (bestIdx < 0) break;
      used[bestIdx] = true;
      selected[s] = viable[bestIdx];
    }

    return selected;
  }

  /**
   * Sort directions into loop order: from the one closest to
   * {@code startDirection}, proceeding clockwise.
   */
  public static double[] sortDirectionsForLoop(double[] directions, double startDirection) {
    int n = directions.length;
    // Compute relative angles from startDirection, normalized to [0, 360)
    double[][] relative = new double[n][2]; // [relativeAngle, originalAngle]
    for (int i = 0; i < n; i++) {
      double rel = directions[i] - startDirection;
      if (rel < 0) rel += 360;
      if (rel >= 360) rel -= 360;
      relative[i][0] = rel;
      relative[i][1] = directions[i];
    }
    // Sort by relative angle
    java.util.Arrays.sort(relative, (a, b) -> Double.compare(a[0], b[0]));
    double[] sorted = new double[n];
    for (int i = 0; i < n; i++) sorted[i] = relative[i][1];
    return sorted;
  }

  /** Absolute angular difference in [0, 180] degrees. Delegates to CheapAngleMeter. */
  public static double angleDiff(double a, double b) {
    return CheapAngleMeter.getDifferenceFromDirection(a, b);
  }

  /**
   * Radius scaling factor so a loop through the given sorted directions matches
   * the perimeter of v1.7.8's buildPointsFromCircle for the same targetPoints:
   * v1.7.8 uses a narrow arc (108-152°) while probe/isochrone loops are wider
   * (270-360°), which without scaling would run proportionally longer. Loop
   * perimeter at radius R = 2R (radial legs) + chords of 2R·sin(gap/2).
   */
  public static double computeRadiusScale(double[] sortedDirections, int targetPoints) {
    double actualPerim = computeLoopPerimeterFactor(sortedDirections);
    double refPerim = computeReferencePerimeterFactor(targetPoints);
    double scale = refPerim / actualPerim;
    // Clamp to [0.5, 1.0]:
    // - Never enlarge (>1.0): narrow arcs mean the road network is constrained in some
    //   directions (e.g., coastal, valley). Enlarging would push waypoints beyond
    //   reachable roads or segment data coverage, causing routing failures.
    //   Shorter-than-target loops are acceptable; unreachable waypoints are not.
    // - At least 0.5 to avoid degenerate tiny loops from extreme angular gaps.
    return Math.max(0.5, Math.min(1.0, scale));
  }

  /**
   * Loop perimeter / R for waypoints at the given sorted directions: 2 radial
   * legs (center to first, last back to center) plus consecutive-waypoint chords.
   */
  static double computeLoopPerimeterFactor(double[] sortedDirs) {
    int n = sortedDirs.length;
    if (n < 2) return 4.0; // degenerate: 2 radial legs of R each

    double chordSum = 0;
    for (int i = 0; i < n - 1; i++) {
      double gap = sortedDirs[i + 1] - sortedDirs[i];
      if (gap < 0) gap += 360;
      if (gap > 180) gap = 360 - gap; // use shortest arc
      chordSum += 2 * Math.sin(Math.toRadians(gap) / 2);
    }
    return 2 + chordSum; // 2R for radial legs + chord sum
  }

  /**
   * Loop perimeter / R for v1.7.8's buildPointsFromCircle: (targetPoints-1)
   * intermediate waypoints in an arc of half-span 90 − 180/targetPoints degrees.
   */
  static double computeReferencePerimeterFactor(int targetPoints) {
    int intermediates = targetPoints - 1;
    if (intermediates < 2) return 4.0;

    double arcHalfSpanDeg = 90.0 - 180.0 / targetPoints;
    double arcSpanRad = Math.toRadians(2 * arcHalfSpanDeg);
    int numGaps = intermediates - 1;
    double gapAngle = arcSpanRad / numGaps;

    double chordSum = 0;
    for (int i = 0; i < numGaps; i++) {
      chordSum += 2 * Math.sin(gapAngle / 2);
    }
    return 2 + chordSum;
  }
}
