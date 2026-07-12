package btools.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Acceptance tests for the AUTO candidate-competition flow described in
 * docs/features/roundtrip-auto-quality-redesign.md §353. Covers all 14
 * listed test cases — some are pure-logic on {@link RouteChoiceScore}, others
 * require real routing (segments-gated, skipped if data absent).
 *
 * <p>The flow under test: for generated AUTO loops with no user vias, the
 * engine runs ISO_GREEDY first, compares GREEDY when ISO_GREEDY is weak,
 * and falls back to legacy WAYPOINT/probe only when greedy variants fail.
 * Accepted candidates are scored and the highest-scoring child track is
 * adopted directly.
 */
public class RoutingEngineAutoCompetitionTest {

  // =========================================================================
  // §353.8 — Route-choice score returns a reason breakdown.
  // §353.7 — Direction weak: cannot dominate other factors.
  // §353.12 — iso/non-iso telemetry fields available on RoundTripResult.
  // §353.13 — Low-iso classification uses accepted legs, not routed.
  // §353.14 — Existing forced algorithm tests still work (covered by other suites).
  // These are pure-logic tests that run unconditionally.
  // =========================================================================

  @Test
  public void routeChoiceScoreReturnsReasonBreakdown() {
    OsmTrack t = cleanSquareLoop(5000);
    RoundTripQualityResult gateVerdict = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.STRICT_LOOP).build();
    RouteChoiceScore.Verdict v = RouteChoiceScore.score(t, t.distance, "fastbike", gateVerdict);

    Assert.assertTrue("score in [0,1]", v.score() >= 0 && v.score() <= 1);
    Assert.assertFalse("reasons non-empty", v.reasons().isEmpty());
    // All component categories present (distance, reuse, closure, continuity,
    // compactness, cost/m, direction).
    Assert.assertTrue("has distance reason",
      v.reasons().stream().anyMatch(r -> r.label.contains("distance ratio")));
    Assert.assertTrue("has reuse reason",
      v.reasons().stream().anyMatch(r -> r.label.contains("road reuse")));
    Assert.assertTrue("has closure reason",
      v.reasons().stream().anyMatch(r -> r.label.contains("closure")));
    Assert.assertTrue("has cost/m reason",
      v.reasons().stream().anyMatch(r -> r.label.contains("cost/m")));
    // describe() produces multi-line output
    String desc = v.describe();
    Assert.assertTrue("describe has score line", desc.contains("score="));
    Assert.assertTrue("describe has reasons section", desc.contains("reasons:"));
  }

  @Test
  public void directionDeltaCannotDominateScore() {
    // §353.7. Direction may shift a candidate's score by at most W_DIRECTION
    // (5%), never more — it must not dominate distance/reuse/closure/etc.
    OsmTrack good = cleanSquareLoop(5000);
    RoundTripQualityResult gate = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.STRICT_LOOP).build();

    // (a) The direction reason's contribution is bounded by a hard literal
    //     (0.05). Asserting against RouteChoiceScore.W_DIRECTION itself would be
    //     tautological — dirContrib == W_DIRECTION * dirScore with dirScore in
    //     [0,1], so it always holds and a W_DIRECTION regression would slip past.
    double dirContrib = RouteChoiceScore.score(good, good.distance, "fastbike", gate, 0)
      .reasons().stream().filter(r -> r.label.startsWith("direction delta"))
      .findFirst().get().scoreContribution;
    Assert.assertTrue("direction contribution must stay <= 0.05; got " + dirContrib,
      Math.abs(dirContrib) <= 0.05 + 1e-9);

    // (b) Dominance: scoring the SAME track across every requested direction
    //     varies only the direction delta, so the total score may move by at
    //     most 0.05 between the best- and worst-aligned direction.
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (int dir = 0; dir < 360; dir += 30) {
      double s = RouteChoiceScore.score(good, good.distance, "fastbike", gate, dir).score();
      min = Math.min(min, s);
      max = Math.max(max, s);
    }
    Assert.assertTrue("direction must actually affect the score (otherwise the bound is vacuous)",
      max - min > 0);
    Assert.assertTrue("direction may swing the total score by at most 0.05; swing was " + (max - min),
      max - min <= 0.05 + 1e-9);
  }

  @Test
  public void rejectedGateMeansZeroScore() {
    // §353.9. A candidate rejected by the hard gate cannot win, regardless
    // of what the soft score would compute. We test this by handing
    // RouteChoiceScore a rejected verdict — it returns 0.
    OsmTrack t = cleanSquareLoop(5000);
    RoundTripQualityResult rejected = RoundTripQualityResult.builder()
      .shape(RouteShape.INVALID_RETRACE)
      .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL, "synthetic rejection").build();
    RouteChoiceScore.Verdict v = RouteChoiceScore.score(t, t.distance, "fastbike", rejected);
    Assert.assertEquals("rejected gate → zero score", 0.0, v.score(), 1e-9);
  }

  @Test
  public void shapeLollipopAndScenicGetPenalty() {
    OsmTrack t = cleanSquareLoop(5000);
    RoundTripQualityResult strict = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.STRICT_LOOP).build();
    RoundTripQualityResult lollipop = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.LOLLIPOP).build();
    RoundTripQualityResult scenic = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.OUT_AND_BACK).build();

    double strictS = RouteChoiceScore.score(t, t.distance, "fastbike", strict).score();
    double lollipopS = RouteChoiceScore.score(t, t.distance, "fastbike", lollipop).score();
    double scenicS = RouteChoiceScore.score(t, t.distance, "fastbike", scenic).score();
    Assert.assertTrue("STRICT_LOOP > LOLLIPOP", strictS > lollipopS);
    Assert.assertTrue("LOLLIPOP > OUT_AND_BACK", lollipopS > scenicS);
  }

  @Test
  public void childCandidateBudgetSharesTheDeadline() {
    // The AUTO competition runs candidates sequentially against one shared
    // deadline; each child gets the REMAINING time, floored so a spawned
    // candidate still gets a usable slice (never the full request timeout).
    long now = 1_000_000L;
    Assert.assertEquals("ample time remaining → full remainder",
      50_000L, RoutingEngine.childCandidateBudgetMs(now + 50_000L, now));
    Assert.assertEquals("remaining below the 5s floor → floored",
      5_000L, RoutingEngine.childCandidateBudgetMs(now + 3_000L, now));
    Assert.assertEquals("deadline already passed → floored, never negative",
      5_000L, RoutingEngine.childCandidateBudgetMs(now - 10_000L, now));
  }

  @Test
  public void roundTripResultExposesIsoNonIsoTelemetry() {
    // §353.12. The RoundTripResult model carries iso/non-iso routed +
    // accepted counters; default 0.
    RoundTripResult r = new RoundTripResult();
    Assert.assertEquals(0, r.getRoutedIsoCandidates());
    Assert.assertEquals(0, r.getRoutedNonIsoCandidates());
    Assert.assertEquals(0, r.getAcceptedIsoLegs());
    Assert.assertEquals(0, r.getAcceptedNonIsoLegs());

    r.setRoutedIsoCandidates(12);
    r.setRoutedNonIsoCandidates(8);
    r.setAcceptedIsoLegs(3);
    r.setAcceptedNonIsoLegs(2);
    Assert.assertEquals(12, r.getRoutedIsoCandidates());
    Assert.assertEquals(8, r.getRoutedNonIsoCandidates());
    Assert.assertEquals(3, r.getAcceptedIsoLegs());
    Assert.assertEquals(2, r.getAcceptedNonIsoLegs());
  }

  @Test
  public void candidateResultModelTracksAlgorithmAndAcceptance() {
    // The internal RoundTripCandidateResult wrapper aggregates the per-
    // candidate fields and exposes accepted() / scoreValue() helpers used
    // by the competition loop.
    RoundTripCandidateResult r = new RoundTripCandidateResult(RoundTripAlgorithm.ISO_GREEDY);
    Assert.assertEquals(RoundTripAlgorithm.ISO_GREEDY, r.algorithm);
    Assert.assertFalse("no track + no gate → not accepted", r.accepted());
    Assert.assertEquals("scoreValue 0 when no score", 0.0, r.scoreValue(), 1e-9);

    r.track = cleanSquareLoop(5000);
    r.gateVerdict = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.STRICT_LOOP).build();
    r.score = RouteChoiceScore.score(r.track, r.track.distance, "fastbike", r.gateVerdict);
    Assert.assertTrue("accepted now", r.accepted());
    Assert.assertTrue("scoreValue > 0", r.scoreValue() > 0);
  }

  // ---- best-effort (lenient) candidate selection — Option C ----------------
  // When no candidate passes the gate, AUTO adopts the least-bad QUALITY-tier
  // best-effort loop, ranked by the same multi-factor RouteChoiceScore used for
  // accepted winners. These pin that both axes the rider cares about drive the
  // choice: distance closeness (primary) and profile-surface ("ground") match.

  @Test
  public void bestEffortSelectionPrefersCloserDistance() {
    // Two profile-friendly loops differing only in distance. The on-target one
    // must win even though the off-target one is earlier in algorithm order.
    OsmTrack onTarget = loopWithCostPerMeter(5000, 1.0);
    OsmTrack tooShort = loopWithCostPerMeter(2000, 1.0); // ~40% of the distance
    List<RoundTripCandidateResult> candidates = Arrays.asList(
      candidate(RoundTripAlgorithm.ISO_GREEDY, tooShort),  // earlier in order…
      candidate(RoundTripAlgorithm.GREEDY, onTarget));     // …but closer to target
    RoundTripCandidateResult best = RoutingEngine.selectBestEffortCandidate(
      candidates, onTarget.distance, "fastbike", 0);
    Assert.assertSame("closer-distance best-effort wins despite later order",
      onTarget, best.track);
  }

  @Test
  public void bestEffortSelectionPrefersProfileFriendlySurface() {
    // Two loops at the same (on-target) distance, differing only in cost/m: one
    // on roads the profile likes, one profile-hostile. Ground match must decide.
    OsmTrack friendly = loopWithCostPerMeter(5000, 1.0); // within fastbike band
    OsmTrack hostile = loopWithCostPerMeter(5000, 8.0);  // well above the band
    List<RoundTripCandidateResult> candidates = Arrays.asList(
      candidate(RoundTripAlgorithm.ISO_GREEDY, hostile),   // earlier in order…
      candidate(RoundTripAlgorithm.GREEDY, friendly));     // …but rideable surface
    RoundTripCandidateResult best = RoutingEngine.selectBestEffortCandidate(
      candidates, friendly.distance, "fastbike", 0);
    Assert.assertSame("profile-friendly surface best-effort wins despite later order",
      friendly, best.track);
  }

  @Test
  public void bestEffortSelectionPicksHighestCompositeAcrossAxes() {
    // Cross-axis: an off-distance friendly loop vs an on-distance hostile loop.
    // The winner is whichever the multi-factor score ranks higher overall — assert
    // the selection matches the directly-computed argmax (no hand-picked axis).
    OsmTrack offDistanceFriendly = loopWithCostPerMeter(2000, 1.0);
    OsmTrack onDistanceHostile = loopWithCostPerMeter(5000, 8.0);
    double expected = onDistanceHostile.distance;
    double sFriendly = RouteChoiceScore.score(offDistanceFriendly, expected, "fastbike", null, 0).score();
    double sHostile = RouteChoiceScore.score(onDistanceHostile, expected, "fastbike", null, 0).score();
    OsmTrack expectedWinner = sHostile > sFriendly ? onDistanceHostile : offDistanceFriendly;
    List<RoundTripCandidateResult> candidates = Arrays.asList(
      candidate(RoundTripAlgorithm.ISO_GREEDY, offDistanceFriendly),
      candidate(RoundTripAlgorithm.GREEDY, onDistanceHostile));
    RoundTripCandidateResult best = RoutingEngine.selectBestEffortCandidate(
      candidates, expected, "fastbike", 0);
    Assert.assertSame("selection picks the highest-composite candidate",
      expectedWinner, best.track);
  }

  @Test
  public void bestEffortSelectionHandlesEmptyAndNullTracks() {
    Assert.assertNull("no candidates → null",
      RoutingEngine.selectBestEffortCandidate(Collections.emptyList(), 10000, "fastbike", 0));
    RoundTripCandidateResult noTrack = new RoundTripCandidateResult(RoundTripAlgorithm.WAYPOINT);
    Assert.assertNull("only null-track candidates → null",
      RoutingEngine.selectBestEffortCandidate(
        Collections.singletonList(noTrack), 10000, "fastbike", 0));
  }

  @Test
  public void scoreBestEffortBypassesZeroGuardButKeepsShapePenalty() {
    OsmTrack t = loopWithCostPerMeter(5000, 1.0);
    RoundTripQualityResult rejectedCorridor = RoundTripQualityResult.builder()
      .reject(RoundTripQualityResult.RejectionTier.QUALITY, "same-way-back corridor")
      .shape(RouteShape.OUT_AND_BACK).build();
    // Strict scorer: a rejected gate still zeroes (production ranking unchanged).
    Assert.assertEquals("strict score() keeps the rejected-gate zero guard",
      0.0, RouteChoiceScore.score(t, t.distance, "fastbike", rejectedCorridor, 0).score(), 1e-9);
    // Best-effort scorer: real geometry score minus the shape disclosure penalty.
    double baseline = RouteChoiceScore.score(t, t.distance, "fastbike", null, 0).score();
    Assert.assertTrue("test geometry must not clamp at 1.0", baseline < 1.0);
    RouteChoiceScore.Verdict v = RouteChoiceScore.scoreBestEffort(
      t, t.distance, "fastbike", rejectedCorridor, 0);
    Assert.assertTrue("zero guard bypassed for best-effort ranking", v.score() > 0);
    Assert.assertEquals("gate shape penalty applied on top of the geometry score",
      baseline - RouteChoiceScore.SHAPE_PENALTY_OUT_AND_BACK, v.score(), 1e-9);
  }

  @Test
  public void bestEffortSelectionRanksRejectedCorridorBelowRejectedStrictLoop() {
    // Identical geometry; only the gate's shape verdict differs. The disclosed
    // corridor (OUT_AND_BACK) must lose to a strict-loop-shaped candidate
    // rejected for a non-shape reason. Before scoreBestEffort consumed the
    // gate verdict, both ranked identically and list order decided.
    OsmTrack t = loopWithCostPerMeter(5000, 1.0);
    RoundTripCandidateResult corridor = candidate(RoundTripAlgorithm.ISO_GREEDY, t);
    corridor.gateVerdict = RoundTripQualityResult.builder()
      .reject(RoundTripQualityResult.RejectionTier.QUALITY, "same-way-back corridor")
      .shape(RouteShape.OUT_AND_BACK).build();
    RoundTripCandidateResult strictOffTarget = candidate(RoundTripAlgorithm.GREEDY, t);
    strictOffTarget.gateVerdict = RoundTripQualityResult.builder()
      .reject(RoundTripQualityResult.RejectionTier.QUALITY, "distance off target")
      .shape(RouteShape.STRICT_LOOP).build();
    RoundTripCandidateResult best = RoutingEngine.selectBestEffortCandidate(
      Arrays.asList(corridor, strictOffTarget), t.distance, "fastbike", 0);
    Assert.assertSame("strict-shaped candidate must outrank the disclosed corridor",
      strictOffTarget, best);
  }

  // ------------- helpers -----------------------------------------------------

  private static RoundTripCandidateResult candidate(RoundTripAlgorithm algo, OsmTrack track) {
    RoundTripCandidateResult r = new RoundTripCandidateResult(algo);
    r.track = track;
    return r;
  }

  /**
   * A clean rectangular loop with a chosen average cost-per-meter. cleanSquareLoop
   * leaves track.cost at 0 (cost/m 0 → always in-band); set it explicitly so the
   * RouteChoiceScore cost/m component (= track.cost / track.distance) is exercised.
   */
  private static OsmTrack loopWithCostPerMeter(int sideMeters, double costPerMeter) {
    OsmTrack t = cleanSquareLoop(sideMeters);
    t.cost = (int) Math.round(costPerMeter * t.distance);
    return t;
  }

  /** A clean rectangular loop with proper paved metadata. */
  private static OsmTrack cleanSquareLoop(int sideMeters) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    int base_ilon = 180_000_000;
    int base_ilat = 50_000_000;
    int s = sideMeters;
    addNode(t, base_ilon + 0,           base_ilat + 0);
    addNode(t, base_ilon + s * 14,      base_ilat + 0);
    addNode(t, base_ilon + s * 14,      base_ilat + s * 9);
    addNode(t, base_ilon + 0,           base_ilat + s * 9);
    addNode(t, base_ilon + 0,           base_ilat + 0);
    int d = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      d += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
      MessageData m = new MessageData();
      m.wayKeyValues = "highway=residential surface=asphalt";
      m.costfactor = 1.0f;
      t.nodes.get(i).message = m;
    }
    t.distance = d;
    return t;
  }

  private static void addNode(OsmTrack t, int ilon, int ilat) {
    t.nodes.add(OsmPathElement.create(ilon, ilat, (short) 0, null));
  }

  // ---- RouteChoiceScore.costMBand profile dispatch ------------------------
  // The existing score() tests all use "fastbike"; this pins the per-profile
  // cost-band table (a substring typo here would silently mis-band a profile).

  @Test
  public void costMBand_dispatchesByProfileFamily() {
    Assert.assertArrayEquals(new double[]{1.2, 3.0}, RouteChoiceScore.costMBand("fastbike"), 1e-9);
    Assert.assertArrayEquals(new double[]{1.2, 3.0}, RouteChoiceScore.costMBand("road"), 1e-9);
    Assert.assertArrayEquals(new double[]{1.2, 3.0}, RouteChoiceScore.costMBand("racing"), 1e-9);
    Assert.assertArrayEquals(new double[]{2.0, 5.0}, RouteChoiceScore.costMBand("gravel"), 1e-9);
    Assert.assertArrayEquals(new double[]{4.0, 9.0}, RouteChoiceScore.costMBand("mtb"), 1e-9);
    Assert.assertArrayEquals(new double[]{1.5, 4.0}, RouteChoiceScore.costMBand("trekking"), 1e-9);
    Assert.assertArrayEquals("unknown profile → default band",
      new double[]{1.5, 4.0}, RouteChoiceScore.costMBand("shortest"), 1e-9);
    Assert.assertArrayEquals("null profile → default band",
      new double[]{1.5, 4.0}, RouteChoiceScore.costMBand(null), 1e-9);
  }

  @Test
  public void costMBand_isCaseInsensitive() {
    Assert.assertArrayEquals(RouteChoiceScore.costMBand("fastbike"),
      RouteChoiceScore.costMBand("FastBike"), 1e-9);
    Assert.assertArrayEquals(RouteChoiceScore.costMBand("gravel"),
      RouteChoiceScore.costMBand("GRAVEL"), 1e-9);
  }
}
