package btools.router;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import btools.router.roundtrip.RoundTripAlgorithm;
import btools.router.roundtrip.RoundTripResult;

/**
 * Geometric- and algorithm-quality guards for <em>small</em> round-trip loops
 * that run in CI against the bundled Dreieich fixture (see {@link RoundTripFixture}).
 * They complement {@link RoundTripInvariantTest} (structural invariants of the
 * default/AUTO algorithm across profiles/directions) by covering aspects no CI
 * suite touched:
 *
 * <ul>
 *   <li><b>Omnidirectional cleanliness</b> — small loops in <em>all four</em>
 *       compass directions for AUTO and both greedy variants. The whole point of
 *       the AUTO redesign was to replace the probe-spike chaos pattern (many
 *       self-crossings) with clean loops; that was only guarded by the
 *       segments-gated Mallorca test (skipped in CI). Here it runs on the fixture.</li>
 *   <li><b>Explicit GREEDY / ISO_GREEDY validity</b> — the scenario suite forces
 *       only WAYPOINT and ISOCHRONE; the greedy variants (which assemble a loop
 *       from merged legs) are forced and validated directly here.</li>
 *   <li><b>AUTO competition entered + winner recorded</b>, and forced variants
 *       fully finalized — previously only in the segments-gated competition suite.</li>
 *   <li><b>Profile policy</b> — a paved-only profile must reject the fixture's
 *       path/track terrain with a clear error and no degenerate track.</li>
 *   <li><b>Radius is honoured</b> — a larger search radius yields a longer loop.</li>
 * </ul>
 *
 * <p>The fixture is a ~3 km synthetic tile. The {@code gravel} profile forms a
 * clean loop in every direction at small radii (matrix-verified across
 * algorithm/direction/radius), so these tests are reliable rather than
 * direction-fragile. Larger radii and real-geography shape quality live in the
 * gated suite ({@link LoopQualityTestBase}).
 */
public class RoundTripQualityFixtureTest {

  private static final String PROFILE = "gravel";
  private static final int RADIUS = 1000;
  private static final int EAST = 90;
  private static final int[] DIRECTIONS = {0, 90, 180, 270};

  /** Clean loops measure 0–1 self-crossings on the fixture; allow a small margin
   *  while still failing the chaos pattern (many crossings). */
  private static final int MAX_SELF_CROSSINGS = 2;

  /** Greedy-merged loops retrace a short shared stem near the origin, so allow
   *  more reuse than the strict 30% AUTO invariant while still requiring a loop. */
  private static final double MAX_REUSE_PCT = 40.0;

  @Test
  public void omnidirectionalSmallLoopsAreCleanAndValid() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.AUTO, RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      for (int dir : DIRECTIONS) {
        assertCleanLoop(algo, dir);
      }
    }
  }

  @Test
  public void autoCompetitionAdoptsAndRecordsWinner() {
    RoutingEngine re = RoundTripFixture.engine(PROFILE, EAST, RADIUS,
      rc -> rc.roundTripAlgorithm = RoundTripAlgorithm.AUTO);
    Assert.assertNull("AUTO completed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("AUTO produced a track", track);
    Assert.assertNotNull("AUTO track carries a message", track.message);
    // The competition adopted a candidate and recorded which algorithm won.
    Assert.assertTrue("AUTO message records the competition winner: " + track.message,
      track.message.contains("AUTO selected"));
  }

  /**
   * Forced GREEDY/ISO_GREEDY bypass the competition but still run through the
   * shared finalize path; the result must record the standard info line (not be
   * left with only the planner's internal note), proving the adopted track is
   * fully finalized — and must not carry an AUTO summary.
   */
  @Test
  public void forcedGreedyVariantsAreFullyFinalized() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      RoutingEngine re = RoundTripFixture.engine(PROFILE, EAST, RADIUS,
        rc -> rc.roundTripAlgorithm = algo);
      Assert.assertNull(algo + " completed: " + re.getErrorMessage(), re.getErrorMessage());
      OsmTrack track = re.getFoundTrack();
      Assert.assertNotNull(algo + " produced a track", track);
      Assert.assertFalse(algo + " bypasses the competition (no AUTO summary)",
        track.message != null && track.message.contains("AUTO selected"));
      Assert.assertNotNull(algo + " info line present", track.messageList);
      Assert.assertFalse(algo + " info line non-empty", track.messageList.isEmpty());
    }
  }

  /**
   * Issue #26 §4 — per-leg source attribution, end-to-end. Every accepted leg
   * of a greedy-family plan records a grep-able "leg N source:" diagnostic,
   * and the aggregate counters match the per-leg lines exactly (commits and
   * undos stay in lock-step). Plain GREEDY runs without an iso pool, so its
   * health telemetry stays at the sentinels and its legs are all graph-native;
   * when ISO_GREEDY actually planned against an iso pool, the plan-exit
   * "iso-pool health:" summary and per-leg poolHealth suffix are present too.
   */
  @Test
  public void acceptedLegsCarrySourceAttributionDiagnostics() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      RoutingEngine re = RoundTripFixture.engine(PROFILE, EAST, RADIUS,
        rc -> rc.roundTripAlgorithm = algo);
      Assert.assertNull(algo + " completed: " + re.getErrorMessage(), re.getErrorMessage());
      RoundTripResult result = re.getLastRoundTripResult();
      Assert.assertNotNull(algo + " recorded a planner result", result);

      List<String> legLines = new ArrayList<>();
      boolean healthSummary = false;
      for (String d : result.getDiagnostics()) {
        if (d.startsWith("leg ") && d.contains(" source: ")) legLines.add(d);
        if (d.startsWith("iso-pool health: ")) healthSummary = true;
      }
      Assert.assertFalse(algo + " accepted legs must carry attribution lines",
        legLines.isEmpty());
      Assert.assertEquals(algo + " per-leg lines match the accepted-leg counters",
        result.getAcceptedIsoLegs() + result.getAcceptedNonIsoLegs(), legLines.size());
      for (String line : legLines) {
        Assert.assertTrue(line, line.contains("quotaInjected="));
        Assert.assertTrue(line, line.contains("return="));
        Assert.assertTrue(line, line.contains("heurRank="));
      }

      boolean hasHealth = !Double.isNaN(result.getIsoPoolHealthScore());
      Assert.assertEquals(algo + " health summary present iff an iso pool was tracked",
        hasHealth, healthSummary);
      if (algo == RoundTripAlgorithm.GREEDY) {
        Assert.assertFalse("plain GREEDY never tracks pool health", hasHealth);
        Assert.assertEquals(-1, result.getPoolDemotedAtStep());
        for (String line : legLines) {
          Assert.assertTrue("plain GREEDY legs are graph-native: " + line,
            line.contains("source: graph-native"));
          Assert.assertFalse("no poolHealth suffix without an iso pool: " + line,
            line.contains("poolHealth="));
        }
      } else if (hasHealth) {
        for (String line : legLines) {
          Assert.assertTrue("iso-pool plans stamp health on every leg: " + line,
            line.contains("poolHealth="));
        }
      }
    }
  }

  /**
   * Profile policy: a paved-only road-bike profile must reject the fixture's
   * unpaved path/track terrain through the quality gate — a clear error and no
   * degenerate track, never a silently-bad loop on hostile ways.
   */
  @Test
  public void pavedOnlyProfileRejectsHostileFixtureCleanly() {
    RoutingEngine re = RoundTripFixture.engine("fastbike", EAST, RADIUS,
      rc -> rc.roundTripAlgorithm = RoundTripAlgorithm.AUTO);
    Assert.assertNotNull("paved-only profile must fail on the unpaved fixture",
      re.getErrorMessage());
    Assert.assertNull("a rejected route must not return a track", re.getFoundTrack());
    Assert.assertTrue("error should explain the rejection: " + re.getErrorMessage(),
      re.getErrorMessage().contains("rejected") || re.getErrorMessage().contains("hostile")
        || re.getErrorMessage().contains("no acceptable route"));
  }

  /**
   * The AUTO competition now bounds its candidates with a shared wall-clock
   * budget (and the WAYPOINT/ISOCHRONE fallbacks honour it). A generous finite
   * budget must not prematurely time out a normal fixture loop: the result must
   * match the unbounded (doRun(0)) run exactly. This guards against the timeout
   * plumbing breaking valid completions; the timeout actually firing needs a
   * runaway route that the tiny fixture cannot produce.
   */
  @Test
  public void finiteBudgetMatchesUnboundedForSmallLoop() {
    OsmTrack unbounded = autoLoopWithBudget(0L);
    OsmTrack timed = autoLoopWithBudget(60_000L);
    Assert.assertNotNull("unbounded run produced a loop", unbounded);
    Assert.assertNotNull("finite-budget run produced a loop", timed);
    Assert.assertEquals("finite budget must not alter the loop (node count)",
      unbounded.nodes.size(), timed.nodes.size());
    Assert.assertEquals("finite budget must not alter the loop (distance)",
      unbounded.distance, timed.distance);
  }

  /**
   * Wiring guard for the shared competition budget, on the QUALITY tier
   * (which always runs the competition — AUTO with a tiny budget now
   * resolves BOUNDED effort instead, covered below): a 1 ms overall deadline
   * lets only the FIRST candidate run (it still gets the MIN_CHILD floor so it
   * completes), and every later candidate is skipped once now >= deadline — so
   * the adopted message records exactly one candidate, yet the first
   * candidate still ships thanks to the MIN budget floor. (The full-budget
   * run below is a completion control only: since issue #26 the standard
   * competition skips the duplicate plain-GREEDY run when ISO_GREEDY is
   * strong, so candidate count signals competition strength, not budget.)
   */
  @Test
  public void tinyBudgetRunsOnlyTheFirstCandidate() {
    RoutingEngine re = engineWithBudget(RoundTripAlgorithm.QUALITY, 1L);
    Assert.assertNull("tiny budget must complete cleanly: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull("first candidate still runs under the MIN budget floor", t);
    Assert.assertTrue("still a real loop", t.nodes.size() > 2);
    Assert.assertNotNull("adopted track carries the competition summary", t.message);
    Assert.assertTrue("only the first candidate ran (rest skipped past the 1ms deadline): "
      + t.message, t.message.contains("after 1 candidate(s)"));

    // Control: the same request with a full budget must complete cleanly and
    // carry the competition summary. It is NOT asserted to run more candidates
    // — see the Javadoc note on issue #26.
    OsmTrack full = autoEngineWithBudget(60_000L).getFoundTrack();
    Assert.assertNotNull(full);
    Assert.assertNotNull("full budget must carry the competition summary", full.message);
    Assert.assertTrue("full budget must report the AUTO competition outcome: " + full.message,
      full.message.contains("AUTO selected") && full.message.contains("candidate(s)"));
  }

  /**
   * Positive control for the shared competition budget (restores the coverage
   * of the deleted "full budget should run more than one candidate"
   * assertion): QUALITY always fields both planners, so a generous budget
   * must record MORE than one candidate in the summary. A deadline-arithmetic
   * regression that stops every competition after candidate #1 fails here —
   * the tiny-budget tests alone cannot see it (they expect 1 candidate).
   */
  @Test
  public void qualityWithFullBudgetRunsMoreThanOneCandidate() {
    RoutingEngine re = engineWithBudget(RoundTripAlgorithm.QUALITY, 60_000L);
    Assert.assertNull("quality run must complete cleanly: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull(t);
    Assert.assertNotNull("adopted track carries the competition summary", t.message);
    Assert.assertFalse("full budget must run more than one candidate: " + t.message,
      t.message.contains("after 1 candidate(s)"));
  }

  /**
   * Context-aware AUTO: a request budget too short to fund the full
   * competition resolves BOUNDED effort — one bounded planner dispatch (with
   * the minimum-slice floor, so even a 1 ms budget ships a loop) instead of
   * the candidate competition. The result is a real loop with no competition
   * summary attached.
   */
  @Test
  public void tinyBudgetAutoResolvesBoundedEffort() {
    RoutingEngine re = autoEngineWithBudget(1L);
    Assert.assertNull("bounded dispatch must complete cleanly: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull("minimum-slice floor still ships a loop", t);
    Assert.assertTrue("still a real loop", t.nodes.size() > 2);
    Assert.assertTrue("no competition ran, so no AUTO summary: "
      + t.message, t.message == null || !t.message.contains("AUTO selected"));
  }

  private OsmTrack autoLoopWithBudget(long budgetMs) {
    return autoEngineWithBudget(budgetMs).getFoundTrack();
  }

  /**
   * Product sizing gate: loops above 200km require an explicitly raised
   * request timeout — a standard 60s budget must fail fast with instructions
   * instead of shipping a guaranteed-degraded loop, while a generous budget
   * (or an untimed run) passes the gate.
   */
  @Test
  public void longLoopsRequireExplicitTimeoutOptIn() {
    RoundTripleEngineResult standard = longLoopEngine(250_000, 60_000L);
    Assert.assertNotNull("250km at 60s must be rejected by the opt-in gate",
      standard.errorMessage);
    Assert.assertTrue("rejection must explain the raise-timeout remedy: " + standard.errorMessage,
      standard.errorMessage.contains("raise maxRunningTime"));

    // A raised budget passes the gate: whatever the tiny fixture map lets the
    // planner do afterwards, the failure (if any) must NOT be the opt-in gate.
    RoundTripleEngineResult raised = longLoopEngine(250_000, 180_000L);
    Assert.assertTrue("raised budget must pass the opt-in gate: " + raised.errorMessage,
      raised.errorMessage == null || !raised.errorMessage.contains("raise maxRunningTime"));

    // 200km is within the standard class — the gate must not fire at 60s.
    RoundTripleEngineResult atLimit = longLoopEngine(199_000, 60_000L);
    Assert.assertTrue("199km at 60s must pass the opt-in gate: " + atLimit.errorMessage,
      atLimit.errorMessage == null || !atLimit.errorMessage.contains("raise maxRunningTime"));
  }

  private static final class RoundTripleEngineResult {
    final String errorMessage;

    RoundTripleEngineResult(String errorMessage) {
      this.errorMessage = errorMessage;
    }
  }

  private RoundTripleEngineResult longLoopEngine(int loopMeters, long budgetMs) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.72, 50.0));
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(PROFILE).getAbsolutePath();
    rc.roundTripLength = loopMeters;
    rc.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rc.startDirection = EAST;
    rc.turnInstructionMode = 2;
    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(budgetMs);
    return new RoundTripleEngineResult(re.getErrorMessage());
  }

  private RoutingEngine autoEngineWithBudget(long budgetMs) {
    return engineWithBudget(RoundTripAlgorithm.AUTO, budgetMs);
  }

  private RoutingEngine engineWithBudget(RoundTripAlgorithm algo, long budgetMs) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.72, 50.0));
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(PROFILE).getAbsolutePath();
    rc.roundTripDistance = RADIUS;
    rc.roundTripAlgorithm = algo;
    rc.startDirection = EAST;
    rc.turnInstructionMode = 2;
    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(budgetMs);
    return re;
  }

  /** A larger search radius must yield a longer loop (the radius is honoured). */
  @Test
  public void largerRadiusYieldsLongerLoop() {
    OsmTrack small = loop(RoundTripAlgorithm.AUTO, EAST, 800);
    OsmTrack large = loop(RoundTripAlgorithm.AUTO, EAST, 1500);
    Assert.assertNotNull("r800 loop", small);
    Assert.assertNotNull("r1500 loop", large);
    // Margin 1.2 → 1.15 (2026-06-10): counting node-shared transverse
    // crossings makes the planner reject a knot-bearing longer loop on this
    // tiny fixture grid in favour of a cleaner one 19% longer than r800. The
    // contract is monotonicity (radius is honoured), not exact proportionality
    // — the fixture network cannot supply the full 2πr at r1500 anyway.
    Assert.assertTrue("r1500 loop (" + large.distance + "m) must be clearly longer than r800 ("
        + small.distance + "m)", large.distance > small.distance * 1.15);
  }

  // -------------------------------------------------------------------------

  private void assertCleanLoop(RoundTripAlgorithm algo, int dir) {
    String label = algo + "_dir" + dir + "_r" + RADIUS;
    RoutingEngine re = RoundTripFixture.engine(PROFILE, dir, RADIUS,
      rc -> rc.roundTripAlgorithm = algo);
    Assert.assertNull(label + " completed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull(label + ": fixture should form a small loop", track);

    RoundTripFixture.assertValidLoop(track, label, MAX_REUSE_PCT);

    int selfCrossings = RoundTripFixture.countSelfCrossings(track);
    Assert.assertTrue(label + ": loop must be geometrically clean — self-crossings "
        + selfCrossings + " > " + MAX_SELF_CROSSINGS,
      selfCrossings <= MAX_SELF_CROSSINGS);
  }

  private OsmTrack loop(RoundTripAlgorithm algo, int dir, int radius) {
    return RoundTripFixture.engine(PROFILE, dir, radius,
      rc -> rc.roundTripAlgorithm = algo).getFoundTrack();
  }
}
