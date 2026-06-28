package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/**
 * Shape guard for the annecy fastbike round-trip that historically self-crossed
 * through shared corridors — the Route des Diacquenods figure-eight on the N
 * route and the Rond-Point de la Contamine roundabout on the W route. Those are
 * crossings the per-node shared-edge guard exempts, so {@code countSelfIntersections}
 * reported 0 while the loop visually crossed itself.
 *
 * <p>The convex-via / loop-sweep / corridor-overlap planner work eliminated those
 * crossings: the loop is now clean in both directions (a full no-exemption
 * geometric scan finds zero crossings). This test pins that improvement — a
 * regression that brings the figure-eight back fails here.
 *
 * <p>The corridor-crossing DETECTOR itself
 * ({@link RoundTripQualityGate#countCorridorCrossings}) is exercised on synthetic
 * geometry in {@code RoundTripQualityGateTest} (side-swap through a shared run
 * counts; same-side exit, opposite-direction retrace, single-node revisit, and
 * over-bound runs do not), so this real-route test does not need to manufacture a
 * crossing to keep the detector honest — it only asserts the routed loop is clean.
 */
public class AnnecyLoopShapeTest {

  @Test
  public void annecyFastbikeLoopHasNoSelfCrossing() throws Exception {
    for (int dir : new int[]{0, 270}) {
      assertCleanLoop(dir);
    }
  }

  private void assertCleanLoop(int direction) throws Exception {
    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    File segFile = new File(segDir, "E5_N45.rd5");
    Assume.assumeTrue("segment file missing: " + segFile, segFile.exists());
    File profileFile = new File(projectDir, "misc/profiles2/fastbike.brf");

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = (int) ((6.130 + 180.0) * 1e6);
    start.ilat = (int) ((45.900 + 90.0) * 1e6);
    wplist.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = direction;
    rctx.roundTripDistance = 8000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.roundTripStrictQuality = false;

    RoutingEngine re = new RoutingEngine(null, null, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(0);
    OsmTrack track = re.getFoundTrack();
    Assume.assumeNotNull("routing produced no track (terrain limitation)", track);

    List<OsmPathElement> nodes = track.nodes;
    String ctx = "annecy fastbike dir " + direction + " (" + track.distance + "m): ";
    // countSelfIntersections folds in the corridor-crossing logic, so it is the
    // authoritative "clean loop" measure (bridge/tunnel overpasses are exempt).
    org.junit.Assert.assertEquals(ctx + "gate self-intersections",
      0, RoundTripQualityGate.countSelfIntersections(track));
    org.junit.Assert.assertEquals(ctx + "shared-corridor crossings",
      0, RoundTripQualityGate.countCorridorCrossings(nodes));
  }
}
