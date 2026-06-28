package btools.router;

import btools.util.CheapAngleMeter;

/**
 * Scores sub-route candidate endpoints for the greedy round-trip algorithm.
 * Lower score = better candidate.
 *
 * Scoring formula:
 *   score = w_dist        * |candidateDistance - subRouteTarget| / subRouteTarget
 *         + w_loop        * |total + candidateDist + returnDist - desiredTotal| / desiredTotal
 *         + w_dir         * directionPenalty * directionFade(step)
 *         + w_reuse       * visitedEdgeRatio
 *         + w_spread      * spreadPenalty(distFromStart, step, totalSteps)
 *         + w_prev        * ((distFromPrevious - subRouteTarget) / subRouteTarget)²
 *         - w_isoBonus    * isoValidatedBonus(costFromStart, bucketHits)
 *         + w_isoHostility * hostility            // 0 unless hostilityActive (paved profiles)
 *         + w_isoBonus    * isoContourDepthMismatch(sourceContour, step, totalSteps)
 *
 * The last three terms are the ISO-aware contribution: a bucket-density bonus
 * (lower score) for candidates reached by a Dijkstra expansion — start-centered
 * iso or per-step graph-native — a hostility penalty applied only
 * when {@link #setHostilityActive(boolean)} is on, and a contour-depth-mismatch
 * penalty. {@code hostility} is the contiguous-hostile-meters penalty when routed
 * leg data is available, else the cost-per-airmeter penalty.
 */
public class CandidateScorer {

  private final double wDist;
  private final double wLoop;
  private final double wDir;
  private final double wReuse;
  private final double wSpread;
  private final double wPrev;
  /** Bonus weight for a candidate validated by an isochrone Dijkstra (vs geometric ring). */
  private final double wIsoBonus;
  /** Penalty weight for high cost-per-airmeter at the candidate (hostility signal). */
  private final double wIsoHostility;
  /**
   * Whether iso-hostility scoring is active. The hostility metric
   * ({@link #isoHostilityPenalty}) assumes a paved-profile cost-per-airmeter
   * baseline of ~1.3; for MTB/gravel the baseline is ~9 (path_preference=20
   * for MTB inflates costfactor on any paved way). Applying the same
   * threshold to all profiles fires hostility on every candidate for MTB,
   * collapsing ISO_GREEDY's selection space. Off by default — turn on
   * explicitly via {@link #setHostilityActive(boolean)} only for profiles
   * whose typical cost/airDist is close to 1.0.
   */
  private boolean hostilityActive;

  public CandidateScorer() {
    this(1.0, 2.0, 0.5, 3.0, 1.5, 1.5);
  }

  public CandidateScorer(double wDist, double wLoop, double wDir, double wReuse, double wSpread) {
    this(wDist, wLoop, wDir, wReuse, wSpread, 1.5);
  }

  public CandidateScorer(double wDist, double wLoop, double wDir, double wReuse, double wSpread, double wPrev) {
    // ISO-aware weights are intentionally small — metadata acts as a tie-break
    // between similar-quality candidates, not a strong quality override. A
    // routed-leg-actually-shorter candidate must still win on the geometric
    // terms; iso bonus only tilts ties.
    this(wDist, wLoop, wDir, wReuse, wSpread, wPrev, 0.05, 0.3);
  }

  /**
   * Full ctor including the ISO-aware weights. The bonus rewards iso-validated
   * candidates of acceptable bucket density (small constant, tie-break only);
   * the hostility weight penalises candidates whose Dijkstra cost-per-airmeter
   * is high (mountains, switchbacks, sea-blocked sectors). Both default to 0
   * for radial candidates with no iso metadata.
   */
  public CandidateScorer(double wDist, double wLoop, double wDir, double wReuse, double wSpread,
                         double wPrev, double wIsoBonus, double wIsoHostility) {
    this.wDist = wDist;
    this.wLoop = wLoop;
    this.wDir = wDir;
    this.wReuse = wReuse;
    this.wSpread = wSpread;
    this.wPrev = wPrev;
    this.wIsoBonus = wIsoBonus;
    this.wIsoHostility = wIsoHostility;
    this.hostilityActive = false; // safe default: profiles must opt in
  }

  /**
   * Enable or disable iso-hostility scoring. Only enable for paved profiles
   * (fastbike, road) where {@link #isoHostilityPenalty}'s 1.5-4.0 indirectness
   * thresholds match the profile's cost-per-airmeter baseline. MTB/gravel
   * profiles have a baseline of ~9 (every paved way costs 9× ideal) and the
   * penalty fires on every candidate, collapsing the candidate pool. The
   * other ISO weights (bonus, contour-mismatch) remain active either way.
   */
  public void setHostilityActive(boolean active) {
    this.hostilityActive = active;
  }

  /**
   * Score a candidate sub-route endpoint.
   *
   * @param candidateDistance   distance of the sub-route to this candidate (meters)
   * @param subRouteTarget     target sub-route distance (meters)
   * @param totalSoFar         total route distance accumulated so far (meters)
   * @param returnDistance      shortest return distance from candidate to start (meters)
   * @param desiredTotal       desired total loop distance (meters)
   * @param candidateBearing   bearing from current node to candidate (degrees)
   * @param directionPreference desired direction preference
   * @param step               current sub-route step (1-based)
   * @param totalSteps         total number of sub-route steps
   * @param visitedEdgeRatio   fraction of candidate path edges already visited [0,1]
   * @param distFromStart      air distance from candidate to start node (meters)
   * @param searchRadius       approximate half-diameter of the loop (desiredDistance/4)
   * @param distFromPrevious   air distance from previous waypoint to this candidate (meters),
   *                           or -1 if no previous waypoint (first step)
   * @return score (lower is better)
   */
  public double score(double candidateDistance, double subRouteTarget,
                      double totalSoFar, double returnDistance, double desiredTotal,
                      double candidateBearing, DirectionPreference directionPreference,
                      int step, int totalSteps,
                      double visitedEdgeRatio, double distFromStart, double searchRadius,
                      double distFromPrevious) {
    // Back-compat path — no iso metadata.
    return score(candidateDistance, subRouteTarget, totalSoFar, returnDistance, desiredTotal,
      candidateBearing, directionPreference, step, totalSteps,
      visitedEdgeRatio, distFromStart, searchRadius, distFromPrevious,
      RoundTripCandidateProvider.NO_ISO_COST, RoundTripCandidateProvider.NO_ISO_DENSITY,
      RoundTripCandidateProvider.NO_ISO_CONTOUR);
  }

  /** Two-arg iso-metadata signature (no sourceContour) — back-compat for older planner callers. */
  public double score(double candidateDistance, double subRouteTarget,
                      double totalSoFar, double returnDistance, double desiredTotal,
                      double candidateBearing, DirectionPreference directionPreference,
                      int step, int totalSteps,
                      double visitedEdgeRatio, double distFromStart, double searchRadius,
                      double distFromPrevious,
                      double costFromStart, int bucketHits) {
    return score(candidateDistance, subRouteTarget, totalSoFar, returnDistance, desiredTotal,
      candidateBearing, directionPreference, step, totalSteps,
      visitedEdgeRatio, distFromStart, searchRadius, distFromPrevious,
      costFromStart, bucketHits, RoundTripCandidateProvider.NO_ISO_CONTOUR);
  }

  /**
   * Score a candidate, with optional ISO-aware metadata (option A).
   *
   * @param costFromStart Dijkstra cost-units from the loop start to this
   *                      candidate; {@link RoundTripCandidateProvider#NO_ISO_COST}
   *                      when the candidate is from a non-iso provider.
   * @param bucketHits    Population of the candidate's angular bucket;
   *                      {@link RoundTripCandidateProvider#NO_ISO_DENSITY} when
   *                      unavailable. Higher = denser road network in that
   *                      sector.
   */
  public double score(double candidateDistance, double subRouteTarget,
                      double totalSoFar, double returnDistance, double desiredTotal,
                      double candidateBearing, DirectionPreference directionPreference,
                      int step, int totalSteps,
                      double visitedEdgeRatio, double distFromStart, double searchRadius,
                      double distFromPrevious,
                      double costFromStart, int bucketHits, int sourceContour) {
    return score(candidateDistance, subRouteTarget, totalSoFar, returnDistance, desiredTotal,
      candidateBearing, directionPreference, step, totalSteps,
      visitedEdgeRatio, distFromStart, searchRadius, distFromPrevious,
      costFromStart, bucketHits, sourceContour, -1);
  }

  /**
   * Phase 2 v2 entry point. {@code routedLegWorstContiguousHostileMeters}
   * is the longest unbroken hostile stretch in the candidate's routed
   * sub-track. The planner computes it with the scorer-side approximation
   * {@link RoundTripQualityGate#worstContiguousCostlyMetersForScorer}, which
   * works on the single-pass tracks scoring sees (the gate's stricter
   * tag-aware {@link RoundTripQualityGate#worstContiguousHostileMetersPaved}
   * needs per-edge metadata it does not yet have). When ≥ 0 it REPLACES the
   * {@link #isoHostilityPenalty} term — the routed signal is strictly more
   * accurate than iso-Dijkstra indirectness because the route is now known,
   * not estimated. Pass {@code -1} to retain the legacy iso behaviour.
   *
   * <p>Only paved profiles activate hostility (gated by
   * {@link #setHostilityActive}); gravel/MTB routes ignore the signal.
   */
  public double score(double candidateDistance, double subRouteTarget,
                      double totalSoFar, double returnDistance, double desiredTotal,
                      double candidateBearing, DirectionPreference directionPreference,
                      int step, int totalSteps,
                      double visitedEdgeRatio, double distFromStart, double searchRadius,
                      double distFromPrevious,
                      double costFromStart, int bucketHits, int sourceContour,
                      int routedLegWorstContiguousHostileMeters) {

    double distScore = distanceScore(candidateDistance, subRouteTarget);
    double loopScore = loopFeasibilityScore(totalSoFar, candidateDistance, returnDistance, desiredTotal);
    double dirScore = directionScore(candidateBearing, directionPreference, step);
    double reuseScore = visitedEdgeRatio;
    double spreadScore = spreadPenalty(distFromStart, searchRadius, step, totalSteps);
    double prevScore = previousDistancePenalty(distFromPrevious, subRouteTarget);
    double isoBonus = isoValidatedBonus(costFromStart, bucketHits);
    double hostility;
    if (!hostilityActive) {
      hostility = 0.0;
    } else if (routedLegWorstContiguousHostileMeters >= 0) {
      hostility = contiguousHostilityPenalty(routedLegWorstContiguousHostileMeters);
    } else {
      hostility = isoHostilityPenalty(costFromStart, distFromStart);
    }
    double contourMismatch = isoContourDepthMismatch(sourceContour, step, totalSteps);

    return wDist * distScore
      + wLoop * loopScore
      + wDir * dirScore
      + wReuse * reuseScore
      + wSpread * spreadScore
      + wPrev * prevScore
      - wIsoBonus * isoBonus
      + wIsoHostility * hostility
      + wIsoBonus * contourMismatch;
  }

  /**
   * Phase-appropriate depth penalty for iso candidates: early loop steps (1–2)
   * should prefer deep frontier candidates (contour 100); late steps should
   * prefer shallower candidates (closer to current). Penalty in [0, 1]; 0 when
   * the contour is missing or already matches the step's preferred depth.
   */
  double isoContourDepthMismatch(int sourceContour, int step, int totalSteps) {
    if (sourceContour == RoundTripCandidateProvider.NO_ISO_CONTOUR) return 0;
    // Preferred contour by step phase, ramping 100 → 25 across totalSteps.
    // Step 1 wants frontier (100); final step wants near-in (25).
    // Clamp to [0,1] so an out-of-contract step (step<1 or step>totalSteps) can
    // never push `preferred` outside its [25,100] design range.
    double phaseFraction = totalSteps <= 1 ? 0.0
      : Math.max(0.0, Math.min(1.0, (step - 1.0) / (totalSteps - 1.0)));
    double preferred = 100 - 75 * phaseFraction;  // 100 at step 1 → 25 at last step
    double diff = Math.abs(sourceContour - preferred);
    return Math.min(1.0, diff / 75.0);
  }

  /**
   * Bonus (subtracted from the score, so makes the candidate better) for a
   * Dijkstra-expansion candidate of acceptable bucket density. Plateaus at
   * hits≥3 — extra-dense buckets do NOT score better than typical-dense ones
   * (we don't want to over-prefer urban-grid candidates). Sparse buckets
   * (hits&lt;3) ramp down to 0 (fragility signal — one-shot road slivers,
   * usually dead-end pockets that force the next leg to backtrack out).
   *
   * <p>Gated on bucket density ALONE: per-step graph-native candidates (the
   * production GREEDY default) carry real {@code bucketHits} but a sentinel
   * {@code costFromStart} — the former conjunctive guard zeroed the dead-end
   * signal exactly on that path. {@code costFromStart} stays in the signature
   * for callers/tests but no longer gates the bonus.
   * Radial candidates (no density metadata) get 0.
   */
  double isoValidatedBonus(double costFromStart, int bucketHits) {
    if (bucketHits == RoundTripCandidateProvider.NO_ISO_DENSITY) {
      return 0;
    }
    if (bucketHits >= 3) return 1.0;
    return bucketHits / 3.0;
  }

  /**
   * Penalty for high cost-per-airmeter at the candidate — Dijkstra had to spend
   * many cost-units per meter of straight-line distance to reach it, which
   * means hostile terrain (mountains, sea-blocked, low road density).
   * Profile-typical roads have cost/airDist ≈ 1.3; mountains/switchbacks 3-5.
   * Scaled to [0, 1] linearly between 1.5 and 4.0; 0 if iso data is missing.
   */
  double isoHostilityPenalty(double costFromStart, double distFromStart) {
    if (costFromStart == RoundTripCandidateProvider.NO_ISO_COST || distFromStart <= 50) {
      return 0;
    }
    double indirectness = costFromStart / distFromStart;
    if (indirectness <= 1.5) return 0;
    if (indirectness >= 4.0) return 1;
    return (indirectness - 1.5) / 2.5;
  }

  /**
   * Phase 2 v2: penalty for the longest unbroken hostile stretch in a
   * routed sub-track. Mirrors {@link RoundTripQualityGate}'s contiguous-
   * stretch ceiling — the cyclist's complaint surface is "I was sent
   * down 2 km of farm track," not "my route averaged a 1.4 cost ratio."
   * Feeding this back into candidate scoring lets the planner prefer
   * sub-routes whose worst stretch stays well under the gate's cap.
   *
   * <p>Mapping: 0 m → 0; ramps linearly from {@code SAFE_FLOOR} to
   * {@code MAX_CONTIGUOUS_HOSTILE_METERS}; saturates at 1 once the
   * routed leg already exceeds the gate's hard ceiling.
   *
   * <p>Sentinel {@code -1} signals "no data" (e.g. non-paved profile,
   * or caller did not compute) and returns 0.
   */
  double contiguousHostilityPenalty(int worstStretchMeters) {
    if (worstStretchMeters < 0) return 0;
    final int safeFloor = 500; // well under the 1500 m gate cap → no penalty
    if (worstStretchMeters <= safeFloor) return 0;
    int cap = RoundTripQualityGate.MAX_CONTIGUOUS_HOSTILE_METERS;
    if (worstStretchMeters >= cap) return 1.0;
    return (worstStretchMeters - safeFloor) / (double) (cap - safeFloor);
  }

  /**
   * How far the candidate distance is from the sub-route target.
   * Normalized to [0, ~1] range.
   */
  double distanceScore(double candidateDistance, double subRouteTarget) {
    if (subRouteTarget <= 0) return 0;
    return Math.abs(candidateDistance - subRouteTarget) / subRouteTarget;
  }

  /**
   * How close the projected total (so far + candidate + return) is to the desired total.
   */
  double loopFeasibilityScore(double totalSoFar, double candidateDistance,
                              double returnDistance, double desiredTotal) {
    if (desiredTotal <= 0) return 0;
    double projectedTotal = totalSoFar + candidateDistance + returnDistance;
    return Math.abs(projectedTotal - desiredTotal) / desiredTotal;
  }

  /**
   * Strengthen the direction preference so a round-trip keeps a coherent outward
   * heading instead of wandering / doubling back into self-crossings. On by
   * default. Strength and late-step retention are calibrated constants.
   */
  static final boolean STRONG_DIRECTION = true;
  private static final double STRONG_DIRECTION_MULT = 2.5;
  /** Direction retention for steps &gt; 2 in strong mode (0 = off after step 2, lets closure win late). */
  private static final double STRONG_DIRECTION_LATE = 0.3;
  /**
   * Terrain-feasibility guard: scale the direction term's influence down when the
   * requested heading is unreachable this step (the best candidate is far
   * off-bearing — sea, no roads that way), so direction never forces a route
   * into terrain it cannot traverse. The planner sets {@link #dirReferenceOffset}
   * (best achievable |Δbearing|) each step. On by default. Mountains keep direction (roads exist, just
   * costlier) — only true blockage relaxes it.
   */
  static final boolean DIR_FEASIBILITY = true;
  private double dirReferenceOffset = 0.0;

  /** Best achievable |Δbearing| (deg) among this step's candidates; set by the planner. */
  void setDirectionReferenceOffset(double deg) { this.dirReferenceOffset = deg; }

  /**
   * Penalty for a candidate bearing that misaligns with the direction preference.
   * Scales by {@link #directionFade}(step) (full early, tapering late) and by the
   * terrain-feasibility factor (see {@link #DIR_FEASIBILITY}); 0 when the
   * preference is {@link DirectionPreference#ANY} or the fade has reached 0.
   */
  double directionScore(double candidateBearing, DirectionPreference pref, int step) {
    if (pref == null || pref == DirectionPreference.ANY) return 0;
    double fade = directionFade(step);
    if (fade <= 0) return 0;
    double angleDiff = CheapAngleMeter.getDifferenceFromDirection(pref.bearing, candidateBearing);
    // Feasibility guard: scale the direction term's INFLUENCE down when the
    // requested heading is unreachable this step (best candidate far off-bearing
    // — sea/mountain), so direction stops forcing a bad route and clean-geometry
    // / cost win instead. Scaling preserves relative ranking only when feasible;
    // when blocked (best offset ≥ 90°) the term vanishes. (Subtracting the
    // reference would NOT change per-step selection — it shifts all candidates
    // equally — so we scale, not subtract.)
    double feas = DIR_FEASIBILITY ? Math.max(0.0, 1.0 - dirReferenceOffset / 90.0) : 1.0;
    double mult = STRONG_DIRECTION ? STRONG_DIRECTION_MULT : 1.0;
    return mult * fade * feas * (angleDiff / 180.0);
  }

  /**
   * Direction preference fades: full weight at step 1, half at step 2, zero after.
   * Strong-direction mode keeps a gentle outward pull through later steps so the
   * loop stays coherent (loop-closure feasibility, w_loop, still dominates late).
   */
  double directionFade(int step) {
    if (STRONG_DIRECTION) {
      if (step <= 1) return 1.0;
      if (step <= 2) return 0.8;
      return STRONG_DIRECTION_LATE;
    }
    if (step <= 1) return 1.0;
    if (step <= 2) return 0.5;
    return 0.0;
  }

  /**
   * Penalizes candidates whose air distance from the previous waypoint differs
   * from the target sub-route distance. Implements the Silesian algorithm's
   * (d(prev, candidate) - target)^2 term which naturally prevents waypoint clustering
   * without a hard angular filter.
   *
   * @param distFromPrevious air distance from previous waypoint to candidate (meters),
   *                         or -1 if no previous waypoint (first step)
   * @param subRouteTarget   target sub-route distance (meters)
   * @return penalty in [0, inf), 0 when distFromPrevious == -1 (first step)
   */
  double previousDistancePenalty(double distFromPrevious, double subRouteTarget) {
    if (distFromPrevious < 0 || subRouteTarget <= 0) return 0;
    double ratio = (distFromPrevious - subRouteTarget) / subRouteTarget;
    return ratio * ratio;
  }

  /**
   * Penalizes candidates based on their distance from start relative to the loop phase.
   * Early steps: penalize staying too close to start (want exploration).
   * Late steps: penalize being too far from start (want closure).
   * Uses a smooth blend over phase [0.4, 0.8] to avoid abrupt preference flips.
   */
  double spreadPenalty(double distFromStart, double searchRadius, int step, int totalSteps) {
    if (searchRadius <= 0 || totalSteps <= 0) return 0;
    double phase = (double) step / totalSteps;
    double normalizedDist = distFromStart / searchRadius;

    double earlyPenalty = Math.max(0, 1.0 - normalizedDist);
    double latePenalty = Math.min(normalizedDist, 2.0);

    // Smooth transition from exploration to closure over phase [0.4, 0.8]
    double blend = Math.max(0, Math.min(1, (phase - 0.4) / 0.4));
    return earlyPenalty * (1 - blend) + latePenalty * blend;
  }
}
