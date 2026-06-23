package btools.router;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

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
   * Wiring guard for the shared competition budget: a 1 ms overall deadline
   * lets only the FIRST candidate run (it still gets the MIN_CHILD floor so it
   * completes), and every later candidate is skipped once now >= deadline — so
   * the adopted AUTO message records exactly one candidate. With a full budget
   * the same request runs more than one. This proves the budget is shared
   * across the competition, not handed to each candidate in full.
   */
  @Test
  public void tinyBudgetRunsOnlyTheFirstCandidate() {
    RoutingEngine re = autoEngineWithBudget(1L);
    Assert.assertNull("tiny budget must complete cleanly: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull("first candidate still runs under the MIN budget floor", t);
    Assert.assertTrue("still a real loop", t.nodes.size() > 2);
    Assert.assertNotNull("adopted track carries the competition summary", t.message);
    Assert.assertTrue("only the first AUTO candidate ran (rest skipped past the 1ms deadline): "
      + t.message, t.message.contains("after 1 candidate(s)"));

    // Sanity: the same request with a full budget runs more than one candidate,
    // so the single-candidate result above is the deadline-skip at work, not an
    // intrinsic property of the request.
    OsmTrack full = autoEngineWithBudget(60_000L).getFoundTrack();
    Assert.assertNotNull(full);
    Assert.assertFalse("full budget should run more than one candidate: " + full.message,
      full.message.contains("after 1 candidate(s)"));
  }

  private OsmTrack autoLoopWithBudget(long budgetMs) {
    return autoEngineWithBudget(budgetMs).getFoundTrack();
  }

  private RoutingEngine autoEngineWithBudget(long budgetMs) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.72, 50.0));
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(PROFILE).getAbsolutePath();
    rc.roundTripDistance = RADIUS;
    rc.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
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
