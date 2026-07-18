package btools.router.roundtrip;

import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingEngine;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

import java.util.*;

/**
 * Quality metrics for a round-trip/loop {@link OsmTrack}: reuse %, distance ratio,
 * direction adherence, continuity, compactness, cost, closure, spur/teardrop and
 * self-intersection counts.
 *
 * <p>{@link #compositeScore} here is reporting/test-only; production candidate
 * selection uses {@code RouteChoiceScore#score}, which weighs the same raw metrics
 * differently. Several primitives ({@link #nearRevisitSpans}, {@link #crossingPoints},
 * {@link #computeRoadReusePercent}, {@link #beelineInSpurMeters}) are reused there.
 */
public final class LoopQualityMetrics {

  private final double roadReusePercent;
  private final double distanceRatio;
  private final double directionDeltaDegrees;
  private final int actualDistanceMeters;
  private final int requestedDistanceMeters;
  private final double continuityScore;
  private final int maxGapMeters;
  private final int totalGapMeters;
  private final double compactnessScore;
  private final double averageCostPerMeter;
  private final int closureDistanceMeters;
  /** Number of local out-and-back "spur" detours (see {@link #computeSpurInfo}). */
  private final int spurCount;
  /** Arc length (m) of the longest detected spur, 0 if none. */
  private final int worstSpurMeters;
  /** Total self-intersections (transverse X-crossings) of the route shape. */
  private final int selfIntersections;
  /** Of {@link #selfIntersections}, those enclosing only a SHORT arc — a small detour
   *  loop/lasso rather than a structural outbound-vs-return crossing. See {@link #detectCrossings}. */
  private final int smallLoopCrossings;

  /**
   * Reconstruct from primitive fields. Used only by {@code LoopQualityTest}'s on-disk
   * cache to rehydrate results across Gradle test forks; not part of the normal write
   * path — use {@link #compute(OsmTrack, int, double)} for new metrics.
   */
  public static LoopQualityMetrics fromFields(double roadReusePercent, double distanceRatio,
                                              double directionDeltaDegrees, int actualDistanceMeters,
                                              int requestedDistanceMeters, double continuityScore,
                                              int maxGapMeters, int totalGapMeters,
                                              double compactnessScore, double averageCostPerMeter,
                                              int closureDistanceMeters, int spurCount,
                                              int worstSpurMeters, int selfIntersections,
                                              int smallLoopCrossings) {
    return new LoopQualityMetrics(roadReusePercent, distanceRatio, directionDeltaDegrees,
      actualDistanceMeters, requestedDistanceMeters, continuityScore, maxGapMeters, totalGapMeters,
      compactnessScore, averageCostPerMeter, closureDistanceMeters, spurCount, worstSpurMeters,
      selfIntersections, smallLoopCrossings);
  }

  private LoopQualityMetrics(double roadReusePercent, double distanceRatio,
                             double directionDeltaDegrees, int actualDistanceMeters,
                             int requestedDistanceMeters, double continuityScore,
                             int maxGapMeters, int totalGapMeters,
                             double compactnessScore, double averageCostPerMeter,
                             int closureDistanceMeters, int spurCount, int worstSpurMeters,
                             int selfIntersections, int smallLoopCrossings) {
    this.roadReusePercent = roadReusePercent;
    this.distanceRatio = distanceRatio;
    this.directionDeltaDegrees = directionDeltaDegrees;
    this.actualDistanceMeters = actualDistanceMeters;
    this.requestedDistanceMeters = requestedDistanceMeters;
    this.continuityScore = continuityScore;
    this.maxGapMeters = maxGapMeters;
    this.totalGapMeters = totalGapMeters;
    this.compactnessScore = compactnessScore;
    this.averageCostPerMeter = averageCostPerMeter;
    this.closureDistanceMeters = closureDistanceMeters;
    this.spurCount = spurCount;
    this.worstSpurMeters = worstSpurMeters;
    this.selfIntersections = selfIntersections;
    this.smallLoopCrossings = smallLoopCrossings;
  }

  /**
   * Compute quality metrics for a round-trip track.
   *
   * @param requestedDirectionDeg requested start direction, degrees [0, 360)
   */
  public static LoopQualityMetrics compute(OsmTrack track, int requestedDistanceMeters,
                                           double requestedDirectionDeg) {
    return compute(track, requestedDistanceMeters, requestedDirectionDeg,
      LoopAnalysis.of(track.nodes));
  }

  /**
   * As {@link #compute(OsmTrack, int, double)} with the crossing analysis
   * supplied by the caller — the scorer path passes the gate's already-computed
   * {@link LoopAnalysis} instead of re-scanning the same track.
   */
  static LoopQualityMetrics compute(OsmTrack track, int requestedDistanceMeters,
                                    double requestedDirectionDeg, LoopAnalysis analysis) {
    List<OsmPathElement> nodes = track.nodes;

    double reusePercent = computeRoadReusePercent(nodes);
    double distRatio = (requestedDistanceMeters > 0)
      ? (double) track.distance / requestedDistanceMeters
      : 0.0;
    double dirDelta = computeDirectionDelta(nodes, requestedDirectionDeg);

    // Continuity: detect beeline gaps (consecutive points >100m apart)
    int[] gapInfo = computeGapInfo(nodes);
    int maxGap = gapInfo[0];
    int totalGap = gapInfo[1];
    double contScore = (track.distance > 0)
      ? Math.max(0.0, Math.min(1.0, 1.0 - (double) totalGap / track.distance))
      : 1.0;

    // Compactness: convex hull area vs ideal circle area
    double compact = computeCompactnessScore(nodes, track.distance);

    // Average cost per meter
    double avgCostPerMeter = (track.distance > 0)
      ? (double) track.cost / track.distance
      : 0.0;

    // Closure distance
    int closureDist = 0;
    if (nodes.size() >= 2) {
      OsmPathElement first = nodes.get(0);
      OsmPathElement last = nodes.get(nodes.size() - 1);
      closureDist = first.calcDistance(last);
    }

    int[] spurInfo = computeSpurInfo(nodes);

    return new LoopQualityMetrics(reusePercent, distRatio, dirDelta,
      track.distance, requestedDistanceMeters, contScore, maxGap, totalGap,
      compact, avgCostPerMeter, closureDist, spurInfo[0], spurInfo[1],
      analysis.selfIntersections, analysis.smallLoopCrossings);
  }

  /** Spur detector: a near-revisit must be this close spatially (m) to count. */
  static final double SPUR_EPS_METERS = 60.0;
  /** Lower arc-gap bound (m): closer revisits are local weaving, not an out-and-back. */
  static final double SPUR_MIN_ARC_GAP = 600.0;
  /** Upper arc-gap bound (m): keeps the detection LOCAL so the loop's own closure
   *  (start≈end over the full perimeter) and broad legitimate lobes are not flagged. */
  static final double SPUR_MAX_ARC_GAP = 6000.0;
  /** Wider arc-gap bound (m) for the beeline-in-spur detector: 6 km {@link #SPUR_MAX_ARC_GAP}
   *  misses the Basel→Ettingen dead-end out-and-back (~7.3 km). Matches
   *  {@code RouteChoiceScore.NEAR_REVISIT_MAX_ARC_M} so report telemetry and the AUTO
   *  teardrop term agree on the spur span. */
  static final double BEELINE_MAX_ARC_GAP = 10000.0;

  /** A self-crossing whose smaller enclosed arc is at most this long (m) is
   *  attributed to a small detour loop / lasso rather than a far-apart structural
   *  crossing (outbound leg vs return leg). */
  static final double SMALL_LOOP_MAX_ARC_METERS = 4000.0;
  /**
   * Detect transverse self-intersections (X-crossings) and classify each by enclosed arc: a
   * crossing whose smaller enclosed sub-loop is short (≤ {@link #SMALL_LOOP_MAX_ARC_METERS}) is a
   * small detour loop/lasso; one enclosing most of the perimeter is a structural outbound-vs-return
   * crossing. The segment-pair phase runs through
   * {@link RoundTripQualityGate#countSegmentPairCrossings} — the gate's grid-accelerated scan —
   * so the report metric and the gate literally share one implementation, at full resolution
   * for every track size (the historical sampling cap fabricated crossings by chord-cutting
   * curves: a clean 3,787-node Nice track once read 57 phantom crossings against gate count 0).
   *
   * @return int[]{totalCrossings, smallLoopCrossings}
   */
  static int[] detectCrossings(List<OsmPathElement> nodes) {
    // Delegates to the shared one-pass analysis (see LoopAnalysis) — the
    // historical inline scans lived here and are preserved there verbatim,
    // including the corridor-crossings-count-only rule (structural shared-run
    // knots must not inflate the lasso surcharge).
    LoopAnalysis a = LoopAnalysis.of(nodes);
    return new int[]{a.selfIntersections, a.smallLoopCrossings};
  }

  /**
   * Like {@link #detectCrossings} but returns crossing LOCATIONS for map highlighting: one
   * {@code {lon, lat, enclosedArcMeters}} per crossing, in degrees. Shares the gate's
   * segment-pair scan (same exemptions and ceiling; the bridge check needs per-edge messages,
   * so on simplified render-time geometry markers can over-show relative to the test-time
   * count — counts stay authoritative). Report/visualization only.
   */
  public static List<double[]> crossingPoints(List<OsmPathElement> nodes) {
    return LoopAnalysis.of(nodes).crossingPoints;
  }

  /**
   * Detect local out-and-back "spur" detours (the thin antenna the planner emits for a waypoint
   * reachable only via a dead-end corridor).
   *
   * <p>A spur is a <em>local near-revisit</em>: the route returns within {@link #SPUR_EPS_METERS}
   * of a point passed {@link #SPUR_MIN_ARC_GAP}–{@link #SPUR_MAX_ARC_GAP} of riding earlier. The
   * arc-gap band discriminates: the lower bound rejects switchback hairpins and local weaving;
   * the upper bound keeps it LOCAL so the loop's own closure and broad fat lobes are not flagged.
   * Validated against a labelled Berlin gravel_E case (flags the Wegendorf out-and-back, not the
   * adjacent fat lobe). Reporting/gate signal only.
   *
   * @return int[]{spurCount, worstSpurArcMeters}
   */
  public static int[] computeSpurInfo(List<OsmPathElement> nodes) {
    int n = nodes.size();
    if (n < 4) return new int[]{0, 0};
    // Delegates to the generalized near-revisit scan (2026-07-18 dedup: the
    // two loops were structurally identical, this one with the SPUR_* bounds
    // baked in) and derives the count / worst arc from the spans.
    double[] cum = LoopGeometry.cumulativeDistances(nodes);
    int spurCount = 0;
    int worstArc = 0;
    for (int[] span : nearRevisitSpans(nodes, cum, SPUR_EPS_METERS, SPUR_MIN_ARC_GAP, SPUR_MAX_ARC_GAP)) {
      spurCount++;
      int arc = (int) Math.round(cum[span[1]] - cum[span[0]]);
      if (arc > worstArc) worstArc = arc;
    }
    return new int[]{spurCount, worstArc};
  }

  /**
   * Generalised, parameterised near-revisit scan: the spans where a track returns within
   * {@code epsMeters} of a point passed {@code minArcMeters}–{@code maxArcMeters} of riding
   * earlier. Same primitive as {@link #computeSpurInfo} but returns the {@code [i, j]} node-index
   * spans, so a caller can map a span back to its leg(s). Spans are non-overlapping (after a hit
   * the scan resumes at {@code j}).
   *
   * <p>Used by the planner's elastic anti-reuse retry to decide whether a shipped leg contains a
   * penalty-induced out-and-back. Deliberately liberal — only a <em>trigger</em> for a
   * re-route-and-compare, so a false positive costs one wasted re-route, never a wrong output.
   * Callers targeting the Basel→Ettingen class (≈7.3 km) pass {@code maxArcMeters} ≈10 km, wider
   * than {@link #SPUR_MAX_ARC_GAP} (6 km).
   */
  public static List<int[]> nearRevisitSpans(List<OsmPathElement> nodes,
                                             double epsMeters, double minArcMeters,
                                             double maxArcMeters) {
    if (nodes == null || nodes.size() < 4) return new ArrayList<>();
    return nearRevisitSpans(nodes, LoopGeometry.cumulativeDistances(nodes),
      epsMeters, minArcMeters, maxArcMeters);
  }

  /** As above with the caller's cumulative-distance array — callers that
   *  already built one (spur info, the teardrop scorer) avoid a second pass. */
  static List<int[]> nearRevisitSpans(List<OsmPathElement> nodes, double[] cum,
                                      double epsMeters, double minArcMeters,
                                      double maxArcMeters) {
    List<int[]> spans = new ArrayList<>();
    int n = nodes == null ? 0 : nodes.size();
    if (n < 4) return spans;

    int i = 0;
    while (i < n) {
      int bestJ = -1;
      OsmPathElement a = nodes.get(i);
      for (int j = i + 1; j < n; j++) {
        double gap = cum[j] - cum[i];
        if (gap > maxArcMeters) break;
        if (gap >= minArcMeters && a.calcDistance(nodes.get(j)) <= epsMeters) {
          bestJ = j;
        }
      }
      if (bestJ >= 0) {
        spans.add(new int[]{i, bestJ});
        i = bestJ; // non-overlapping spans
      } else {
        i++;
      }
    }
    return spans;
  }

  /**
   * Length (m) of the longest single edge that is BOTH long and <em>null-way</em>
   * (no {@code wayKeyValues}) — the beeline fingerprint.
   *
   * <p>The conjunction is the discriminator (established empirically): long+tagged is a real rural
   * road; short+null is harmless undetailed geometry (shape nodes survive, so it follows the real
   * road shape); long+null is a beeline — a straight cut with no shape and no tags, what
   * {@link RoutingEngine#retrackForDetail} ships when it cannot snap a straight expansion-guide
   * onto the real road. Measuring the longest single null-way edge (not a contiguous sum) isolates
   * the beeline from harmless undetailed roads. Reporting/gate signal only.
   */
  public static int maxSingleNullEdgeMeters(OsmTrack track) {
    if (track == null || track.nodes == null) return 0;
    List<OsmPathElement> nodes = track.nodes;
    int max = 0;
    for (int i = 1; i < nodes.size(); i++) {
      OsmPathElement curr = nodes.get(i);
      String tags = (curr.message != null) ? curr.message.getWayKeyValues() : null;
      boolean nullWay = (tags == null) || tags.isEmpty();
      if (nullWay) {
        int seg = nodes.get(i - 1).calcDistance(curr);
        if (seg > max) max = seg;
      }
    }
    return max;
  }

  /**
   * The beeline-in-dead-end fingerprint: the longest single null-way edge (m) lying <em>inside a
   * detected spur span</em> ({@link #computeSpurInfo}) — the <b>intersection</b> of two signals
   * that each over-fire alone (a long null-way edge; a near-revisit spur). A straight untagged cut
   * within a near-revisit is the dead-end beeline: the route shot straight to a dead-end waypoint
   * and came back, which {@link RoutingEngine#retrackForDetail} could not snap to a real way. A
   * real long road is traversed once, not inside a near-revisit, so it is excluded.
   * @return the longest single null-way edge within any spur span (m), 0 if none.
   */
  static int beelineInSpurMeters(OsmTrack track) {
    int[] d = beelineInSpurDetail(track);
    return d[0];
  }

  /**
   * Like {@link #beelineInSpurMeters} but also returns the flagged edge's endpoints for map
   * location: {@code [meters, startIlon, startIlat, endIlon, endIlat]}; coordinates 0 when none.
   */
  static int[] beelineInSpurDetail(OsmTrack track) {
    int[] none = new int[]{0, 0, 0, 0, 0};
    if (track == null || track.nodes == null) return none;
    List<OsmPathElement> nodes = track.nodes;
    int n = nodes.size();
    if (n < 4) return none;
    double[] cum = LoopGeometry.cumulativeDistances(nodes);
    double total = cum[n - 1];
    int[] worst = none;
    int i = 0;
    while (i < n) {
      int bestJ = -1;
      OsmPathElement a = nodes.get(i);
      for (int j = i + 1; j < n; j++) {
        double gap = cum[j] - cum[i];
        if (gap > BEELINE_MAX_ARC_GAP) break;
        // A span covering most of the perimeter is the loop's own start≈end
        // closure (reachable on loops shorter than the arc cap), not a spur —
        // same exclusion as RouteChoiceScore.CLOSURE_EXCLUSION_FRACTION.
        if (total > 0 && gap > RouteChoiceScore.CLOSURE_EXCLUSION_FRACTION * total) break;
        if (gap >= SPUR_MIN_ARC_GAP && a.calcDistance(nodes.get(j)) <= SPUR_EPS_METERS) bestJ = j;
      }
      if (bestJ >= 0) {
        // longest single null-way edge within [i, bestJ]
        for (int k = i + 1; k <= bestJ; k++) {
          OsmPathElement curr = nodes.get(k);
          String tags = (curr.message != null) ? curr.message.getWayKeyValues() : null;
          if (tags == null || tags.isEmpty()) {
            OsmPathElement prev = nodes.get(k - 1);
            int seg = prev.calcDistance(curr);
            if (seg > worst[0]) {
              worst = new int[]{seg, prev.getILon(), prev.getILat(), curr.getILon(), curr.getILat()};
            }
          }
        }
        i = bestJ;
      } else {
        i++;
      }
    }
    return worst;
  }

  /**
   * Percentage of the track that reuses the same road edges. An edge is the unordered pair of
   * consecutive node positions; if it appears N times, (N-1) traversals count as reuse.
   */
  static double computeRoadReusePercent(List<OsmPathElement> nodes) {
    if (nodes.size() < 2) return 0.0;

    // Spatial corridor overlap (a parallel return on a different way) unioned
    // with edge-identity retrace, so this metric — consumed by RouteChoiceScore
    // (W_REUSE) and the composite score — sees same-corridor-back that edge
    // identity is blind to, and cannot drift from ReuseClassifier's gate which
    // uses the same primitive.
    boolean[] spatialOverlap = CorridorOverlapIndex.computeEdgeOverlap(nodes);

    Map<Long, int[]> edgeCounts = new HashMap<>();
    double totalDistance = 0;
    double reusedDistance = 0;

    for (int i = 1; i < nodes.size(); i++) {
      OsmPathElement a = nodes.get(i - 1);
      OsmPathElement b = nodes.get(i);
      int segDist = a.calcDistance(b);
      totalDistance += segDist;

      // Mixed undirected edge key (matches GreedyRoundTripPlanner.edgeKey). The
      // old `lo*31 + hi` form is a trivially-collidable linear combination of
      // packed (ilon<<32)|ilat ids, which could fold a genuinely-new edge into
      // an existing bucket and inflate the reuse percentage.
      long edgeKey = LoopGeometry.edgeKey(a, b);

      int[] entry = edgeCounts.get(edgeKey);
      boolean identityReuse = entry != null;
      if (entry == null) {
        edgeCounts.put(edgeKey, new int[]{1, segDist});
      } else {
        entry[0]++;
      }
      // Second/subsequent identity traversal OR a spatial parallel corridor.
      boolean spatial = (i - 1) < spatialOverlap.length && spatialOverlap[i - 1];
      if (identityReuse || spatial) {
        reusedDistance += segDist;
      }
    }

    return (totalDistance > 0) ? (reusedDistance / totalDistance) * 100.0 : 0.0;
  }

  /** Profile-ideal cost-per-meter (cycleway/tertiary on fastbike). Score plateaus at 1.0 below this. */
  private static final double IDEAL_COST_PER_METER = 1.5;
  /** Cost-per-meter at which a route is on roads the profile actively dislikes; score = 0. */
  private static final double ZERO_SCORE_COST_PER_METER = 4.0;

  /**
   * Profile-match score in [0,1]: 1.0 at cost/m ≤ {@link #IDEAL_COST_PER_METER}, decaying linearly
   * to 0.0 at {@link #ZERO_SCORE_COST_PER_METER}+. 0.0 if cost data is missing.
   */
  static double computeCostMatchScore(double avgCostPerMeter) {
    if (avgCostPerMeter <= 0) return 0.0;
    return Math.max(0.0, Math.min(1.0,
      (ZERO_SCORE_COST_PER_METER - avgCostPerMeter) / (ZERO_SCORE_COST_PER_METER - IDEAL_COST_PER_METER)));
  }

  /**
   * Angular delta between the requested direction and the track's principal axis (bearing from
   * start to the farthest point). For loops, initial heading is ambiguous — clockwise vs
   * counter-clockwise differ by ~180° — so the farthest-point bearing captures the
   * traversal-invariant principal axis. For open tracks the farthest point is usually the
   * endpoint, so it stays equivalent to overall heading.
   */
  static double computeDirectionDelta(List<OsmPathElement> nodes,
                                      double requestedDirectionDeg) {
    if (nodes.size() < 2) return 0.0;

    OsmPathElement first = nodes.get(0);
    OsmPathElement farthest = first;
    int maxDist = 0;
    for (OsmPathElement n : nodes) {
      int d = first.calcDistance(n);
      if (d > maxDist) {
        maxDist = d;
        farthest = n;
      }
    }
    if (maxDist == 0) return 0.0;

    // cos(lat)-scaled bearing: the requested direction is a true compass
    // bearing, and the raw integer-delta angle is off by up to ~12° at 50°N.
    double actualDirection = CheapRuler.getScaledBearing(
      first.getILon(), first.getILat(),
      farthest.getILon(), farthest.getILat());

    return CheapAngleMeter.getDifferenceFromDirection(requestedDirectionDeg, actualDirection);
  }

  /** A beeline shorter than this is a waypoint-snap connector, not a routing gap. */
  private static final int BEELINE_MIN_METERS = 500;

  /** Pseudo-tag BRouter writes on synthetic beeline links (see OsmPath.addAddionalPenalty). */
  private static final String BEELINE_MARKER = "direct_segment";
  private static final String FERRY_MARKER = "route=ferry";

  /**
   * Compute gap information for consecutive track points.
   * A "gap" is a segment that does not follow a real road:
   * <ol>
   *   <li>a ferry segment (carries the {@code route=ferry} tag), or</li>
   *   <li>a synthetic beeline/shortcut longer than {@code BEELINE_MIN_METERS}.</li>
   * </ol>
   * The discriminator is BRouter's own marker, not segment length or tag-absence:
   * a beeline link carries the pseudo-tag {@code direct_segment=N}
   * ({@code OsmPath#addAddionalPenalty}, {@code descriptionBitmap == null} branch),
   * while real road links carry their actual way tags (and only in detail mode).
   * Matching on the {@code direct_segment} marker therefore detects real beelines of
   * any length and never penalizes a legitimate long road segment (rural straight,
   * bridge, sparse-node highway), which never carries that marker. The length floor
   * skips trivial waypoint-snap connectors.
   *
   * @return int[]{maxGapMeters, totalGapMeters}
   */
  static int[] computeGapInfo(List<OsmPathElement> nodes) {
    int maxGap = 0;
    int totalGap = 0;

    for (int i = 1; i < nodes.size(); i++) {
      OsmPathElement prev = nodes.get(i - 1);
      OsmPathElement curr = nodes.get(i);
      int dist = prev.calcDistance(curr);

      String tags = (curr.message != null) ? curr.message.getWayKeyValues() : null;
      boolean isFerry = tags != null && tags.contains(FERRY_MARKER);
      boolean isBeeline = tags != null && tags.contains(BEELINE_MARKER);

      // Ferries are gaps at any length; beelines only once they exceed the
      // connector threshold (a short direct segment is just a waypoint snap).
      boolean isGap = isFerry || (isBeeline && dist > BEELINE_MIN_METERS);

      if (isGap) {
        totalGap += dist;
        if (dist > maxGap) {
          maxGap = dist;
        }
      }
    }
    return new int[]{maxGap, totalGap};
  }

  /**
   * Compactness score clamped to [0, 1]: convex-hull area of track points over the area of a
   * circle with the same circumference as the track distance ({@code distance} in meters). A
   * perfect circle scores 1.0, an out-and-back line ~0.
   */
  static double computeCompactnessScore(List<OsmPathElement> nodes, int distance) {
    if (nodes.size() < 3 || distance <= 0) return 0.0;

    // Subsample for performance (at most ~1000 points, minimum step of 1)
    List<double[]> points = new ArrayList<>();
    long centerILatSum = 0;
    int step = Math.max(1, nodes.size() / 1000);
    for (int i = 0; i < nodes.size(); i += step) {
      OsmPathElement n = nodes.get(i);
      centerILatSum += n.getILat();
      points.add(new double[]{n.getILon(), n.getILat()});
    }
    // Always include the last point
    OsmPathElement last = nodes.get(nodes.size() - 1);
    if (points.isEmpty() || points.get(points.size() - 1)[0] != last.getILon()
      || points.get(points.size() - 1)[1] != last.getILat()) {
      points.add(new double[]{last.getILon(), last.getILat()});
    }

    if (points.size() < 3) return 0.0;

    // Get meter scales at the center latitude
    int centerILat = (int) (centerILatSum / points.size());
    double[] kxky = CheapRuler.getLonLatToMeterScales(centerILat);
    double kx = kxky[0]; // ilon units to meters
    double ky = kxky[1]; // ilat units to meters

    // Convert to meter coordinates
    double[] xs = new double[points.size()];
    double[] ys = new double[points.size()];
    for (int i = 0; i < points.size(); i++) {
      xs[i] = points.get(i)[0] * kx;
      ys[i] = points.get(i)[1] * ky;
    }

    double hullArea = convexHullArea(xs, ys);

    // Area of a circle with circumference = distance
    // circumference = 2*PI*r => r = distance / (2*PI)
    // area = PI * r^2 = PI * (distance / (2*PI))^2 = distance^2 / (4*PI)
    double circleArea = (double) distance * distance / (4.0 * Math.PI);

    if (circleArea <= 0) return 0.0;
    return Math.max(0.0, Math.min(1.0, hullArea / circleArea));
  }

  /**
   * Area of the convex hull of 2D points via Andrew's monotone chain. O(n log n).
   */
  static double convexHullArea(double[] xs, double[] ys) {
    int n = xs.length;
    if (n < 3) return 0.0;

    // Create index array and sort by x, then y
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) idx[i] = i;
    Arrays.sort(idx, new Comparator<>() {
      @Override
      public int compare(Integer a, Integer b) {
        int cx = Double.compare(xs[a], xs[b]);
        return cx != 0 ? cx : Double.compare(ys[a], ys[b]);
      }
    });

    // Build lower hull
    int[] hull = new int[2 * n];
    int k = 0;
    for (int i = 0; i < n; i++) {
      while (k >= 2 && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) {
        k--;
      }
      hull[k++] = idx[i];
    }
    // Build upper hull
    int lower = k + 1;
    for (int i = n - 2; i >= 0; i--) {
      while (k >= lower && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) {
        k--;
      }
      hull[k++] = idx[i];
    }
    // k-1 is the hull size (last point == first point)
    if (k < 4) return 0.0; // need at least 3 distinct hull vertices for non-zero area

    // Shoelace formula for area
    double area = 0;
    for (int i = 0; i < k - 1; i++) {
      int j = (i + 1) % (k - 1);
      area += xs[hull[i]] * ys[hull[j]];
      area -= xs[hull[j]] * ys[hull[i]];
    }
    return Math.abs(area) / 2.0;
  }

  /** Cross product of vectors OA and OB where O=hull[o], A=hull[a], B=idx[b] */
  private static double cross(double[] xs, double[] ys, int o, int a, int b) {
    return (xs[a] - xs[o]) * (ys[b] - ys[o])
      - (ys[a] - ys[o]) * (xs[b] - xs[o]);
  }

  /**
   * Weighted composite quality score in [0, 1]; higher is better. Weights reflect what cyclists
   * care about in a round-trip:
   * <ul>
   *   <li><b>25% distance</b> — total length close to target</li>
   *   <li><b>20% reuse</b> — no out-and-back / road retracing</li>
   *   <li><b>20% continuity</b> — no synthetic beelines, maxGap penalty baked in</li>
   *   <li><b>20% profile match</b> — cost/m matches profile-preferred roads</li>
   *   <li><b>10% compactness</b> — convex hull area vs ideal-circle area</li>
   *   <li><b>5% direction</b> — soft hint: user direction may be unreachable</li>
   * </ul>
   * Direction is low-weighted because in asymmetric terrain (valleys, coastlines) the requested
   * direction may be unachievable and the bearing signal degrades to ~random. Closure is not
   * scored — round-trips close by construction; bad closure is a hard gate, not a soft signal.
   *
   * <p><b>Reporting / test use only — NOT the production selection formula.</b> Production ranking
   * uses {@code RouteChoiceScore#score} with different weights (closure at 10%, added shape
   * penalties). Changing weights here does not change which route production selects.
   */
  public double compositeScore() {
    double distScore = 1.0 - Math.min(1.0, Math.abs(distanceRatio - 1.0) / 0.5);
    double reuseScore = 1.0 - Math.min(1.0, roadReusePercent / 50.0);
    double dirScore = 1.0 - Math.min(1.0, directionDeltaDegrees / 180.0);
    // continuity already penalises totalGap; weight maxGap on top so a single
    // 2km beeline scores worse than two 1km beelines of the same total length.
    double maxGapScore = 1.0 - Math.min(1.0, maxGapMeters / 1500.0);
    double continuityWithMaxGap = 0.75 * continuityScore + 0.25 * maxGapScore;
    double costMatchScore = computeCostMatchScore(averageCostPerMeter);

    return 0.25 * distScore
      + 0.20 * reuseScore
      + 0.20 * continuityWithMaxGap
      + 0.20 * costMatchScore
      + 0.10 * compactnessScore
      + 0.05 * dirScore;
  }

  public double getRoadReusePercent() {
    return roadReusePercent;
  }

  public double getDistanceRatio() {
    return distanceRatio;
  }

  public double getDirectionDeltaDegrees() {
    return directionDeltaDegrees;
  }

  public int getActualDistanceMeters() {
    return actualDistanceMeters;
  }

  public int getRequestedDistanceMeters() {
    return requestedDistanceMeters;
  }

  public double getContinuityScore() {
    return continuityScore;
  }

  public int getMaxGapMeters() {
    return maxGapMeters;
  }

  public int getTotalGapMeters() {
    return totalGapMeters;
  }

  public double getCompactnessScore() {
    return compactnessScore;
  }

  public double getAverageCostPerMeter() {
    return averageCostPerMeter;
  }

  public int getClosureDistanceMeters() {
    return closureDistanceMeters;
  }

  public int getSpurCount() {
    return spurCount;
  }

  public int getWorstSpurMeters() {
    return worstSpurMeters;
  }

  /** Total transverse self-intersections (X-crossings) of the route shape. */
  public int getSelfIntersections() {
    return selfIntersections;
  }

  /** How many of {@link #getSelfIntersections} come from a small detour loop/lasso (short
   *  enclosed arc) rather than a structural crossing. */
  public int getSmallLoopCrossings() {
    return smallLoopCrossings;
  }

  @Override
  public String toString() {
    return String.format(
      "LoopQualityMetrics[reuse=%.1f%%, distRatio=%.2f (%dm/%dm), dirDelta=%.1f°, " +
        "continuity=%.2f (maxGap=%dm, totalGap=%dm), compactness=%.2f, " +
        "cost/m=%.1f, closure=%dm, spurs=%d (worst=%dm), " +
        "selfIntersections=%d (smallLoop=%d), composite=%.2f]",
      roadReusePercent, distanceRatio, actualDistanceMeters, requestedDistanceMeters,
      directionDeltaDegrees, continuityScore, maxGapMeters, totalGapMeters,
      compactnessScore, averageCostPerMeter, closureDistanceMeters, spurCount, worstSpurMeters,
      selfIntersections, smallLoopCrossings, compositeScore());
  }
}
