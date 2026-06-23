package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Pure-function tests for the cost-contour scoring rule + frontier road-native
 * coordinate extraction that drive {@link RoutingEngine#runIsochroneExpansion}'s
 * per-bucket frontier / contour selection and
 * {@link RoutingEngine#placeWaypointsFromIsochrone}'s position resolution.
 *
 * <p>Design properties exercised:
 * <ol>
 *   <li>Closest-to-target-cost beats farthest-air when the farther node has a
 *       poor contour fit.</li>
 *   <li>The 25/50/75 contour candidates are selected by cost proximity, not by
 *       max air-distance under a cap.</li>
 *   <li>Direct ISOCHRONE placement uses the road-native frontier coord whenever
 *       the frontier entry carries one (6-element entry); otherwise it falls
 *       back to a synthetic position via {@code CheapRuler.destination}.</li>
 * </ol>
 */
public class IsochroneCostContourTest {

  private static final double EPS = 1e-9;

  // ----- costContourScore basic correctness ---------------------------------

  @Test
  public void scoreOfOnTargetNodeIsNegativeOfAirReachBonus() {
    // path.cost == targetCost → costError = 0. Score = -0.10 * airReachBonus.
    int target = 4000;
    double radius = 1000;
    double s = RoutingEngine.costContourScore(target, target, 500, radius);
    // airReachBonus = clamp(500/1000, 0, 1) = 0.5 → score = -0.05.
    assertEquals(-0.05, s, EPS);
  }

  @Test
  public void scoreOfFarOffTargetIsLarge() {
    // path.cost = 0, target = 1000 → costError = 1.0; air reach 0 → score = 1.0.
    double s = RoutingEngine.costContourScore(0, 1000, 0, 1000);
    assertEquals(1.0, s, EPS);
  }

  @Test
  public void scoreClampsAirReachBonusAt1() {
    // dist > searchRadius caps airReachBonus at 1.0, not unbounded.
    int target = 1000;
    double radius = 1000;
    double sAt    = RoutingEngine.costContourScore(target, target, 1000, radius);
    double sBeyond = RoutingEngine.costContourScore(target, target, 5000, radius);
    assertEquals("airReachBonus saturates at dist == searchRadius",
      sAt, sBeyond, EPS);
    assertEquals(-0.10, sBeyond, EPS);
  }

  @Test
  public void scoreClampsAirReachBonusAtZero() {
    // dist < 0 (defensive) gets clamped to 0; airReachBonus contributes nothing.
    int target = 1000;
    double s = RoutingEngine.costContourScore(target, target, -100, 1000);
    assertEquals(0.0, s, EPS);
  }

  @Test
  public void scoreHandlesZeroTargetCostDefensively() {
    // Pathological target (no contour set yet) yields +∞ so any real
    // candidate dominates.
    double s = RoutingEngine.costContourScore(500, 0, 1000, 2000);
    assertTrue("zero target gives +infinity score", Double.isInfinite(s) && s > 0);
  }

  // ----- isBetterCandidate tie-break order ----------------------------------

  @Test
  public void lowerScoreWins() {
    assertTrue(RoutingEngine.isBetterCandidate(0.1, 0, 0, 0.2, 0, 0));
    assertFalse(RoutingEngine.isBetterCandidate(0.2, 0, 0, 0.1, 0, 0));
  }

  @Test
  public void tieBreakPrefersHigherCost() {
    // Same score → higher path cost wins.
    assertTrue(RoutingEngine.isBetterCandidate(0.5, 200, 1000, 0.5, 100, 1000));
    assertFalse(RoutingEngine.isBetterCandidate(0.5, 100, 1000, 0.5, 200, 1000));
  }

  @Test
  public void tieBreakPrefersHigherDistWhenCostEqual() {
    // Same score, same cost → higher dist wins.
    assertTrue(RoutingEngine.isBetterCandidate(0.5, 100, 2000, 0.5, 100, 1500));
    assertFalse(RoutingEngine.isBetterCandidate(0.5, 100, 1500, 0.5, 100, 2000));
  }

  @Test
  public void allTiesExistingRemains() {
    // Spec tie-break #3: existing candidate remains on full equality.
    assertFalse(RoutingEngine.isBetterCandidate(0.5, 100, 1000, 0.5, 100, 1000));
  }

  // ----- the spec's primary property: cost-contour beats farthest-air -------

  @Test
  public void costContourBeatsFarthestAirWhenAirNodeIsOffTarget() {
    // Same bucket, two candidates. Old "farthest air" rule would pick B.
    // New cost-contour rule picks A because B is way off the target cost.
    int costBudget = 4000;
    double searchRadius = 1000;

    // A: hit the cost budget exactly, modest air-reach.
    double scoreA = RoutingEngine.costContourScore(costBudget, costBudget, 600, searchRadius);
    // B: way under target cost (low-cost dead-end), much farther in air-distance.
    double scoreB = RoutingEngine.costContourScore(1500, costBudget, 1500, searchRadius);

    assertTrue("on-target node should beat farther-but-off-target node ("
      + "scoreA=" + scoreA + ", scoreB=" + scoreB + ")", scoreA < scoreB);

    // And the better candidate should replace the worse one.
    assertTrue(RoutingEngine.isBetterCandidate(scoreA, costBudget, 600,
      Double.POSITIVE_INFINITY, 0, 0));
    // Starting from A as best, B should NOT replace it.
    assertFalse(RoutingEngine.isBetterCandidate(scoreB, 1500, 1500,
      scoreA, costBudget, 600));
  }

  @Test
  public void airReachBonusCannotOvercome10PercentCostError() {
    // Property: cost is dominant. A node at the target cost beats a node with
    // 10% cost error even if the off-target node has maximum air-reach.
    int target = 1000;
    double radius = 1000;
    // Off-target node has 11% cost error AND maxed-out airReachBonus.
    double sOff = RoutingEngine.costContourScore(1110, target, 5000, radius); // 0.11 - 0.10 = 0.01
    // On-target with minimum air-reach.
    double sOn  = RoutingEngine.costContourScore(target, target, 100, radius); // -0.01
    assertTrue("cost dominates above the air-reach trade-off threshold", sOn < sOff);
  }

  // ----- 25/50/75 contour selection by cost proximity ------------------------

  @Test
  public void contourCandidatesPickClosestToContourCost() {
    // For the 25% contour with budget=4000, target = 1000. A node at cost 1000
    // beats a farther-in-air node at cost 2000 (50% of budget — too deep).
    int costBudget = 4000;
    int target25 = costBudget / 4; // 1000
    double radius = 1000;

    double sOnContour = RoutingEngine.costContourScore(target25, target25, 200, radius);
    double sDeeperButFarther = RoutingEngine.costContourScore(2000, target25, 700, radius);
    assertTrue("25% contour: on-cost wins over deeper-but-farther",
      sOnContour < sDeeperButFarther);
  }

  @Test
  public void differentContoursPickDifferentBestCandidates() {
    // Three candidates in the same bucket, at costs ≈25%, ≈50%, ≈75% of budget.
    // For the 25 contour, the 25-cost candidate should be best; for the 50
    // contour, the 50-cost; etc.
    int costBudget = 4000;
    double radius = 1000;
    int t25 = costBudget / 4;
    int t50 = costBudget / 2;
    int t75 = (costBudget * 3) / 4;
    int[] candidateCosts = {t25, t50, t75};
    double[] candidateDists = {500, 750, 900}; // higher cost ⇒ slightly farther

    for (int contour = 0; contour < 3; contour++) {
      int target = candidateCosts[contour];
      int bestIdx = -1;
      double bestScore = Double.POSITIVE_INFINITY;
      for (int i = 0; i < 3; i++) {
        double s = RoutingEngine.costContourScore(candidateCosts[i], target,
          candidateDists[i], radius);
        if (s < bestScore) { bestScore = s; bestIdx = i; }
      }
      assertEquals("contour " + (contour * 25 + 25) + "% should pick its own cost candidate",
        contour, bestIdx);
    }
  }

  @Test
  public void contourCandidateAcceptsSlightlyAboveContour() {
    // The new rule (unlike the old cost-cap) allows a candidate slightly above
    // the contour cost to win over one slightly below, if it's closer.
    int target = 1000;
    double radius = 1000;
    // 950 → costError = 0.05, 1020 → costError = 0.02. With equal air-reach,
    // 1020 wins.
    double sBelow = RoutingEngine.costContourScore(950,  target, 500, radius);
    double sAbove = RoutingEngine.costContourScore(1020, target, 500, radius);
    assertTrue("closer-from-above beats farther-from-below",  sAbove < sBelow);
  }

  // ----- frontier road-native coord extraction ------------------------------

  @Test
  public void roadNativeCoordReturnsCoordsForIsoEntry() {
    // 6-element entry has road-native coords at [4], [5].
    double[] entry = {45.0, 2000.0, 2600.0, 5.0, 188_720_123.0, 140_000_456.0};
    int[] coord = RoutingEngine.frontierRoadNativeCoord(entry);
    assertNotNull(coord);
    assertEquals(188_720_123, coord[0]);
    assertEquals(140_000_456, coord[1]);
  }

  @Test
  public void roadNativeCoordReturnsNullForProbeOnlyEntry() {
    // 4-element entry (probe-only injected by mergeIsochroneWithProbe).
    double[] entry = {45.0, 2000.0, 2600.0, 0.0};
    assertNull(RoutingEngine.frontierRoadNativeCoord(entry));
  }

  @Test
  public void roadNativeCoordReturnsNullForShortEntry() {
    double[] entry = {45.0, 2000.0};
    assertNull(RoutingEngine.frontierRoadNativeCoord(entry));
  }

  @Test
  public void roadNativeCoordReturnsNullForNull() {
    assertNull(RoutingEngine.frontierRoadNativeCoord(null));
  }

  // ----- nearest-candidate-by-airDist selection -----------------------------

  /**
   * Build a candidate with the given bucket/airDist; other fields are
   * irrelevant for {@link RoutingEngine#nearestCandidateByAirDist}.
   */
  private static IsoCandidate cand(int bucket, double airDist, int sourceContour) {
    return new IsoCandidate(0, 0, bucket * 10 + 5, airDist,
      (int) (airDist * 1.3), bucket, 5, sourceContour);
  }

  @Test
  public void nearestCandidatePicksClosestAirDist() {
    // Four candidates per bucket (frontier-max + 25/50/75 contours) at
    // increasing air-distances. The target sits between two of them; the closer
    // one wins.
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0,  500, 25),
      cand(0, 1000, 50),
      cand(0, 1500, 75),
      cand(0, 2000, 100));
    IsoCandidate best = RoutingEngine.nearestCandidateByAirDist(bucket, 1100);
    assertNotNull(best);
    assertEquals(50, best.sourceContour); // 1000 is closer to 1100 than 1500
  }

  @Test
  public void nearestCandidateSelectsExactMatch() {
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0, 1000, 50),
      cand(0, 2000, 100));
    IsoCandidate best = RoutingEngine.nearestCandidateByAirDist(bucket, 2000);
    assertNotNull(best);
    assertEquals(100, best.sourceContour);
  }

  @Test
  public void nearestCandidateHandlesEmptyAndNull() {
    assertNull(RoutingEngine.nearestCandidateByAirDist(null, 1000));
    assertNull(RoutingEngine.nearestCandidateByAirDist(new ArrayList<>(), 1000));
  }

  @Test
  public void nearestCandidatePicksFrontierMaxWhenTargetIsLarge() {
    // Target larger than any candidate — pick the farthest (frontier-max).
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0,  500, 25),
      cand(0, 1500, 75),
      cand(0, 2000, 100));
    IsoCandidate best = RoutingEngine.nearestCandidateByAirDist(bucket, 5000);
    assertNotNull(best);
    assertEquals(100, best.sourceContour);
  }
}
