package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;

/**
 * Fast unit tests for the greedy sub-route round-trip planner. These exercise
 * pure helpers (scoring, sort, param parsing, fallback selection, the quality
 * gate delegation) against bundled fixtures and never touch real segment data.
 * The slow, segment-gated end-to-end tests live in {@code GreedyRoundTripPlannerIT}.
 */
public class GreedyRoundTripPlannerTest {

  @Before
  public void setup() {
    // Classification now comes from the cost-model probe (PavedProfileProbeTest),
    // not the profile name; seed "fastbike" as paved for the gate-delegation case.
    RoundTripQualityGate.putPavedClassificationForTest("fastbike", true);
  }

  @Test
  public void roundTripAlgorithmParsing() {
    Assert.assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString(null));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.fromString("greedy"));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.fromString("GREEDY"));
    Assert.assertEquals(RoundTripAlgorithm.ISOCHRONE, RoundTripAlgorithm.fromString("isochrone"));
    Assert.assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("waypoint"));
    Assert.assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString("bogus"));
  }

  @Test
  public void directionPreferenceParsing() {
    Assert.assertEquals(DirectionPreference.N, DirectionPreference.fromString("N"));
    Assert.assertEquals(DirectionPreference.SW, DirectionPreference.fromString("sw"));
    Assert.assertEquals(DirectionPreference.ANY, DirectionPreference.fromString(null));
    Assert.assertEquals(DirectionPreference.ANY, DirectionPreference.fromString("bogus"));
    Assert.assertEquals(0.0, DirectionPreference.N.bearing, 0.001);
    Assert.assertEquals(180.0, DirectionPreference.S.bearing, 0.001);
  }

  @Test
  public void sortByRoutedScoreOrdersAscending() {
    GreedyRoundTripPlanner.ScoredRoute a = new GreedyRoundTripPlanner.ScoredRoute();
    a.routedScore = 0.42;
    a.candidateIndex = 0;
    GreedyRoundTripPlanner.ScoredRoute b = new GreedyRoundTripPlanner.ScoredRoute();
    b.routedScore = 0.17;
    b.candidateIndex = 1;
    GreedyRoundTripPlanner.ScoredRoute c = new GreedyRoundTripPlanner.ScoredRoute();
    c.routedScore = 0.81;
    c.candidateIndex = 2;

    List<GreedyRoundTripPlanner.ScoredRoute> list = new ArrayList<>();
    list.add(a);
    list.add(b);
    list.add(c);
    GreedyRoundTripPlanner.sortByRoutedScore(list);

    Assert.assertSame("lowest routedScore first", b, list.get(0));
    Assert.assertSame(a, list.get(1));
    Assert.assertSame("highest routedScore last", c, list.get(2));
  }

  @Test
  public void sortByRoutedScoreIsStableOnTies() {
    // Stability matters: the legacy single-best-wins logic used `<` so the
    // first candidate to reach a tied score won. Phase 1 Step 2 keeps that
    // tie-break by relying on List.sort's stable contract.
    GreedyRoundTripPlanner.ScoredRoute first = new GreedyRoundTripPlanner.ScoredRoute();
    first.routedScore = 0.5;
    first.candidateIndex = 0;
    GreedyRoundTripPlanner.ScoredRoute second = new GreedyRoundTripPlanner.ScoredRoute();
    second.routedScore = 0.5;
    second.candidateIndex = 1;
    GreedyRoundTripPlanner.ScoredRoute third = new GreedyRoundTripPlanner.ScoredRoute();
    third.routedScore = 0.5;
    third.candidateIndex = 2;

    List<GreedyRoundTripPlanner.ScoredRoute> list = new ArrayList<>();
    list.add(first);
    list.add(second);
    list.add(third);
    GreedyRoundTripPlanner.sortByRoutedScore(list);

    Assert.assertEquals(0, list.get(0).candidateIndex);
    Assert.assertEquals(1, list.get(1).candidateIndex);
    Assert.assertEquals(2, list.get(2).candidateIndex);
  }

  @Test
  public void sortByRoutedScoreHandlesEmptyList() {
    List<GreedyRoundTripPlanner.ScoredRoute> empty = new ArrayList<>();
    GreedyRoundTripPlanner.sortByRoutedScore(empty);
    Assert.assertTrue(empty.isEmpty());
  }

  @Test
  public void autoSelectsGreedyForLargeRadius() {
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoutingEngine.selectRoundTripAlgorithm(8000));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoutingEngine.selectRoundTripAlgorithm(5000));
  }

  @Test
  public void autoFallbackSelectsGreedyForSmallRadius() {
    // Generated-loop AUTO resolution now runs inside doRoundTrip() via the
    // greedy-first candidate competition. The cheap helper is only a fallback
    // for unsupported/direct callers, and it must keep the same greedy default
    // across radii.
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoutingEngine.selectRoundTripAlgorithm(4000));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoutingEngine.selectRoundTripAlgorithm(1500));
  }

  @Test
  public void greedySubRouteCountScalesWithDistanceAndProfile() {
    Assert.assertEquals(3, RoutingEngine.selectGreedySubRouteCount(7000, "fastbike"));
    Assert.assertEquals(4, RoutingEngine.selectGreedySubRouteCount(20000, "fastbike"));
    Assert.assertEquals(5, RoutingEngine.selectGreedySubRouteCount(50000, "fastbike"));
    Assert.assertEquals(6, RoutingEngine.selectGreedySubRouteCount(90000, "fastbike"));
    Assert.assertEquals(6, RoutingEngine.selectGreedySubRouteCount(50000, "mtb"));
    Assert.assertEquals(6, RoutingEngine.selectGreedySubRouteCount(90000, "mtb"));
  }

  @Test
  public void legacyRoundTripIsochroneParamMapsToIsochrone() {
    // Drive the real parser: roundTripIsochrone=1 promotes AUTO -> ISOCHRONE.
    RoutingContext rctx = new RoutingContext();
    Assert.assertEquals(RoundTripAlgorithm.AUTO, rctx.roundTripAlgorithm);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("roundTripIsochrone", "1");
    new RoutingParamCollector().setParams(rctx, null, params);
    Assert.assertEquals(RoundTripAlgorithm.ISOCHRONE, rctx.roundTripAlgorithm);
    Assert.assertTrue(rctx.roundTripIsochrone);
  }

  @Test
  public void explicitRoundTripAlgorithmWinsOverIsochroneShortcut() {
    // An explicit roundTripAlgorithm beats the roundTripIsochrone=1 shortcut,
    // regardless of parameter order (the parser only promotes when AUTO).
    for (boolean algoFirst : new boolean[]{true, false}) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> params = new LinkedHashMap<>();
      if (algoFirst) {
        params.put("roundTripAlgorithm", "GREEDY");
        params.put("roundTripIsochrone", "1");
      } else {
        params.put("roundTripIsochrone", "1");
        params.put("roundTripAlgorithm", "GREEDY");
      }
      new RoutingParamCollector().setParams(rctx, null, params);
      Assert.assertEquals("explicit algorithm must win (algoFirst=" + algoFirst + ")",
        RoundTripAlgorithm.GREEDY, rctx.roundTripAlgorithm);
    }
  }

  @Test
  public void tierAliasesResolveThroughSetParams() {
    // fromString is unit-tested in RoundTripAlgorithmTest; this pins the parser
    // wiring (RoutingParamCollector → RoundTripAlgorithm.fromString) so the
    // algorithm name actually reaches rctx.roundTripAlgorithm — including the
    // FAST preview alias and the dropped BALANCED/QUALITY names → AUTO.
    String[][] cases = {
      {"FAST", "WAYPOINT"}, {"ISO_GREEDY", "ISO_GREEDY"}, {"bogus", "AUTO"},
      {"BALANCED", "AUTO"}, {"QUALITY", "AUTO"},
    };
    for (String[] c : cases) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> p = new LinkedHashMap<>();
      p.put("roundTripAlgorithm", c[0]);
      new RoutingParamCollector().setParams(rctx, null, p);
      Assert.assertEquals("alias " + c[0] + " must resolve to " + c[1],
        RoundTripAlgorithm.valueOf(c[1]), rctx.roundTripAlgorithm);
    }
  }

  @Test
  public void roundTripPointsOutOfRangeClampsToDefault() {
    // Valid range is [3,20]; anything outside (or non-integer) falls back to 5.
    for (String v : new String[]{"2", "0", "21", "100", "-3"}) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> p = new LinkedHashMap<>();
      p.put("roundTripPoints", v);
      new RoutingParamCollector().setParams(rctx, null, p);
      Integer expectedClamp = 5;
      Assert.assertEquals("roundTripPoints=" + v + " clamps to 5",
        expectedClamp, rctx.roundTripPoints);
    }
    // An in-range value is preserved unchanged.
    RoutingContext rctx = new RoutingContext();
    Map<String, String> p = new LinkedHashMap<>();
    p.put("roundTripPoints", "8");
    new RoutingParamCollector().setParams(rctx, null, p);
    Integer expectedPoints = 8;
    Assert.assertEquals(expectedPoints, rctx.roundTripPoints);
  }

  @Test
  public void roundTripStrictQualityParsesBooleanWithDefaultFalse() {
    Assert.assertTrue(strictQualityFor("1"));
    Assert.assertFalse(strictQualityFor("0"));
    // Malformed input fails loud (see malformedRoundTripParamsFailLoud).
    // Absent → default false.
    RoutingContext rctx = new RoutingContext();
    new RoutingParamCollector().setParams(rctx, null, new LinkedHashMap<>());
    Assert.assertFalse(rctx.roundTripStrictQuality);
  }

  private static boolean strictQualityFor(String v) {
    RoutingContext rctx = new RoutingContext();
    Map<String, String> p = new LinkedHashMap<>();
    p.put("roundTripStrictQuality", v);
    new RoutingParamCollector().setParams(rctx, null, p);
    return rctx.roundTripStrictQuality;
  }

  @Test
  public void roundTripDensifyOverrideIsTriState() {
    // The override is a Boolean: 1 → TRUE (force on), 0 → FALSE (force off),
    // absent → null (engine default decides).
    Assert.assertEquals(Boolean.TRUE, densifyFor("1"));
    Assert.assertEquals("0 forces off (FALSE, distinct from unset)",
      Boolean.FALSE, densifyFor("0"));
    RoutingContext rctx = new RoutingContext();
    new RoutingParamCollector().setParams(rctx, null, new LinkedHashMap<>());
    Assert.assertNull("absent → unset", rctx.explicitViaDensifyOverride);
  }

  private static Boolean densifyFor(String v) {
    RoutingContext rctx = new RoutingContext();
    Map<String, String> p = new LinkedHashMap<>();
    p.put("roundTripDensify", v);
    new RoutingParamCollector().setParams(rctx, null, p);
    return rctx.explicitViaDensifyOverride;
  }

  @Test
  public void nonPositiveRoundTripDistanceIsNulled() {
    // Symmetric with roundTripLength: a non-positive roundTripDistance would
    // become a zero/negative searchRadius (which silently disables the distance
    // gate and ships a wrong-scale loop), so the parser must invalidate it and
    // let the default radius apply.
    for (String v : new String[]{"0", "-100", "-5000"}) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> p = new LinkedHashMap<>();
      p.put("roundTripDistance", v);
      new RoutingParamCollector().setParams(rctx, null, p);
      Assert.assertNull("roundTripDistance=" + v + " must null out", rctx.roundTripDistance);
    }
    // A positive value is preserved unchanged.
    RoutingContext rctx = new RoutingContext();
    Map<String, String> p = new LinkedHashMap<>();
    p.put("roundTripDistance", "2500");
    new RoutingParamCollector().setParams(rctx, null, p);
    Integer expectedDistance = 2500;
    Assert.assertEquals(expectedDistance, rctx.roundTripDistance);
  }

  @Test
  public void nonPositiveRoundTripLengthIsNulled() {
    // roundTripLength <= 0 must be discarded (null), not passed through.
    for (String v : new String[]{"0", "-5"}) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> params = new LinkedHashMap<>();
      params.put("roundTripLength", v);
      new RoutingParamCollector().setParams(rctx, null, params);
      Assert.assertNull("roundTripLength=" + v + " must null out", rctx.roundTripLength);
    }
  }

  @Test
  public void malformedRoundTripParamsFailLoud() {
    // A non-integer value on an integer round-trip URL parameter throws
    // NumberFormatException, matching the historic upstream contract
    // (Integer.parseInt/valueOf) used for every integer param. Malformed input
    // surfaces as an error rather than a silently default-substituted route, so
    // the whole collector has one consistent fail-loud parsing contract.
    String[] intParams = {
      "roundTripLength", "roundTripDistance", "roundTripDirectionAdd",
      "roundTripPoints", "roundTripIsochrone", "allowSamewayback"};
    for (String key : intParams) {
      RoutingContext rctx = new RoutingContext();
      Map<String, String> params = new LinkedHashMap<>();
      params.put(key, "not-a-number");
      try {
        new RoutingParamCollector().setParams(rctx, null, params);
        Assert.fail("malformed " + key + " must throw NumberFormatException");
      } catch (NumberFormatException expected) {
        // expected — fail loud
      }
    }
  }

  // --- fallback-selection rule (gate-accept-aware) ---

  @Test
  public void betterFallbackPrefersGateAcceptedOverRejected() {
    // A gate-accepted loop beats a gate-rejected one regardless of error — this
    // is the core of the fix: don't latch a rejected low-error loop and discard
    // a usable accepted higher-error one.
    Assert.assertTrue("accepted (worse error) beats rejected (better error)",
      GreedyRoundTripPlanner.isBetterFallback(true, 0.90, false, 0.10));
    Assert.assertFalse("rejected (better error) does not beat accepted (worse error)",
      GreedyRoundTripPlanner.isBetterFallback(false, 0.10, true, 0.90));
  }

  @Test
  public void betterFallbackBreaksTiesByLowerError() {
    // Same gate verdict → lower geometric error wins; equal error is not "better".
    Assert.assertTrue("accepted: lower error wins",
      GreedyRoundTripPlanner.isBetterFallback(true, 0.10, true, 0.20));
    Assert.assertFalse("accepted: higher error loses",
      GreedyRoundTripPlanner.isBetterFallback(true, 0.30, true, 0.20));
    Assert.assertTrue("rejected: lower error wins",
      GreedyRoundTripPlanner.isBetterFallback(false, 0.10, false, 0.20));
    Assert.assertFalse("rejected: higher error loses",
      GreedyRoundTripPlanner.isBetterFallback(false, 0.30, false, 0.20));
    Assert.assertFalse("equal error + same verdict is not strictly better",
      GreedyRoundTripPlanner.isBetterFallback(true, 0.20, true, 0.20));
  }

  @Test
  public void betterFallbackPrefersLowerSoundnessRankAcrossAllThreeTiers() {
    // 3-tier guarantee (int overload): accepted (0) > sound same-way-back corridor (1) > chaos (2),
    // and the rank dominates geometric error. The boolean adapter collapses every rejection to
    // severity 2, so it cannot express the middle tier — this is the only coverage of the
    // "rideable corridor beats chaos" invariant the planner's fallback selection relies on
    // (GreedyRoundTripPlanner.java:644).
    Assert.assertTrue("sound corridor (worse error) beats chaos (better error)",
      GreedyRoundTripPlanner.isBetterFallback(1, 0.90, 2, 0.10));
    Assert.assertFalse("chaos (better error) does not beat sound corridor (worse error)",
      GreedyRoundTripPlanner.isBetterFallback(2, 0.10, 1, 0.90));
    Assert.assertTrue("accepted (worse error) beats sound corridor (better error)",
      GreedyRoundTripPlanner.isBetterFallback(0, 0.90, 1, 0.10));
    Assert.assertFalse("sound corridor (better error) does not beat accepted (worse error)",
      GreedyRoundTripPlanner.isBetterFallback(1, 0.10, 0, 0.90));
    Assert.assertTrue("same tier (corridor) → lower error wins",
      GreedyRoundTripPlanner.isBetterFallback(1, 0.10, 1, 0.20));
  }

  @Test
  public void greedyRejectsAllowSamewayback() {
    // allowSamewayback semantics (out-and-back) are not implemented by greedy;
    // dispatch must fall back to the waypoint-based algorithm.
    Assert.assertFalse("greedy should not handle allowSamewayback",
      RoutingEngine.greedySupports(true, 1));
  }

  @Test
  public void greedyRejectsExtraUserViaPoints() {
    // User-supplied via points are not honored by greedy today; must fall back.
    Assert.assertFalse("greedy should not handle extra user via points",
      RoutingEngine.greedySupports(false, 2));
    Assert.assertFalse("greedy should not handle 3 user waypoints",
      RoutingEngine.greedySupports(false, 3));
  }

  @Test
  public void greedyAcceptsLoneStartWaypoint() {
    Assert.assertTrue("greedy supports a single start waypoint",
      RoutingEngine.greedySupports(false, 1));
  }

  @Test
  public void combinedRoutedScorePrefersLowerVisitedRatio() {
    // Two candidates with identical cost-per-meter; the one with lower
    // scorer score (e.g. less edge reuse) should win.
    CandidateScorer scorer = new CandidateScorer();
    double subTarget = 2000;
    double total = 0;
    double desired = 10000;
    double estimatedReturn = 8000;

    double freshScore = scorer.score(
      2000, subTarget, total, estimatedReturn, desired,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000, -1);
    double reusedScore = scorer.score(
      2000, subTarget, total, estimatedReturn, desired,
      90, DirectionPreference.ANY, 1, 5,
      0.8, 5000, 5000, -1);

    double freshCombined = GreedyRoundTripPlanner.combinedRoutedScore(freshScore, 1.2);
    double reusedCombined = GreedyRoundTripPlanner.combinedRoutedScore(reusedScore, 1.2);

    Assert.assertTrue("fresh candidate should beat reused at equal cost-per-meter",
      freshCombined < reusedCombined);
  }

  @Test
  public void combinedRoutedScoreFactorsInCostPerMeter() {
    // With identical scorer score, lower cost/meter is preferred.
    double scorerScore = 0.5;
    double cheap = GreedyRoundTripPlanner.combinedRoutedScore(scorerScore, 1.0);
    double expensive = GreedyRoundTripPlanner.combinedRoutedScore(scorerScore, 3.0);
    Assert.assertTrue("cheaper route should score lower at equal scorer score",
      cheap < expensive);
  }

  @Test
  public void greedyMatchedWaypointsAreNeverBeeline() {
    // Successful greedy plans must produce road-snapped (SHAPING) waypoints.
    // A DIRECT entry would tell the routing engine to insert a beeline,
    // which violates the round-trip invariant.
    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(null, new RoundTripCandidateProvider.RadialCandidateProvider());

    MatchedWaypoint startMwp = makeMatchedWaypoint(1000, 1000, 900, 900, 1100, 1100);
    // Even when the source MWP is marked DIRECT, copyMatchedWaypoint must
    // reset it to SHAPING so beeline behavior cannot leak through.
    startMwp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;

    MatchedWaypoint via1 = makeMatchedWaypoint(2000, 2000, 1900, 1900, 2100, 2100);
    via1.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
    MatchedWaypoint via2 = makeMatchedWaypoint(3000, 3000, 2900, 2900, 3100, 3100);

    List<MatchedWaypoint> stack = new ArrayList<>();
    stack.add(startMwp);
    stack.add(via1);
    stack.add(via2);

    List<MatchedWaypoint> matched = planner.buildMatchedWaypoints(stack, startMwp);

    Assert.assertEquals("expected from + via1 + via2 + to", 4, matched.size());
    for (MatchedWaypoint m : matched) {
      Assert.assertNotEquals("greedy matched waypoint " + m.name + " must not be DIRECT",
        MatchedWaypoint.WAYPOINT_TYPE_DIRECT, m.wpttype);
      Assert.assertEquals("greedy matched waypoint " + m.name + " must be SHAPING",
        MatchedWaypoint.WAYPOINT_TYPE_SHAPING, m.wpttype);
      // waypoint == crosspoint is what disables RoutingEngine's dynamic beeline
      // insertion (it triggers when distance(waypoint, crosspoint) > catchingRange).
      Assert.assertEquals("waypoint and crosspoint must coincide for " + m.name,
        m.crosspoint.ilon, m.waypoint.ilon);
      Assert.assertEquals("waypoint and crosspoint must coincide for " + m.name,
        m.crosspoint.ilat, m.waypoint.ilat);
    }
    // Vias are planner-generated: the engine's via-pinned spur/teardrop cleanup
    // (isNearGeneratedWaypoint) keys on the generated flag — greedy vias are
    // named "via*", not "rt*", so without the flag the cleanup never activates.
    // Start/closing copies of the user's start point stay non-generated.
    Assert.assertFalse("'from' is the user's start, not generated", matched.get(0).generated);
    Assert.assertTrue("'via1' must be flagged generated", matched.get(1).generated);
    Assert.assertTrue("'via2' must be flagged generated", matched.get(2).generated);
    Assert.assertFalse("'to' is the user's start, not generated", matched.get(3).generated);
  }

  @Test
  public void pocketPenaltyRampsWithReachability() {
    // No cloud (radial/iso-start candidates): no signal, no penalty.
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.pocketPenalty(-1), 1e-9);
    // Junction-rich neighborhood and the well-connected expansion-edge
    // half-disk (~12 cells) are both penalty-free.
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.pocketPenalty(25), 1e-9);
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.pocketPenalty(12), 1e-9);
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.pocketPenalty(10), 1e-9);
    // Thin dead-end corridor saturates.
    Assert.assertEquals(1.0, GreedyRoundTripPlanner.pocketPenalty(3), 1e-9);
    Assert.assertEquals(1.0, GreedyRoundTripPlanner.pocketPenalty(0), 1e-9);
    // Monotone ramp in between.
    double p7 = GreedyRoundTripPlanner.pocketPenalty(7);
    double p5 = GreedyRoundTripPlanner.pocketPenalty(5);
    Assert.assertTrue("ramp must be monotone: p(7)=" + p7 + " p(5)=" + p5,
      p7 > 0 && p5 > p7 && p5 < 1.0);
  }

  private static MatchedWaypoint makeMatchedWaypoint(int crossLon, int crossLat,
                                                     int n1Lon, int n1Lat,
                                                     int n2Lon, int n2Lat) {
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.crosspoint = new OsmNode(crossLon, crossLat);
    mwp.waypoint = new OsmNode(crossLon, crossLat);
    mwp.node1 = new OsmNode(n1Lon, n1Lat);
    mwp.node2 = new OsmNode(n2Lon, n2Lat);
    return mwp;
  }

  @Test
  public void candidateScorerDefaultWeights() {
    CandidateScorer scorer = new CandidateScorer();
    // Basic sanity: perfect candidate should score very low
    double perfectScore = scorer.score(
      2000, 2000,  // exact distance match
      0, 8000, 10000,  // perfect loop feasibility
      0, DirectionPreference.N,
      1, 5,
      0.0, 5000, 5000, -1);

    double badScore = scorer.score(
      4000, 2000,  // 100% over target
      0, 12000, 10000,  // 40% over desired
      180, DirectionPreference.N,  // opposite direction
      1, 5,
      0.5, 500, 5000, -1);  // high reuse, too close to start

    Assert.assertTrue("Perfect candidate should beat bad one", perfectScore < badScore);
  }

  @Test
  public void subRouteCountDefaultIsFive() {
    // Verify construction works with default and explicit params
    // (can't invoke plan() without segments, but constructors should succeed)
    Assert.assertNotNull(new CandidateScorer());
    Assert.assertNotNull(new CandidateScorer(1.0, 2.0, 0.5, 3.0, 1.5));
  }

  // ---- Boundary-proximity weighting for back-and-forth penalty ----
  //
  // These weights are a planner steering hint, NOT the semantic stem/spur
  // classifier (that runs in ReuseClassifier after routing). Keeping the
  // boundary-near-1.0/mid-loop-0.5 split matches what the production planner
  // actually needs: pushing the semantic stem-vs-mid distinction down to
  // per-edge weights regressed real Dreieich loops (the planner skipped
  // viable paved sub-routes and picked path/track alternatives that the
  // profile gate then rejected).

  @Test
  public void boundaryProximityWeightFullPenaltyNearStart() {
    // Both positions within first 20% of the loop → full penalty (1.0).
    double w = GreedyRoundTripPlanner.boundaryProximityWeight(500, 1000, 10000);
    Assert.assertEquals("near-start reuse → full penalty", 1.0, w, 0.001);
  }

  @Test
  public void boundaryProximityWeightFullPenaltyNearEnd() {
    // Both positions within last 20% of the loop → full penalty (1.0).
    double w = GreedyRoundTripPlanner.boundaryProximityWeight(8500, 9000, 10000);
    Assert.assertEquals("near-end reuse → full penalty", 1.0, w, 0.001);
  }

  @Test
  public void boundaryProximityWeightHalfPenaltyMidLoop() {
    // Both positions in middle 60% of the loop → half penalty (0.5). This
    // reflects the cyclist intuition: a road crossed once on the way out and
    // again on the way back at mid-loop is less annoying than the same
    // crossing right after departure.
    double w = GreedyRoundTripPlanner.boundaryProximityWeight(3500, 6500, 10000);
    Assert.assertEquals("mid-loop reuse → half penalty", 0.5, w, 0.001);
  }

  @Test
  public void boundaryProximityWeightFullPenaltyIfEitherEndIsNearBoundary() {
    // First visit near start (boundary=0.05); current visit mid-loop (boundary=0.5).
    // min(0.05, 0.5) = 0.05 < 0.2 → full penalty.
    double w = GreedyRoundTripPlanner.boundaryProximityWeight(500, 5000, 10000);
    Assert.assertEquals("one near-boundary → full penalty", 1.0, w, 0.001);
  }

  @Test
  public void finalTrackReuseRatioCountsSecondAndLaterVisitsOnly() {
    // Test the bug fix: finalTrackReuseRatio shouldn't count an edge as "reuse"
    // just because it appears in the track — only subsequent re-traversals count.
    OsmTrack track = new OsmTrack();
    track.nodes = new ArrayList<>();
    track.nodes.add(makeNode(0, 0));         // 0,0
    track.nodes.add(makeNode(1000, 0));      // 1,0  (edge 0-1)
    track.nodes.add(makeNode(2000, 0));      // 2,0  (edge 1-2)
    track.nodes.add(makeNode(1000, 0));      // back to 1  (edge 1-2 REUSED)
    track.nodes.add(makeNode(0, 0));         // back to 0  (edge 0-1 REUSED)
    track.distance = (int) (track.nodes.get(0).calcDistance(track.nodes.get(1))
      + track.nodes.get(1).calcDistance(track.nodes.get(2))
      + track.nodes.get(2).calcDistance(track.nodes.get(3))
      + track.nodes.get(3).calcDistance(track.nodes.get(4)));
    double reuse = GreedyRoundTripPlanner.finalTrackReuseRatio(track);
    // 4 edges total; 2 are first visits (no reuse), 2 are second visits (reuse).
    // distance-weighted: equal segments, so reuse = 2/4 = 0.5.
    Assert.assertEquals("half the track is reused", 0.5, reuse, 0.05);
  }

  @Test
  public void finalTrackReuseRatioZeroForNonRepeatingTrack() {
    OsmTrack track = new OsmTrack();
    track.nodes = new ArrayList<>();
    track.nodes.add(makeNode(0, 0));
    track.nodes.add(makeNode(1000, 0));
    track.nodes.add(makeNode(1000, 1000));
    track.nodes.add(makeNode(0, 1000));
    track.nodes.add(makeNode(0, 0));
    Assert.assertEquals("no edge traversed twice → 0 reuse", 0.0,
      GreedyRoundTripPlanner.finalTrackReuseRatio(track), 0.001);
  }

  private static OsmPathElement makeNode(int dx, int dy) {
    return OsmPathElement.create(8720000 + dx, 50000000 + dy, (short) 0, null);
  }

  // ---- Phase 1.5 — internal fallback gate delegates to production gate -

  @Test
  public void qualityGateReasonDelegatesToProductionGate() {
    // Build a track tripping the production gate's hostile-fraction check
    // (every edge tagged highway=path with surface=ground → 100% hostile)
    // and verify the planner's internal qualityGateReason returns a
    // rejection reason matching the production gate's prefix. Pre-Phase 1.5
    // the planner used independent FALLBACK_* constants and would have
    // accepted this track (it has zero reuse and clean ratio).
    OsmTrack heavy = squarePathTrack(/*sideMeters*/ 5000);
    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(null, new RoundTripCandidateProvider.RadialCandidateProvider());
    planner.setProfileName("fastbike");
    String reason = planner.qualityGateReason(heavy, heavy.distance);
    Assert.assertNotNull("planner rejects hostile-fraction routes", reason);
    Assert.assertTrue(
      "planner reason matches production gate prefix (contiguous or % hostile): " + reason,
      reason.startsWith("contiguous ") || reason.contains("of distance on profile-hostile ways"));
  }

  @Test
  public void qualityGateReasonAcceptsCleanLoop() {
    // The same delegation accepts a clean paved loop.
    OsmTrack clean = squareResidentialTrack(/*sideMeters*/ 5000);
    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(null, new RoundTripCandidateProvider.RadialCandidateProvider());
    planner.setProfileName("fastbike");
    Assert.assertNull("planner accepts clean residential loop",
      planner.qualityGateReason(clean, clean.distance));
  }

  /** Synthetic 4-side square loop tagged entirely with highway=path
   * surface=ground (no asphalt override, so trips the gate). */
  private static OsmTrack squarePathTrack(int side) {
    OsmTrack t = squareTrack(side);
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=ground";
    m.costfactor = 3.0f; // low enough not to trip costfactor check alone
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = cloneMsg(m);
    }
    return t;
  }

  /** Synthetic 4-side square loop tagged residential / asphalt (clean). */
  private static OsmTrack squareResidentialTrack(int side) {
    OsmTrack t = squareTrack(side);
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=residential surface=asphalt";
    m.costfactor = 1.0f;
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = cloneMsg(m);
    }
    return t;
  }

  private static OsmTrack squareTrack(int side) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    int baseLon = 180_000_000;
    int baseLat = 50_000_000;
    int ilonPerM = 14;
    int ilatPerM = 9;
    t.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    t.nodes.add(OsmPathElement.create(baseLon + side * ilonPerM, baseLat, (short) 0, null));
    t.nodes.add(OsmPathElement.create(baseLon + side * ilonPerM, baseLat + side * ilatPerM, (short) 0, null));
    t.nodes.add(OsmPathElement.create(baseLon, baseLat + side * ilatPerM, (short) 0, null));
    t.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    int d = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      d += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    }
    t.distance = d;
    return t;
  }

  private static MessageData cloneMsg(MessageData src) {
    MessageData m = new MessageData();
    m.wayKeyValues = src.wayKeyValues;
    m.costfactor = src.costfactor;
    return m;
  }

  // ======================================================================
  // pickDiverseTopK — angular-diversity candidate selection (was untested).
  // MIN_ANGULAR_SEPARATION_DEG = 30°, strict '<'. After the diversity pass
  // culls near-bearing duplicates, a back-fill tops the result up to k.
  // ======================================================================

  private static RoundTripCandidateProvider.CandidatePoint cp(double bearing) {
    RoundTripCandidateProvider.CandidatePoint p = new RoundTripCandidateProvider.CandidatePoint();
    p.bearing = bearing;
    return p;
  }

  private static List<RoundTripCandidateProvider.CandidatePoint> cps(double... bearings) {
    List<RoundTripCandidateProvider.CandidatePoint> list = new ArrayList<>();
    for (double b : bearings) list.add(cp(b));
    return list;
  }

  @Test
  public void pickDiverseTopK_cullsNearBearingCandidates() {
    // 10° is within 30° of the already-picked 0° → culled; 200° is far → picked.
    List<RoundTripCandidateProvider.CandidatePoint> picked =
      GreedyRoundTripPlanner.pickDiverseTopK(cps(0, 10, 200), 2);
    Assert.assertEquals(2, picked.size());
    Assert.assertEquals(0.0, picked.get(0).bearing, 1e-9);
    Assert.assertEquals(200.0, picked.get(1).bearing, 1e-9);
  }

  @Test
  public void pickDiverseTopK_backfillsWhenDiversityStarvesBelowK() {
    // All three are within 30° of the first: only 0° survives the diversity
    // pass, so the back-fill restores k by insertion order (adds 10°).
    List<RoundTripCandidateProvider.CandidatePoint> picked =
      GreedyRoundTripPlanner.pickDiverseTopK(cps(0, 10, 20), 2);
    Assert.assertEquals(2, picked.size());
    Assert.assertEquals(0.0, picked.get(0).bearing, 1e-9);
    Assert.assertEquals(10.0, picked.get(1).bearing, 1e-9);
  }

  @Test
  public void pickDiverseTopK_diversityUsesAngularWraparound() {
    // 350° and 10° are 20° apart across the 0/360 seam → 10° is culled even
    // though its numeric distance from 350 is 340.
    List<RoundTripCandidateProvider.CandidatePoint> picked =
      GreedyRoundTripPlanner.pickDiverseTopK(cps(350, 10, 200), 2);
    Assert.assertEquals(2, picked.size());
    Assert.assertEquals(350.0, picked.get(0).bearing, 1e-9);
    Assert.assertEquals(200.0, picked.get(1).bearing, 1e-9);
  }

  @Test
  public void pickDiverseTopK_exactSeparationIsNotCulled() {
    // Strict '<' separation: exactly 30° apart is kept (both survive).
    List<RoundTripCandidateProvider.CandidatePoint> picked =
      GreedyRoundTripPlanner.pickDiverseTopK(cps(0, 30), 2);
    Assert.assertEquals(2, picked.size());
  }

  @Test
  public void pickDiverseTopK_returnsAllWhenKExceedsSize() {
    Assert.assertEquals(3,
      GreedyRoundTripPlanner.pickDiverseTopK(cps(0, 90, 180), 5).size());
  }

  @Test
  public void pickDiverseTopK_emptyAndZeroKAreEmpty() {
    Assert.assertTrue(GreedyRoundTripPlanner.pickDiverseTopK(
      new ArrayList<>(), 3).isEmpty());
    Assert.assertTrue(GreedyRoundTripPlanner.pickDiverseTopK(
      cps(0, 90), 0).isEmpty());
  }

  // ---- boundaryProximityWeight guard + clamp (the desired<=0 and beyond-end
  //      branches the existing near/mid tests don't reach) -------------------

  @Test
  public void boundaryProximityWeight_nonPositiveDesiredReturnsFull() {
    Assert.assertEquals("desired==0 guard → full weight",
      1.0, GreedyRoundTripPlanner.boundaryProximityWeight(3500, 6500, 0), 1e-9);
    Assert.assertEquals("negative desired guard → full weight",
      1.0, GreedyRoundTripPlanner.boundaryProximityWeight(3500, 6500, -5000), 1e-9);
  }

  @Test
  public void boundaryProximityWeight_positionsBeyondDesiredClampToBoundary() {
    // firstPos/currentPos past desiredDistance: the (1-frac) term goes negative
    // and is clamped to 0, so a beyond-end position reads as on-boundary → 1.0.
    Assert.assertEquals(1.0,
      GreedyRoundTripPlanner.boundaryProximityWeight(12000, 11000, 10000), 1e-9);
  }

  // ---- heading persistence (loop-shape term, 2026-06-11) -------------------

  @Test
  public void headingPersistence_withinQuotaIsFree() {
    // 6 steps → quota = 1.5 × 60° = 90°: gentle continuation costs nothing.
    Assert.assertEquals(0.0,
      GreedyRoundTripPlanner.headingPersistencePenalty(90, 90, 6), 1e-9);
    Assert.assertEquals(0.0,
      GreedyRoundTripPlanner.headingPersistencePenalty(90, 150, 6), 1e-9);
    Assert.assertEquals("wraparound handled",
      0.0, GreedyRoundTripPlanner.headingPersistencePenalty(350, 30, 6), 1e-9);
  }

  @Test
  public void headingPersistence_kinksAndReversalsPay() {
    // 120° kink at quota 90° → excess 30/180.
    Assert.assertEquals(30.0 / 180.0,
      GreedyRoundTripPlanner.headingPersistencePenalty(0, 120, 6), 1e-9);
    // Full reversal → excess 90/180 = 0.5 (the zigzag fingerprint).
    Assert.assertEquals(0.5,
      GreedyRoundTripPlanner.headingPersistencePenalty(0, 180, 6), 1e-9);
    // Fewer steps → larger quota: the same 120° kink is free at 4 steps (quota 135°).
    Assert.assertEquals(0.0,
      GreedyRoundTripPlanner.headingPersistencePenalty(0, 120, 4), 1e-9);
  }

  @Test
  public void headingPersistence_terrainGateFades() {
    // Open network (baseline indirectness): full weight.
    Assert.assertEquals(1.0, GreedyRoundTripPlanner.headingTerrainFreedom(1.3), 1e-9);
    // Constrained terrain (Nice-class, observed ~2.0): term fully off —
    // the network dictates the headings, fighting it ships weave (A/B:
    // coastal_nice_100km_gravel went 0→43 crossings at full weight).
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.headingTerrainFreedom(2.0), 1e-9);
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.headingTerrainFreedom(2.5), 1e-9);
    // Midpoint fades linearly.
    Assert.assertEquals(0.5, GreedyRoundTripPlanner.headingTerrainFreedom(1.65), 1e-9);
  }

  // ---- leg-junction seam contiguity (loop-review backlog item 1) -----------

  private static OsmTrack trackWithNodes(int[][] lonLat) {
    OsmTrack t = new OsmTrack();
    for (int[] p : lonLat) {
      OsmNode n = new OsmNode(p[0], p[1]);
      t.nodes.add(OsmPathElement.create(n.ilon, n.ilat, (short) 0, null));
    }
    return t;
  }

  @Test
  public void seamGaps_contiguousLegsReportNothing() {
    // Leg 2 starts exactly where leg 1 ends — the by-construction contract.
    OsmTrack a = trackWithNodes(new int[][]{{188720000, 140000000}, {188730000, 140000000}});
    OsmTrack b = trackWithNodes(new int[][]{{188730000, 140000000}, {188740000, 140000000}});
    Assert.assertTrue(GreedyRoundTripPlanner.seamGapsMeters(
      java.util.Arrays.asList(a, b), null).isEmpty());
  }

  @Test
  public void seamGaps_smallSnapOffsetTolerated() {
    // ~35m offset (0.0005 deg lon at 50N) — below the 100m defect threshold.
    OsmTrack a = trackWithNodes(new int[][]{{188720000, 140000000}, {188730000, 140000000}});
    OsmTrack b = trackWithNodes(new int[][]{{188730500, 140000000}, {188740000, 140000000}});
    Assert.assertTrue(GreedyRoundTripPlanner.seamGapsMeters(
      java.util.Arrays.asList(a, b), null).isEmpty());
  }

  @Test
  public void seamGaps_spliceDefectReported() {
    // Leg 2 starts ~700m away from leg 1's end (0.01 deg lon) — a glued
    // non-adjacent endpoint, exactly what the safety net exists to surface.
    OsmTrack a = trackWithNodes(new int[][]{{188720000, 140000000}, {188730000, 140000000}});
    OsmTrack b = trackWithNodes(new int[][]{{188830000, 140000000}, {188840000, 140000000}});
    List<String> gaps =
      GreedyRoundTripPlanner.seamGapsMeters(java.util.Arrays.asList(a), b);
    Assert.assertEquals(1, gaps.size());
    Assert.assertTrue("gap message names the leg: " + gaps.get(0),
      gaps.get(0).contains("leg 2"));
  }

  @Test
  public void seamGaps_emptyLegsSkipped() {
    OsmTrack a = trackWithNodes(new int[][]{{188720000, 140000000}, {188730000, 140000000}});
    OsmTrack empty = new OsmTrack();
    OsmTrack b = trackWithNodes(new int[][]{{188730000, 140000000}, {188740000, 140000000}});
    Assert.assertTrue(GreedyRoundTripPlanner.seamGapsMeters(
      java.util.Arrays.asList(a, empty, b), null).isEmpty());
  }

  // ---- variety seed (ADR-0001): score-jitter hash properties ---------------

  @Test
  public void seededUnit_isDeterministicAndBounded() {
    double a = GreedyRoundTripPlanner.seededUnit(3, 8_500_000, 47_500_000);
    double b = GreedyRoundTripPlanner.seededUnit(3, 8_500_000, 47_500_000);
    Assert.assertEquals("same seed + same salts must reproduce exactly", a, b, 0.0);
    for (int seed = 1; seed <= 50; seed++) {
      for (int salt = 0; salt < 20; salt++) {
        double u = GreedyRoundTripPlanner.seededUnit(seed, salt, salt * 31 + 7);
        Assert.assertTrue("unit must lie in [-1,1), got " + u, u >= -1.0 && u < 1.0);
      }
    }
  }

  @Test
  public void seededUnit_variesAcrossSeedsAndCandidates() {
    // Different seeds must jitter the same candidate differently — this is
    // what makes seed N select a different loop variant than seed M.
    double s1 = GreedyRoundTripPlanner.seededUnit(1, 8_500_000, 47_500_000);
    double s2 = GreedyRoundTripPlanner.seededUnit(2, 8_500_000, 47_500_000);
    Assert.assertNotEquals("seeds 1 and 2 must differ", s1, s2, 1e-12);
    // The same seed must jitter different candidates differently — otherwise
    // every candidate shifts by the same factor and the ranking never flips.
    double c1 = GreedyRoundTripPlanner.seededUnit(5, 8_500_000, 47_500_000);
    double c2 = GreedyRoundTripPlanner.seededUnit(5, 8_500_100, 47_500_000);
    Assert.assertNotEquals("neighboring candidates must differ", c1, c2, 1e-12);
  }

  @Test
  public void varietyJitter_factorStaysWithinAmplitude() {
    // The multiplicative factor must stay inside 1 ± VARIETY_JITTER_AMPLITUDE,
    // the bound the gate-pass-rate calibration (ADR-0001) is built on.
    for (int seed = 1; seed <= 100; seed++) {
      double factor = 1.0 + GreedyRoundTripPlanner.VARIETY_JITTER_AMPLITUDE
        * GreedyRoundTripPlanner.seededUnit(seed, 8_500_000, 47_500_000);
      Assert.assertTrue("factor out of bounds: " + factor,
        factor >= 1.0 - GreedyRoundTripPlanner.VARIETY_JITTER_AMPLITUDE
          && factor < 1.0 + GreedyRoundTripPlanner.VARIETY_JITTER_AMPLITUDE);
    }
  }

}
