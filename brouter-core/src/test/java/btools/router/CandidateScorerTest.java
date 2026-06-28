package btools.router;

import org.junit.Test;

import static org.junit.Assert.*;

public class CandidateScorerTest {

  private final CandidateScorer scorer = new CandidateScorer();

  @Test
  public void closerCandidateScoresBetter() {
    double closeScore = scorer.score(
      2000, 2000,  // candidate exactly at target
      0, 8000, 10000,  // total=0, return=8000, desired=10000
      90, DirectionPreference.ANY,
      1, 5,
      0.0, 5000, 5000, -1);

    double farScore = scorer.score(
      3500, 2000,  // candidate 75% over target
      0, 8000, 10000,
      90, DirectionPreference.ANY,
      1, 5,
      0.0, 5000, 5000, -1);

    assertTrue("Closer candidate should score lower (better)", closeScore < farScore);
  }

  @Test
  public void directionPenaltyFadesAcrossSteps() {
    double step1Score = scorer.directionScore(180, DirectionPreference.N, 1);
    double step2Score = scorer.directionScore(180, DirectionPreference.N, 2);
    double step3Score = scorer.directionScore(180, DirectionPreference.N, 3);

    // Direction fades from the outbound steps inward. The default
    // strong-direction mode retains a gentle late pull (does not snap to zero)
    // so the loop keeps a coherent heading; the penalty is monotonically
    // decreasing and stays positive while a preference is active.
    assertTrue("Step 1 should have full direction penalty", step1Score > 0);
    assertTrue("Step 2 penalty < step 1", step2Score > 0 && step2Score < step1Score);
    assertTrue("Step 3 penalty faded (< step 2)", step3Score >= 0 && step3Score < step2Score);
  }

  @Test
  public void directionAnyProducesZeroPenalty() {
    double score = scorer.directionScore(45, DirectionPreference.ANY, 1);
    assertEquals(0.0, score, 0.001);
  }

  @Test
  public void alignedDirectionScoresBetter() {
    // Heading north when preference is north
    double alignedScore = scorer.directionScore(0, DirectionPreference.N, 1);
    // Heading south when preference is north
    double oppositeScore = scorer.directionScore(180, DirectionPreference.N, 1);

    assertTrue("Aligned direction should score lower", alignedScore < oppositeScore);
  }

  @Test
  public void visitedEdgePenaltyIncreasesScore() {
    double noReuse = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1);

    double highReuse = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.8, 5000, 5000, -1);

    assertTrue("High edge reuse should increase score (worse)", highReuse > noReuse);
  }

  @Test
  public void spreadPenaltyEarlyPhase() {
    // Early phase (step 1 of 5): staying close to start is bad
    double closeToStart = scorer.spreadPenalty(500, 5000, 1, 5);
    double farFromStart = scorer.spreadPenalty(4500, 5000, 1, 5);

    assertTrue("Close to start in early phase should be penalized more", closeToStart > farFromStart);
  }

  @Test
  public void spreadPenaltyLatePhase() {
    // Late phase (step 4 of 5): being far from start is bad
    double closeToStart = scorer.spreadPenalty(500, 5000, 4, 5);
    double farFromStart = scorer.spreadPenalty(4500, 5000, 4, 5);

    assertTrue("Far from start in late phase should be penalized more", farFromStart > closeToStart);
  }

  @Test
  public void loopFeasibilityPrefersCloserToDesired() {
    // Projected total = 0 + 2000 + 8000 = 10000 (exactly desired)
    double perfect = scorer.loopFeasibilityScore(0, 2000, 8000, 10000);
    // Projected total = 0 + 2000 + 12000 = 14000 (40% over)
    double tooLong = scorer.loopFeasibilityScore(0, 2000, 12000, 10000);

    assertTrue("Perfect loop feasibility should score lower", perfect < tooLong);
    assertEquals("Perfect feasibility should be zero", 0.0, perfect, 0.001);
  }

  @Test
  public void previousDistancePenaltyZeroForFirstStep() {
    // distFromPrevious = -1 means first step (no previous waypoint)
    double penalty = scorer.previousDistancePenalty(-1, 2000);
    assertEquals("First step should have zero penalty", 0.0, penalty, 0.001);
  }

  @Test
  public void previousDistancePenaltyZeroAtTarget() {
    // distFromPrevious exactly equals subRouteTarget
    double penalty = scorer.previousDistancePenalty(2000, 2000);
    assertEquals("Candidate at target distance should have zero penalty", 0.0, penalty, 0.001);
  }

  @Test
  public void previousDistancePenaltyIncreasesWithDeviation() {
    // 50% over target: ((3000-2000)/2000)^2 = 0.25
    double over = scorer.previousDistancePenalty(3000, 2000);
    assertEquals(0.25, over, 0.001);

    // 50% under target: ((1000-2000)/2000)^2 = 0.25
    double under = scorer.previousDistancePenalty(1000, 2000);
    assertEquals(0.25, under, 0.001);

    // Symmetric
    assertEquals("Equal over/under deviation should produce equal penalty", over, under, 0.001);
  }

  @Test
  public void previousDistancePenaltyAffectsScore() {
    // Score with no previous waypoint (first step)
    double firstStepScore = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 2, 5,
      0.0, 5000, 5000, -1);

    // Score with previous waypoint at exactly target distance (ideal)
    double idealPrevScore = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 2, 5,
      0.0, 5000, 5000, 2000);

    // Score with previous waypoint very close (clustering)
    double clusterScore = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 2, 5,
      0.0, 5000, 5000, 200);

    assertTrue("Clustered candidate should score worse than ideal spacing",
      clusterScore > idealPrevScore);
  }

  @Test
  public void distanceScoreSymmetric() {
    double over = scorer.distanceScore(2500, 2000);
    double under = scorer.distanceScore(1500, 2000);
    assertEquals("Equal over/under deviation should score equally", over, under, 0.001);
  }

  @Test
  public void spreadPenaltySmoothTransition() {
    // The blend should be smooth across the transition zone (phase 0.4 → 0.8).
    // No abrupt jumps between consecutive steps.
    double prev = scorer.spreadPenalty(4000, 5000, 1, 5);
    for (int step = 2; step <= 5; step++) {
      double current = scorer.spreadPenalty(4000, 5000, step, 5);
      double jump = Math.abs(current - prev);
      assertTrue("Step " + (step - 1) + "→" + step + " jump=" + jump + " should be < 0.5",
        jump < 0.5);
      prev = current;
    }
  }

  // ----- A: ISO-aware scoring ------------------------------------------------

  @Test
  public void scoreWithoutIsoMetadataMatchesLegacySignature() {
    // The 12-arg back-compat overload must produce exactly the same score as
    // the 14-arg form with NO_ISO_COST/NO_ISO_DENSITY sentinels.
    double legacy = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1);
    double explicit = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      RoundTripCandidateProvider.NO_ISO_COST, RoundTripCandidateProvider.NO_ISO_DENSITY);
    assertEquals("Legacy and explicit-no-iso must match", legacy, explicit, 1e-9);
  }

  @Test
  public void isoValidatedCandidateScoresBetterAtSamePosition() {
    // Two identical candidates at the same air-distance and bearing — one carries
    // iso metadata, the other is from a radial provider. The iso-validated one
    // should win the tie-break (lower score).
    double withoutIso = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      RoundTripCandidateProvider.NO_ISO_COST, RoundTripCandidateProvider.NO_ISO_DENSITY);
    double withIso = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      2600 /* costFromStart, indirectness 2600/5000=0.52 → no hostility */,
      10 /* dense bucket → full validation bonus */);
    assertTrue("iso-validated candidate should score lower (better): "
      + withIso + " vs " + withoutIso, withIso < withoutIso);
  }

  @Test
  public void hostileSectorPenalizedOverEasySector() {
    // Two iso candidates at same air-distance, same bucket density, but
    // different cost — the high-cost (hostile, e.g., switchback mountain
    // direction) should score worse than the low-cost (easy valley road).
    // Hostility scoring is off by default (only meaningful for paved
    // profiles whose cost/airDist baseline is ~1.0); enable explicitly
    // for this test.
    scorer.setHostilityActive(true);
    double easyRoad = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      6500 /* cost/airDist = 6500/5000 = 1.3, very easy */, 10);
    double hostileMountain = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      20000 /* cost/airDist = 20000/5000 = 4.0, very hostile */, 10);
    assertTrue("easy-road candidate beats hostile-mountain at same air-dist: "
      + easyRoad + " vs " + hostileMountain, easyRoad < hostileMountain);
  }

  @Test
  public void hostilityDisabledByDefaultIsAProfileSafetyInvariant() {
    // ISO_GREEDY must not collapse MTB/gravel candidates by applying a
    // fastbike-tuned hostility threshold. The scorer defaults to OFF;
    // the engine only enables it for paved profiles.
    double easyRoad = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1, 6500, 10);
    double hostileMountain = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1, 20000, 10);
    assertEquals("hostility off → identical cost/airDist scores",
      easyRoad, hostileMountain, 1e-9);
  }

  @Test
  public void verySparseBucketLosesValidationBonus() {
    // The bonus plateaus at hits>=3 (we don't reward extra-dense buckets); only
    // very-sparse (hits<3) loses the bonus as a fragility signal. So
    // typical-dense (hits=3) and ultra-dense (hits=20) should score equally,
    // and one-shot (hits=1) should score worse.
    double typicalDense = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      6500, 3);
    double ultraDense = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      6500, 20);
    double oneShot = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      6500, 1);
    assertEquals("hits=3 and hits=20 score equally (no extra density reward)",
      typicalDense, ultraDense, 1e-9);
    assertTrue("one-shot scores worse than typical-dense (fragility): "
      + oneShot + " vs " + typicalDense, oneShot > typicalDense);
  }

  @Test
  public void isoValidatedBonusGatesOnBucketDensityAlone() {
    // The bonus keys on bucket density only. A sentinel costFromStart must NOT
    // zero it: per-step graph-native candidates (production GREEDY) carry real
    // bucketHits with costFromStart = NO_ISO_COST, and the former conjunctive
    // guard disabled the dead-end fragility signal exactly on that path.
    assertTrue("density present, iso cost sentinel → bonus still applies",
      scorer.isoValidatedBonus(RoundTripCandidateProvider.NO_ISO_COST, 10) > 0);
    assertEquals("density missing → no bonus", 0.0, scorer.isoValidatedBonus(
      6500, RoundTripCandidateProvider.NO_ISO_DENSITY), 1e-9);
    assertEquals("both missing → no bonus", 0.0, scorer.isoValidatedBonus(
      RoundTripCandidateProvider.NO_ISO_COST,
      RoundTripCandidateProvider.NO_ISO_DENSITY), 1e-9);
    assertTrue("fully populated → bonus > 0",
      scorer.isoValidatedBonus(6500, 10) > 0);
  }

  @Test
  public void graphNativeOneShotDeadEndRanksWorseThanDenseCandidate() {
    // Graph-native per-step candidates: costFromStart is the NO_ISO_COST
    // sentinel, bucketHits is real. A one-shot bucket (hits=1, the dead-end
    // road-sliver signature) must rank worse than a dense bucket (hits>=3) at
    // otherwise identical geometry — this is the placement-side signal that
    // steers vias away from one-way-out pockets.
    double dense = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      RoundTripCandidateProvider.NO_ISO_COST, 10);
    double oneShotDeadEnd = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      RoundTripCandidateProvider.NO_ISO_COST, 1);
    assertTrue("one-shot dead-end candidate scores worse than dense: "
      + oneShotDeadEnd + " vs " + dense, oneShotDeadEnd > dense);
  }

  @Test
  public void contourDepthMatchesLoopPhase() {
    // Step 1 of 5 wants the deep frontier (contour 100); step 5 wants near-in (25).
    // mismatch values: 0 when contour matches expected phase, 1 at the extreme.
    assertEquals("step 1 + contour 100 → no mismatch", 0.0,
      scorer.isoContourDepthMismatch(100, 1, 5), 1e-9);
    assertEquals("step 5 + contour 25 → no mismatch", 0.0,
      scorer.isoContourDepthMismatch(25, 5, 5), 1e-9);
    assertTrue("step 1 + contour 25 → large mismatch (wants depth)",
      scorer.isoContourDepthMismatch(25, 1, 5) > 0.5);
    assertTrue("step 5 + contour 100 → large mismatch (wants close-in)",
      scorer.isoContourDepthMismatch(100, 5, 5) > 0.5);
    // No sentinel → 0.
    assertEquals(0.0,
      scorer.isoContourDepthMismatch(RoundTripCandidateProvider.NO_ISO_CONTOUR, 1, 5), 1e-9);
  }

  @Test
  public void hostilityPenaltyIsZeroBelowThreshold() {
    // cost/airDist ≤ 1.5 → no penalty (profile-typical roads).
    assertEquals(0.0, scorer.isoHostilityPenalty(7000, 5000), 1e-9); // ind=1.4
    assertEquals(0.0, scorer.isoHostilityPenalty(7500, 5000), 1e-9); // ind=1.5
    assertTrue("ind=2.0 should have a small penalty",
      scorer.isoHostilityPenalty(10000, 5000) > 0);
    assertEquals("ind≥4 should saturate at 1.0",
      1.0, scorer.isoHostilityPenalty(20000, 5000), 1e-9);
    // Missing-iso → no penalty.
    assertEquals(0.0,
      scorer.isoHostilityPenalty(RoundTripCandidateProvider.NO_ISO_COST, 5000), 1e-9);
  }

  // ----- Phase 2 v2 — contiguous-hostile-meters routed-leg term -----------

  @Test
  public void contiguousHostilityPenaltyRamps() {
    // 0 below the safe floor; linear ramp 500→1500m; saturates at the
    // gate's hard cap. The mapping mirrors the gate's enforcement metric
    // so candidate scoring biases toward sub-tracks whose worst stretch
    // stays under MAX_CONTIGUOUS_HOSTILE_METERS.
    assertEquals("0m hostile → 0", 0.0, scorer.contiguousHostilityPenalty(0), 1e-9);
    assertEquals("500m (safe floor) → 0", 0.0, scorer.contiguousHostilityPenalty(500), 1e-9);
    assertEquals("1000m → mid-ramp 0.5", 0.5, scorer.contiguousHostilityPenalty(1000), 1e-9);
    assertEquals("1500m (gate cap) → saturated 1.0",
      1.0, scorer.contiguousHostilityPenalty(1500), 1e-9);
    assertEquals("3000m (over cap) → still 1.0",
      1.0, scorer.contiguousHostilityPenalty(3000), 1e-9);
    assertEquals("-1 sentinel (no data) → 0",
      0.0, scorer.contiguousHostilityPenalty(-1), 1e-9);
  }

  @Test
  public void routedHostilitySignalReplacesIsoHostility() {
    // The Phase 2 v2 overload takes a routed-leg worst-contiguous meters.
    // When non-negative, it REPLACES the iso-hostility penalty: the route
    // is now known, so the iso estimate is obsolete.
    scorer.setHostilityActive(true);
    // Two iso candidates with the same iso indirectness (so iso term would
    // tie). Candidate A's routed leg has 200m worst-contiguous (clean);
    // candidate B's has 1400m (almost-at-cap). A should win.
    double cleanLeg = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      10000, 10, RoundTripCandidateProvider.NO_ISO_CONTOUR,
      /*routedLegWorstContiguousHostileMeters*/ 200);
    double nearCapLeg = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      10000, 10, RoundTripCandidateProvider.NO_ISO_CONTOUR,
      /*routedLegWorstContiguousHostileMeters*/ 1400);
    assertTrue("clean routed leg beats near-cap routed leg: "
      + cleanLeg + " vs " + nearCapLeg, cleanLeg < nearCapLeg);
  }

  @Test
  public void minusOneRoutedSignalFallsBackToIso() {
    // -1 sentinel = "no routed data" → scorer must keep using the iso
    // term so pre-Phase-2 callers and non-paved profiles see no behaviour
    // change.
    scorer.setHostilityActive(true);
    double routedSentinel = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      10000, 10, RoundTripCandidateProvider.NO_ISO_CONTOUR, -1);
    double legacy16Arg = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1,
      10000, 10, RoundTripCandidateProvider.NO_ISO_CONTOUR);
    assertEquals("sentinel == legacy 16-arg form",
      legacy16Arg, routedSentinel, 1e-9);
  }
}
