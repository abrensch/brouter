package btools.router;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Integration tests for the greedy sub-route round-trip planner against real
 * segment tiles (Basel, tile E5_N45 in {@code segments4/}). Skipped if the tile
 * is absent.
 */
public class GreedyRoundTripPlannerIntegrationTest {

  private File segmentDir;
  private File profileDir;

  @Before
  public void setup() {
    segmentDir = new File("../segments4");
    if (!segmentDir.exists() || !segmentDir.isDirectory()) {
      segmentDir = new File("segments4");
    }
    profileDir = new File("misc/profiles2");
    if (!profileDir.exists()) {
      profileDir = new File("../misc/profiles2");
    }
    // Classification now comes from the cost-model probe (PavedProfileProbeTest),
    // not the profile name; seed "fastbike" as paved for the gate-delegation case.
    RoundTripQualityGate.putPavedClassificationForTest("fastbike", true);
  }

  private boolean hasSegmentData() {
    return segmentDir.exists() && segmentDir.isDirectory()
      && segmentDir.listFiles() != null
      && segmentDir.listFiles().length > 0;
  }

  @Test
  public void greedyRoundTripWithSegments() {
    Assume.assumeTrue("Segment data required", hasSegmentData());

    // Basel area: 47.5581, 7.5878
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((7.5878 + 180) * 1e6);
    start.ilat = (int) ((47.5581 + 90) * 1e6);
    start.name = "start";

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx.roundTripDistance = 8000; // ~50km loop
    rctx.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;

    List<OsmNodeNamed> waypoints = new ArrayList<>();
    waypoints.add(start);

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, waypoints, rctx, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(300000);

    Assert.assertNull("No error expected", re.errorMessage);
    Assert.assertNotNull("Track should be produced", re.foundTrack);
    // Guard the loop LENGTH, not just distance > 0: the request targets a ~50km
    // loop (2*PI*8000 ~= 50.3km), so a stub or wildly wrong-length loop must
    // fail. Allow generous undershoot/overshoot ([0.3, 1.8]x) since forced
    // GREEDY can fall well short of target, but a near-zero stub still fails.
    int target = (int) Math.round(2 * Math.PI * rctx.roundTripDistance);
    Assert.assertTrue("loop length " + re.foundTrack.distance + "m is outside [0.3, 1.8]x of the "
      + target + "m target", re.foundTrack.distance > target * 0.3 && re.foundTrack.distance < target * 1.8);
  }

  @Test
  public void deterministic() {
    Assume.assumeTrue("Segment data required", hasSegmentData());

    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((7.5878 + 180) * 1e6);
    start.ilat = (int) ((47.5581 + 90) * 1e6);
    start.name = "start";

    RoutingContext rctx1 = new RoutingContext();
    rctx1.localFunction = new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx1.roundTripDistance = 5000;
    rctx1.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;
    rctx1.startDirection = 90; // East

    List<OsmNodeNamed> wp1 = new ArrayList<>();
    wp1.add(start);
    RoutingEngine re1 = new RoutingEngine(null, null, segmentDir, wp1, rctx1, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re1.quite = true;
    re1.doRun(300000);

    RoutingContext rctx2 = new RoutingContext();
    rctx2.localFunction = new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx2.roundTripDistance = 5000;
    rctx2.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;
    rctx2.startDirection = 90;

    OsmNodeNamed start2 = new OsmNodeNamed();
    start2.ilon = start.ilon;
    start2.ilat = start.ilat;
    start2.name = "start";
    List<OsmNodeNamed> wp2 = new ArrayList<>();
    wp2.add(start2);
    RoutingEngine re2 = new RoutingEngine(null, null, segmentDir, wp2, rctx2, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re2.quite = true;
    re2.doRun(300000);

    // Assert both tracks exist first, so a regression that makes greedy return
    // null on Basel fails loudly instead of passing vacuously through the old
    // null-guarded if. Compare node count too — a stronger determinism proxy
    // than int-distance alone (two distinct routes can share a distance).
    Assert.assertNotNull("first run must produce a track", re1.foundTrack);
    Assert.assertNotNull("second run must produce a track", re2.foundTrack);
    Assert.assertEquals("deterministic: same distance",
      re1.foundTrack.distance, re2.foundTrack.distance);
    Assert.assertEquals("deterministic: same node count",
      re1.foundTrack.nodes.size(), re2.foundTrack.nodes.size());
  }

}
