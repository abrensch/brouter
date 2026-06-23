package btools.router;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;

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
      OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                         OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
        throw toThrow;
      }
    };
  }

  private static OsmTrack routeOneLeg(RoutingEngine engine) {
    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(engine, new RoundTripCandidateProvider.RadialCandidateProvider());
    return planner.timedFindTrack("test-leg",
      new MatchedWaypoint(), new MatchedWaypoint(), null,
      System.currentTimeMillis() + 60_000L);
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
