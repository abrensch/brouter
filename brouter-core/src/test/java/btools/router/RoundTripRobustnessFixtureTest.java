package btools.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * Real-routing integration tests against the bundled Dreieich fixture (the same
 * tile {@link RoundTripFixture} and {@link RoutingEngineTest} use, so these run
 * in CI without downloads).
 *
 * <p>Where {@link RoundTripQualityFixtureTest} pins loop <em>quality</em>, this
 * suite pins <em>robustness</em>: every round-trip algorithm, across a stress
 * range of inputs, must always reach a well-defined outcome — a usable track XOR
 * a clean error message — and must never let an exception escape the engine.
 * This guards the error-handling paths exercised by the planner/competition
 * (unroutable-leg fallback, candidate-failure capture, malformed-parameter
 * tolerance) at the integration level, complementing their focused unit tests.
 */
public class RoundTripRobustnessFixtureTest {

  private static final String GRAVEL = "gravel";
  private static final int[] DIRECTIONS = {0, 90, 180, 270};
  private static final RoundTripAlgorithm[] ALGOS = {
    RoundTripAlgorithm.AUTO, RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY,
    RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.ISOCHRONE};

  /**
   * Every algorithm, in every direction, at small and large radii, must finish
   * with a defined outcome and never throw. When a track is produced it must be
   * a non-degenerate loop; otherwise a non-empty error message must explain why.
   */
  @Test
  public void everyAlgorithmAlwaysReachesADefinedOutcomeWithoutThrowing() {
    for (RoundTripAlgorithm algo : ALGOS) {
      for (int dir : DIRECTIONS) {
        for (int radius : new int[]{800, 2500}) {
          String label = algo + " dir=" + dir + " r=" + radius;
          RoutingEngine re;
          try {
            re = RoundTripFixture.engine(GRAVEL, dir, radius, rc -> rc.roundTripAlgorithm = algo);
          } catch (RuntimeException e) {
            Assert.fail(label + ": engine threw " + e.getClass().getSimpleName()
              + ": " + e.getMessage());
            return;
          }
          OsmTrack track = re.getFoundTrack();
          String err = re.getErrorMessage();

          // Defined outcome: exactly one of (track, error).
          Assert.assertTrue(label + ": must produce a track XOR an error (track="
              + (track != null) + ", err=" + err + ")",
            (track != null) ^ (err != null && !err.isEmpty()));

          if (track != null) {
            Assert.assertTrue(label + ": a produced loop must be non-degenerate ("
                + track.nodes.size() + " nodes)", track.nodes.size() > 2);
            Assert.assertTrue(label + ": a produced loop must have positive length",
              track.distance > 0);
          }
        }
      }
    }
  }

  /**
   * A malformed {@code roundTripAlgorithm} URL value must be tolerated end-to-end:
   * the parser falls back to AUTO and the engine still produces a loop, rather
   * than throwing out of parameter parsing.
   */
  @Test
  public void malformedAlgorithmParamFallsBackToAutoAndStillRoutes() {
    RoutingEngine re = RoundTripFixture.engine(GRAVEL, 90, 1000, rc -> {
      // Seed a non-default value so the assertion proves the parser actually
      // reset the malformed input — rc.roundTripAlgorithm defaults to AUTO, so
      // without this seed the assertEquals would pass even if the parser ignored
      // the param entirely (the original tautology).
      rc.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;
      Map<String, String> params = new LinkedHashMap<>();
      params.put("roundTripAlgorithm", "definitely-not-an-algorithm");
      new RoutingParamCollector().setParams(rc, null, params);
      Assert.assertEquals("unknown algorithm must reset to AUTO",
        RoundTripAlgorithm.AUTO, rc.roundTripAlgorithm);
    });
    Assert.assertNull("AUTO must complete: " + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("AUTO must still produce a loop after a bad algo param",
      re.getFoundTrack());
  }

  /**
   * A start far from any road must be handled gracefully — a clean error (or a
   * recovered loop), never an uncaught exception. This is the deterministic,
   * tile-independent guard for the unroutable-start path (the integration
   * analog of an islanded leg); the exact outcome may vary with the tile, but
   * "no exception, defined outcome" must always hold.
   */
  @Test
  public void offNetworkStartIsHandledGracefully() {
    // ~0.2 deg (>10 km) north-east of the Dreieich origin — off the tile's road
    // network but still a sane coordinate.
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.92, 50.20));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(GRAVEL).getAbsolutePath();
    rc.startDirection = 90;
    rc.roundTripDistance = 1000;

    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(),
      wps, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    try {
      re.doRun(0);
    } catch (RuntimeException e) {
      Assert.fail("off-network start must not throw: " + e.getClass().getSimpleName()
        + ": " + e.getMessage());
      return;
    }
    boolean haveTrack = re.getFoundTrack() != null;
    boolean haveError = re.getErrorMessage() != null && !re.getErrorMessage().isEmpty();
    Assert.assertTrue("off-network start must yield a track XOR a clean error",
      haveTrack ^ haveError);
  }
}
