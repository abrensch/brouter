package btools.router;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;
import btools.router.roundtrip.GreedyRoundTripPlanner;
import btools.router.roundtrip.RadialCandidateProvider;

/**
 * Regression test for the greedy planner's handling of an unroutable / islanded
 * leg.
 *
 * <p>{@code RoutingEngine._findTrack} throws {@link RoutingIslandException} (an
 * unchecked exception) when a leg's start/end sits on a small disconnected graph
 * component. {@link GreedyRoundTripPlanner#timedFindTrack} must treat that as
 * "no track for this leg" (return {@code null}) — exactly like the
 * {@link IllegalArgumentException} path — so the planner falls back to its
 * best-so-far loop instead of letting the exception propagate out of
 * {@code plan()} and discard the result and all accumulated telemetry.
 *
 * <p>The exception is only thrown deep inside the real Dijkstra with loaded map
 * data, so we drive the leg-routing path through a {@link RoutingEngine} test
 * double whose {@code findTrack} throws — no segment tile required (the override
 * short-circuits before any graph access).
 */
public class RoutingIslandExceptionTest {

  /** A RoutingEngine whose leg router fails the way an islanded leg does. */
  private static RoutingEngine engineThrowing(final RuntimeException toThrow) {
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("gravel").getAbsolutePath();
    return new RoutingEngine(null, null, RoundTripFixture.segmentDir(), new ArrayList<>(), rc) {
      @Override
      public OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                         OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
        throw toThrow;
      }
    };
  }

  private static OsmTrack routeOneLeg(RoutingEngine engine) {
    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(engine.roundTripOps(), new RadialCandidateProvider());
    return planner.timedFindTrack("test-leg",
      new MatchedWaypoint(), new MatchedWaypoint(), null,
      System.currentTimeMillis() + 60_000L);
  }

  /** A MatchedWaypoint with real node positions, so island pairs are recordable. */
  private static MatchedWaypoint mwpAt(int ilon, int ilat) {
    MatchedWaypoint m = new MatchedWaypoint();
    m.waypoint = new btools.mapaccess.OsmNode(ilon, ilat);
    m.crosspoint = new btools.mapaccess.OsmNode(ilon, ilat);
    m.node1 = new btools.mapaccess.OsmNode(ilon, ilat + 500);
    m.node2 = new btools.mapaccess.OsmNode(ilon + 500, ilat);
    return m;
  }

  @Test
  public void islandedLegRecordsEndpointPairsOnFastMotorProfiles() {
    // Fast-motor island learning: an islanded greedy leg must record BOTH
    // endpoints' match pairs into the engine's islandNodePairs (and freeze
    // them), so the next match of the same point avoids the pocket — the same
    // learn-and-re-match loop the engine's top-level findTrack retry uses.
    RoutingEngine engine = engineThrowing(new RoutingIslandException());
    engine.roundTripOps().routingContext().carMode = true;
    MatchedWaypoint from = mwpAt(100_000_000, 100_000_000);
    MatchedWaypoint to = mwpAt(101_000_000, 101_000_000);

    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(engine.roundTripOps(), new RadialCandidateProvider());
    OsmTrack leg = planner.timedFindTrack("test-leg", from, to, null,
      System.currentTimeMillis() + 60_000L);

    Assert.assertNull("the islanded leg still maps to null", leg);
    Assert.assertTrue("from-side pair recorded",
      engine.islandNodePairs.hasPair(from.node1.getIdFromPos(), from.node2.getIdFromPos()));
    Assert.assertTrue("to-side pair recorded",
      engine.islandNodePairs.hasPair(to.node1.getIdFromPos(), to.node2.getIdFromPos()));
  }

  @Test
  public void islandedLegRecordsNothingOnBikeProfiles() {
    // Bike behavior must stay bit-identical: no island learning off carMode.
    RoutingEngine engine = engineThrowing(new RoutingIslandException());
    MatchedWaypoint from = mwpAt(100_000_000, 100_000_000);
    MatchedWaypoint to = mwpAt(101_000_000, 101_000_000);

    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(engine.roundTripOps(), new RadialCandidateProvider());
    Assert.assertNull(planner.timedFindTrack("test-leg", from, to, null,
      System.currentTimeMillis() + 60_000L));

    Assert.assertFalse("no pair recorded for bike profiles",
      engine.islandNodePairs.hasPair(from.node1.getIdFromPos(), from.node2.getIdFromPos()));
    Assert.assertFalse(
      engine.islandNodePairs.hasPair(to.node1.getIdFromPos(), to.node2.getIdFromPos()));
  }

  @Test
  public void routingIslandExceptionOnLegYieldsNullNotPropagation() {
    RoutingEngine engine = engineThrowing(new RoutingIslandException());
    engine.startTime = 11111L;
    engine.maxRunningTime = 22222L;

    OsmTrack leg = routeOneLeg(engine);

    Assert.assertNull("an islanded leg must map to null, not propagate", leg);
    // The finally block must restore the engine's timing state for the next leg.
    Assert.assertEquals("startTime restored after the leg attempt", 11111L, engine.startTime);
    Assert.assertEquals("maxRunningTime restored after the leg attempt", 22222L, engine.maxRunningTime);
  }

  @Test
  public void illegalArgumentOnLegStillYieldsNull() {
    // The pre-existing IllegalArgumentException behaviour must be preserved.
    OsmTrack leg = routeOneLeg(engineThrowing(new IllegalArgumentException("no path")));
    Assert.assertNull("IllegalArgumentException leg must map to null", leg);
  }

  @Test
  public void terminatedEngineRethrowsLegExceptionInsteadOfSwallowing() {
    // Termination responsiveness: once the engine is terminated (watchdog kill /
    // request timeout), a failing leg must PROPAGATE so plan() aborts promptly,
    // rather than being swallowed to null and burning the rest of the budget
    // re-attempting and re-swallowing the same kill on every subsequent leg.
    RoutingEngine engine = engineThrowing(new IllegalArgumentException("killed by watchdog"));
    engine.startTime = 11111L;
    engine.maxRunningTime = 22222L;
    engine.terminate();

    try {
      routeOneLeg(engine);
      Assert.fail("a terminated engine must re-throw the leg exception, not return null");
    } catch (IllegalArgumentException expected) {
      // expected — the catch re-throws when engine.isTerminated()
    }
    // The finally block must still restore the engine's timing state even when
    // the exception propagates.
    Assert.assertEquals("startTime restored after a propagated leg exception", 11111L, engine.startTime);
    Assert.assertEquals("maxRunningTime restored after a propagated leg exception", 22222L, engine.maxRunningTime);
  }
}
