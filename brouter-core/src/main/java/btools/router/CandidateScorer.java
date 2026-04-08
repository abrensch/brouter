package btools.router;

import btools.util.CheapAngleMeter;

/**
 * Scores sub-route candidate endpoints for the greedy round-trip algorithm.
 * Lower score = better candidate.
 *
 * Scoring formula:
 *   score = w_dist   * |candidateDistance - subRouteTarget| / subRouteTarget
 *         + w_loop   * |total + candidateDist + returnDist - desiredTotal| / desiredTotal
 *         + w_dir    * directionPenalty * directionFade(step)
 *         + w_reuse  * visitedEdgeRatio
 *         + w_spread * spreadPenalty(distFromStart, step, totalSteps)
 */
public class CandidateScorer {

  private final double wDist;
  private final double wLoop;
  private final double wDir;
  private final double wReuse;
  private final double wSpread;

  public CandidateScorer() {
    this(1.0, 2.0, 0.5, 3.0, 1.5);
  }

  public CandidateScorer(double wDist, double wLoop, double wDir, double wReuse, double wSpread) {
    this.wDist = wDist;
    this.wLoop = wLoop;
    this.wDir = wDir;
    this.wReuse = wReuse;
    this.wSpread = wSpread;
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
   * @return score (lower is better)
   */
  public double score(double candidateDistance, double subRouteTarget,
                      double totalSoFar, double returnDistance, double desiredTotal,
                      double candidateBearing, DirectionPreference directionPreference,
                      int step, int totalSteps,
                      double visitedEdgeRatio, double distFromStart, double searchRadius) {

    double distScore = distanceScore(candidateDistance, subRouteTarget);
    double loopScore = loopFeasibilityScore(totalSoFar, candidateDistance, returnDistance, desiredTotal);
    double dirScore = directionScore(candidateBearing, directionPreference, step);
    double reuseScore = visitedEdgeRatio;
    double spreadScore = spreadPenalty(distFromStart, searchRadius, step, totalSteps);

    return wDist * distScore
      + wLoop * loopScore
      + wDir * dirScore
      + wReuse * reuseScore
      + wSpread * spreadScore;
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
   * Penalty for misalignment with the direction preference.
   * Fades after step 2 (exploration phase only).
   */
  double directionScore(double candidateBearing, DirectionPreference pref, int step) {
    if (pref == null || pref == DirectionPreference.ANY) return 0;
    double fade = directionFade(step);
    if (fade <= 0) return 0;
    double angleDiff = CheapAngleMeter.getDifferenceFromDirection(pref.bearing, candidateBearing);
    return fade * (angleDiff / 180.0);
  }

  /**
   * Direction preference fades: full weight at step 1, half at step 2, zero after.
   */
  double directionFade(int step) {
    if (step <= 1) return 1.0;
    if (step <= 2) return 0.5;
    return 0.0;
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
