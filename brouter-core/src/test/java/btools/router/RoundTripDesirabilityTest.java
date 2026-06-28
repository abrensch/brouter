package btools.router;

import org.junit.Assert;
import org.junit.Test;

/**
 * End-to-end wiring test for the experimental round-trip desirability heatmap
 * (issue #15), run against the bundled Dreieich fixture (no downloads).
 *
 * <p>The unit-level selection logic lives in {@link DesirabilityCandidateProviderTest};
 * this suite proves the feature is plumbed through the engine correctly:
 * <ul>
 *   <li>with the flag <b>off</b>, the desirability grid is never built (true no-op);</li>
 *   <li>with the flag <b>on</b>, the GREEDY round-trip actually accumulates the grid
 *       (so the {@link DesirabilityCandidateProvider} branch is reached), and still
 *       produces a valid, closed loop of roughly the requested size — i.e. the strong
 *       {@code DESIR_WEIGHT} reward does not break loop formation.</li>
 * </ul>
 *
 * <p>It deliberately does <em>not</em> assert that the resulting route differs from the
 * baseline: the ~3 km fixture is too uniform for desirability to re-rank candidates, so
 * the route is identical there. The measured routing effect lives on real-geography
 * tiles (see issue #15); the wiring guarantee is what CI can assert cheaply.
 */
public class RoundTripDesirabilityTest {

  private static final String PROFILE = "gravel";
  private static final int EAST = 90;
  private static final int RADIUS = 1000;
  private static final int MAX_CLOSURE_M = 100;

  @Test
  public void flagOffNeverBuildsTheGrid() {
    RoutingEngine re = RoundTripFixture.engine(PROFILE, EAST, RADIUS, rc -> {
      rc.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;
      rc.roundTripStrictQuality = false;
      // roundTripDesirability left at its default (false)
    });
    // Guard against a vacuous pass: confirm routing actually ran (and reached the
    // expansion) before asserting the grid stayed empty.
    Assert.assertNull("engine must route without error when the flag is off: "
      + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("engine produced a track when the flag is off", re.getFoundTrack());
    Assert.assertTrue("grid must stay empty when the flag is off (no-op)",
      re.desirabilityGrid.isEmpty());
  }

  @Test
  public void flagOnBuildsGridAndStillFormsAValidLoop() {
    RoutingEngine re = RoundTripFixture.engine(PROFILE, EAST, RADIUS, rc -> {
      rc.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;
      rc.roundTripDesirability = true;
      rc.roundTripStrictQuality = false;
    });

    // The flag-on path was exercised end-to-end: the expansion populated the grid,
    // which is the precondition for buildCandidateProvider to pick the desirability provider.
    Assert.assertFalse("flag-on GREEDY must populate the desirability grid",
      re.desirabilityGrid.isEmpty());

    // A valid loop still forms: the strong desirability reward biases waypoint
    // selection but loop-closure still pulls the return leg back to the start.
    Assert.assertNull("flag-on GREEDY routed without error: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("flag-on GREEDY produced a track", track);
    Assert.assertTrue("loop is non-degenerate", track.nodes.size() >= 10);
    int closing = track.nodes.get(0).calcDistance(track.nodes.get(track.nodes.size() - 1));
    Assert.assertTrue("loop closes near the origin, gap " + closing + "m", closing <= MAX_CLOSURE_M);
    // Length is in the right ballpark for the radius (loop ≈ 2π·radius); generous bounds.
    Assert.assertTrue("loop length plausible for radius: " + track.distance,
      track.distance > RADIUS && track.distance < 12 * RADIUS);
  }
}
