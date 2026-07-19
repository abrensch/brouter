package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import btools.router.roundtrip.RoundTripAlgorithm;

/**
 * Car pocket escape on real map data. The Basel downtown start
 * ({@link LoopTestRegion#BASEL}) snaps into a car pocket: a car-legal
 * component of a few dozen nodes (parking/service ways) whose only exits are
 * ways a car profile cannot use. Historically the greedy planner died there —
 * every leg out of the pocket threw {@code RoutingIslandException}, every
 * candidate got discarded, and the plan failed with "could not build any
 * loop", while the FAST tier escaped via the engine's island learning
 * (record pair, re-match, retry).
 *
 * <p>The safety property under test: the planner tiers participate in the same
 * island learning, so an explicit planner request on a fast-motorized profile
 * escapes the pocket and returns a closed loop instead of failing.
 */
public class RoundTripFastMotorIslandIntegrationTest {

  private OsmTrack carLoop(RoundTripAlgorithm algo, int lengthMeters) throws Exception {
    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    LoopTestSegments.ensureRegion(segDir, LoopTestRegion.BASEL);

    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = (int) ((7.590 + 180.0) * 1e6);
    start.ilat = (int) ((47.560 + 90.0) * 1e6);
    wps.add(start);

    RoutingContext rc = new RoutingContext();
    rc.localFunction = new File(projectDir, "misc/profiles2/car-vario.brf").getAbsolutePath();
    rc.startDirection = 0;
    rc.roundTripLength = lengthMeters;
    rc.roundTripAlgorithm = algo;

    RoutingEngine re = new RoutingEngine(null, null, segDir, wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(0);
    return re.getFoundTrack();
  }

  private void assertClosedLoop(OsmTrack track, String tier) {
    Assert.assertNotNull(tier + " must escape the car pocket and build a loop", track);
    Assert.assertTrue(tier + " loop length sane: " + track.distance, track.distance > 20000);
    int closure = track.nodes.get(0).calcDistance(track.nodes.get(track.nodes.size() - 1));
    Assert.assertTrue(tier + " loop closes (gap " + closure + "m)", closure < 100);
  }

  @Test
  public void greedyEscapesTheBaselCarPocket() throws Exception {
    assertClosedLoop(carLoop(RoundTripAlgorithm.GREEDY, 50000), "GREEDY");
  }

  @Test
  public void isoGreedyEscapesTheBaselCarPocket() throws Exception {
    assertClosedLoop(carLoop(RoundTripAlgorithm.ISO_GREEDY, 50000), "ISO_GREEDY");
  }
}
