package btools.router;

import org.junit.Assert;
import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;

import java.util.function.Consumer;

/**
 * Round-trip feature/scenario coverage on the Dreieich fixture: each generation
 * strategy and the same-way-back mode must still produce a valid loop (or fail
 * cleanly). Scenarios are held to the same structural invariants as
 * {@link RoundTripInvariantTest} via {@link RoundTripFixture#assertValidLoop}.
 */
public class RoundTripScenarioTest {

  private OsmTrack routeOk(String profile, int direction, int radius, Consumer<RoutingContext> tweak) {
    RoutingEngine re = RoundTripFixture.engine(profile, direction, radius, tweak);
    RoundTripFixture.assertNoEngineErrorOrSkip(re, "routing");
    return re.getFoundTrack();
  }

  /** The explicit WAYPOINT strategy must produce valid loops for every cycling profile. */
  @Test
  public void waypointAlgorithmValidLoop() {
    for (String profile : new String[]{"trekking", "fastbike", "gravel", "mtb"}) {
      OsmTrack t = routeOk(profile, 90, 1500, rc -> rc.roundTripAlgorithm = RoundTripAlgorithm.WAYPOINT);
      RoundTripFixture.assertValidLoop(t, "waypoint_" + profile, 30.0);
    }
  }

  /** The ISOCHRONE strategy (the small-radius default) must produce valid loops. */
  @Test
  public void isochroneAlgorithmValidLoop() {
    for (String profile : new String[]{"trekking", "fastbike", "gravel", "mtb"}) {
      OsmTrack t = routeOk(profile, 90, 1500, rc -> rc.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE);
      RoundTripFixture.assertValidLoop(t, "isochrone_" + profile, 30.0);
    }
  }

  /**
   * allowSamewayback is an out-and-back: at a feasible config it returns to the origin
   * (high reuse by design, but it must close with no beelines).
   */
  @Test
  public void allowSamewaybackProducesClosedLoop() {
    OsmTrack t = routeOk("trekking", 270, 1000, rc -> rc.allowSamewayback = true);
    RoundTripFixture.assertValidLoop(t, "samewayback", 100.0);
  }

  /**
   * Regression: allowSamewayback used to have one leg deleted by back-and-forth removal
   * at some directions (the two legs look like an overlap), leaving a one-way stub that
   * did not close. It must now produce a closed out-and-back regardless of direction.
   */
  @Test
  public void allowSamewaybackClosesAtConstrainedDirection() {
    OsmTrack t = routeOk("trekking", 90, 1000, rc -> rc.allowSamewayback = true);
    RoundTripFixture.assertValidLoop(t, "samewayback_dir90", 100.0);
  }

  /** roundTripLength sets the total loop distance directly and must yield a valid loop. */
  @Test
  public void roundTripLengthParameterValidLoop() {
    // 6 km total loop ~= 955 m radius.
    OsmTrack t = routeOk("trekking", 90, 1000, rc -> rc.roundTripLength = 6000);
    RoundTripFixture.assertValidLoop(t, "length6km", 30.0);
  }

  /**
   * roundTripLength must take precedence over roundTripDistance (RoutingContext Javadoc).
   * The radius arg sets roundTripDistance=3000 (which alone would give a ~18.8 km loop);
   * roundTripLength=6000 must win and produce a ~6 km loop instead. A clearly different
   * radius makes the two distinguishable, unlike values that happen to coincide.
   */
  @Test
  public void roundTripLengthTakesPrecedenceOverRoundTripDistance() {
    OsmTrack t = routeOk("trekking", 90, 3000, rc -> rc.roundTripLength = 6000);
    RoundTripFixture.assertValidLoop(t, "length6km_precedence", 30.0);
    // ~6 km (from roundTripLength), not ~18.8 km (2*PI*3000 from roundTripDistance).
    Assert.assertTrue("roundTripLength must take precedence over roundTripDistance: expected "
      + "~6km loop but got " + t.distance + "m", t.distance < 12000);
  }

  /** An explicit roundTripPoints waypoint count must still yield a valid loop. */
  @Test
  public void roundTripPointsParameterValidLoop() {
    OsmTrack t = routeOk("trekking", 90, 1500, rc -> rc.roundTripPoints = 8);
    RoundTripFixture.assertValidLoop(t, "points8", 30.0);
  }

  /** A round-trip through a user via point must keep the via and still be a valid loop. */
  @Test
  public void userViaPreservedInValidLoop() {
    OsmNodeNamed via = RoundTripFixture.node("via1", 8.722, 50.001); // known-good fixture road
    RoutingEngine re = RoundTripFixture.engine("trekking", 0, 1000, rc -> { }, via);
    RoundTripFixture.assertNoEngineErrorOrSkip(re, "user-via round-trip");

    OsmTrack track = re.getFoundTrack();
    RoundTripFixture.assertValidLoop(track, "userVia", 30.0);

    boolean foundVia = false;
    Assert.assertNotNull("no matched waypoints", track.matchedWaypoints);
    for (MatchedWaypoint mwp : track.matchedWaypoints) {
      if ("via1".equals(mwp.name)) foundVia = true;
    }
    Assert.assertTrue("user via1 must be preserved in the loop", foundVia);
  }
}
