package btools.router;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Guard for the non-positive {@code roundTripDistance} path. A negative distance
 * set directly on the context used to flow through as a negative
 * {@code searchRadius}, which (a) placed candidates in the wrong direction and
 * (b) silently disabled the gate's distance-ratio check (skipped when
 * {@code expectedDistance = 2*PI*searchRadius <= 0}), shipping a wrong-scale loop
 * as success. The engine now floors a non-positive distance to the default
 * radius, so the result matches a plain default-radius request.
 */
public class RoundTripDistanceGuardIntegrationTest {

  private static RoutingEngine run(int roundTripDistance) {
    OsmNodeNamed start = RoundTripFixture.node("from", 8.72, 50.0);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    rc.startDirection = 90;
    rc.roundTripDistance = roundTripDistance; // set directly, bypassing the param parser
    rc.roundTripStrictQuality = false;
    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60000);
    return re;
  }

  @Test
  public void negativeRoundTripDistanceIsFlooredToDefault() {
    // The default radius (used when roundTripDistance is null/non-positive) is
    // 1500m. A negative request must produce the identical outcome — not the
    // old wrong-scale loop the disabled distance gate let through.
    RoutingEngine negative = run(-5000);
    RoutingEngine deflt = run(1500);

    OsmTrack negTrack = negative.getFoundTrack();
    OsmTrack defTrack = deflt.getFoundTrack();

    Assert.assertEquals("negative distance must match the default-radius track presence",
      defTrack == null, negTrack == null);
    if (defTrack != null) {
      Assert.assertEquals(
        "negative distance must yield the same loop as the default radius (floored, not a wrong-scale loop)",
        defTrack.distance, negTrack.distance);
    }
  }
}
