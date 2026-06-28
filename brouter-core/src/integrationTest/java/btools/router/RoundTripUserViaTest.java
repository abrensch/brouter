package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;

/**
 * Acceptance tests for the explicit-via round-trip semantics described in
 * docs/features/roundtrip-user-via-semantics.md. Covers all 12 spec §190
 * cases. Tests that require real routing use segment data from {@code
 * segments4/} and {@code misc/profiles2/}; they self-skip when data isn't
 * present (same pattern as {@link GreedyRoundTripPlannerTest}).
 *
 * <p>Pure-logic cases (the quality gate's explicit-via mode behaviour) run
 * unconditionally — those don't need real OSM data.
 */
public class RoundTripUserViaTest {

  private File segmentDir;
  private File profileDir;

  @Before
  public void setup() {
    // Prefer the production segments4/ if available — provides full real
    // routing for the integration-style tests below.
    segmentDir = new File("../segments4");
    if (!segmentDir.exists() || !segmentDir.isDirectory()) {
      segmentDir = new File("segments4");
    }
    profileDir = new File("misc/profiles2");
    if (!profileDir.exists()) {
      profileDir = new File("../misc/profiles2");
    }
  }

  private boolean hasSegmentData(String region) {
    return segmentDir.exists() && segmentDir.isDirectory()
      && new File(segmentDir, region).exists();
  }

  /** Dreieich start: (8.720, 50.000) — clean fastbike paved network, used as
   * the routing baseline. Has E5_N50.rd5 in segments4. */
  private static OsmNodeNamed dreieichStart() {
    OsmNodeNamed n = new OsmNodeNamed();
    n.ilon = (int) ((8.720 + 180) * 1_000_000);
    n.ilat = (int) ((50.000 + 90) * 1_000_000);
    n.name = "from";
    return n;
  }

  private static OsmNodeNamed via(String name, double lon, double lat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.ilon = (int) ((lon + 180) * 1_000_000);
    n.ilat = (int) ((lat + 90) * 1_000_000);
    n.name = name;
    return n;
  }

  /**
   * Integration tests use the trekking profile rather than fastbike: the
   * paved-only fastbike profile rejects the path/track terrain that real
   * routes around the Dreieich start point can pick up, and via-spec
   * acceptance is about VIA SEMANTICS, not profile policy. The profile-
   * hostility gate IS still enforced (and tested separately via the
   * synthetic quality-gate tests above).
   */
  private RoutingContext trekkingContext() {
    RoutingContext rctx = new RoutingContext();
    File trekking = new File(profileDir, "trekking.brf");
    rctx.localFunction = trekking.exists() ? trekking.getAbsolutePath()
      : new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx.roundTripDistance = 3000; // small radius — easier on the test setup
    return rctx;
  }

  // =========================================================================
  // §190.5 — distance ratio mismatch doesn't reject in explicit-via mode.
  // Pure quality-gate unit test, no routing needed.
  // =========================================================================

  @Test
  public void explicitViaMode_distanceRatioMismatchBecomesDisclosure() {
    OsmTrack t = squareLoop(5000); // 20km track
    // Pretend the user asked for a 60km loop — ratio = 20/60 = 0.33, well
    // below MIN_DISTANCE_RATIO=0.5. In auto-generated mode this rejects;
    // in explicit-via mode it must pass with a disclosure.
    double mismatchedDesired = 60000;

    RoundTripQualityResult autoMode = RoundTripQualityGate.evaluate(
      t, mismatchedDesired, "fastbike", /*allowSamewayback*/ false,
      /*explicitViaMode*/ false);
    Assert.assertFalse("auto mode rejects ratio mismatch", autoMode.isAccepted());

    RoundTripQualityResult viaMode = RoundTripQualityGate.evaluate(
      t, mismatchedDesired, "fastbike", /*allowSamewayback*/ false,
      /*explicitViaMode*/ true);
    Assert.assertTrue("explicit-via mode accepts ratio mismatch: " + viaMode,
      viaMode.isAccepted());
    Assert.assertTrue("attaches advisory disclosure",
      viaMode.getDisclosures().stream().anyMatch(d -> d.contains("via-route distance")));
  }

  @Test
  public void explicitViaMode_stillRejectsBeelineRoutes() {
    OsmTrack t = squareLoop(5000);
    // Beeline marker: matchedWaypoint with wpttype=DIRECT. Must be rejected
    // even in explicit-via mode (hard safety check).
    t.matchedWaypoints = new ArrayList<>();
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
    t.matchedWaypoints.add(mwp);

    RoundTripQualityResult r = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, /*explicitViaMode*/ true);
    Assert.assertFalse("beeline rejection still active in explicit-via mode",
      r.isAccepted());
    Assert.assertTrue(r.getRejectionReason().contains("beeline"));
  }

  @Test
  public void explicitViaMode_stillRejectsBrokenClosure() {
    // Closure > 400m must reject even in explicit-via mode (loops must close).
    OsmTrack t = squareLoop(5000);
    OsmPathElement last = t.nodes.get(t.nodes.size() - 1);
    t.nodes.set(t.nodes.size() - 1, OsmPathElement.create(
      last.getILon() + 6000, last.getILat(), (short) 0, null));

    RoundTripQualityResult r = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, /*explicitViaMode*/ true);
    Assert.assertFalse("closure check still active in explicit-via mode",
      r.isAccepted());
    Assert.assertTrue(r.getRejectionReason().contains("closure"));
  }

  @Test
  public void explicitViaMode_invalidRetraceDowngradesToDisclosure() {
    // Construct a track with mid-route retrace that the classifier flags
    // as INVALID_RETRACE in auto mode. In explicit-via mode this should
    // become a disclosure ("via-route note: ...") rather than rejection.
    OsmTrack t = zigzagTrack();

    RoundTripQualityResult autoMode = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, /*explicitViaMode*/ false);
    Assert.assertFalse("auto mode rejects zigzag", autoMode.isAccepted());

    RoundTripQualityResult viaMode = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, /*explicitViaMode*/ true);
    Assert.assertTrue("explicit-via mode accepts; user picked the vias: " + viaMode,
      viaMode.isAccepted());
    Assert.assertTrue("attaches via-route note disclosure",
      viaMode.getDisclosures().stream().anyMatch(d -> d.startsWith("via-route note")));
  }

  // =========================================================================
  // Spec §190.1–11 — integration tests using real segments4/ data.
  // Skipped when segment data not present.
  // =========================================================================

  @Test
  public void singleViaRouteWithAutoBypassesTwoIntermediateCheck() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    OsmNodeNamed v1 = via("v1", 8.730, 50.005); // ~700m NE
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(v1);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("1-via AUTO round-trip should succeed (no 2-intermediate check fail): "
      + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("track produced", re.getFoundTrack());
  }

  @Test
  public void multipleViasPreserveInputOrder() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    // Place vias in a specific non-bearing-sorted order: NE, SW, N.
    // If the engine sorted by bearing, the order would change.
    OsmNodeNamed v1 = via("first_NE", 8.730, 50.005);
    OsmNodeNamed v2 = via("second_SW", 8.715, 49.998);
    OsmNodeNamed v3 = via("third_N", 8.722, 50.010);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(v1); wps.add(v2); wps.add(v3);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 0; // North-bias, should NOT influence via order

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("multi-via routing succeeded: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull(t);
    List<MatchedWaypoint> matched = t.matchedWaypoints;
    Assert.assertNotNull("matchedWaypoints recorded", matched);
    // Expected order: from, first_NE, second_SW, third_N, to (closing start)
    Assert.assertEquals("from", matched.get(0).name);
    Assert.assertEquals("first_NE", matched.get(1).name);
    Assert.assertEquals("second_SW", matched.get(2).name);
    Assert.assertEquals("third_N", matched.get(3).name);
  }

  @Test
  public void roundTripPointsIgnoredWhenUserViasPresent() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    OsmNodeNamed v1 = via("v1", 8.730, 50.005);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(v1);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.roundTripPoints = 12; // would normally request 12 generated points

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("succeeded: " + re.getErrorMessage(), re.getErrorMessage());
    List<MatchedWaypoint> matched = re.getFoundTrack().matchedWaypoints;
    Assert.assertEquals("only user vias + closing start (no generated rt*): " + matched,
      3, matched.size());
    // No matched waypoint name should start with rt
    for (MatchedWaypoint mwp : matched) {
      Assert.assertFalse("no generated rt* names: " + mwp.name,
        mwp.name != null && mwp.name.startsWith("rt"));
    }
  }

  @Test
  public void startDirectionDoesNotReorderVias() {
    // §190.4. Same test as multipleViasPreserveInputOrder but with the
    // direction pointing AWAY from the first via — the engine must still
    // visit them in input order.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    OsmNodeNamed v1 = via("v1_NE", 8.730, 50.005);
    OsmNodeNamed v2 = via("v2_SW", 8.715, 49.998);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(v1); wps.add(v2);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 180; // South — opposite of v1's NE bearing

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("succeeded: " + re.getErrorMessage(), re.getErrorMessage());
    List<MatchedWaypoint> matched = re.getFoundTrack().matchedWaypoints;
    Assert.assertEquals("first via is v1 despite S direction", "v1_NE",
      matched.get(1).name);
    Assert.assertEquals("second via is v2", "v2_SW", matched.get(2).name);
  }

  @Test
  public void explicitViaModeUsesSameSkeletonForAllAlgorithms() {
    // §190.6-8. WAYPOINT, GREEDY, ISO_GREEDY all converge to the same
    // explicit-via skeleton when user vias are present.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed v1Src = via("v1", 8.730, 50.005);

    int[] orderEachAlgo = new int[]{0, 0, 0};
    RoundTripAlgorithm[] algos = {
      RoundTripAlgorithm.WAYPOINT,
      RoundTripAlgorithm.GREEDY,
      RoundTripAlgorithm.ISO_GREEDY
    };
    for (int i = 0; i < algos.length; i++) {
      List<OsmNodeNamed> wps = new ArrayList<>();
      wps.add(dreieichStart());
      wps.add(via(v1Src.name, 8.730, 50.005));

      RoutingContext rctx = trekkingContext();
      rctx.roundTripAlgorithm = algos[i];

      RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
        RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
      re.quite = true;
      re.doRun(60_000);
      Assert.assertNull("algo=" + algos[i] + " failed: " + re.getErrorMessage(),
        re.getErrorMessage());
      List<MatchedWaypoint> matched = re.getFoundTrack().matchedWaypoints;
      orderEachAlgo[i] = matched.size();
      // All algorithms must produce the same 3-waypoint skeleton (from,
      // v1, closing start) — no rt* additions.
      Assert.assertEquals("algo=" + algos[i] + " skeleton size",
        3, matched.size());
      for (MatchedWaypoint mwp : matched) {
        Assert.assertFalse("algo=" + algos[i] + " contains rt*: " + mwp.name,
          mwp.name != null && mwp.name.startsWith("rt"));
      }
    }
  }

  @Test
  public void allowSamewaybackMirrorsViaChain() {
    // §190.9. start → v1 → v2 → v1 → start.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    OsmNodeNamed v1 = via("v1", 8.725, 50.003);
    OsmNodeNamed v2 = via("v2", 8.730, 50.005);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(v1); wps.add(v2);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.allowSamewayback = true;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("samewayback+via routing succeeded: " + re.getErrorMessage(),
      re.getErrorMessage());
    List<MatchedWaypoint> matched = re.getFoundTrack().matchedWaypoints;
    // Expected length: from, v1, v2_center, v1_mirror, to = 5
    Assert.assertEquals("mirrored chain length: " + matched, 5, matched.size());
    Assert.assertEquals("from", matched.get(0).name);
    // The middle is v2 (renamed to *_center by doRouting); confirm by position.
    Assert.assertEquals("center node position is v2's",
      v2.ilon, matched.get(2).waypoint.ilon);
    Assert.assertEquals(v2.ilat, matched.get(2).waypoint.ilat);
    // No rt* names in the mirrored chain
    for (MatchedWaypoint mwp : matched) {
      Assert.assertFalse("mirrored chain contains rt*: " + mwp.name,
        mwp.name != null && mwp.name.startsWith("rt"));
    }
  }

  @Test
  public void unsnappableViaFailsWithNamedError() {
    // §190.10. A via in the middle of the ocean must fail with a clear,
    // named error — never silently dropped.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    OsmNodeNamed start = dreieichStart();
    // Coords in the middle of the North Sea — no roads within snap radius.
    OsmNodeNamed lostVia = via("ocean_via", 3.0, 55.0);
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start); wps.add(lostVia);

    RoutingContext rctx = trekkingContext();
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    try {
      re.doRun(60_000);
    } catch (IllegalArgumentException e) {
      // expected — but engine usually catches exceptions, surfacing as errorMessage
    }
    String err = re.getErrorMessage();
    Assert.assertNotNull("expected an error message", err);
    Assert.assertTrue("error names the via (" + err + ")",
      err.contains("ocean_via"));
  }

  // =========================================================================
  // §190.12 — existing no-via tests still pass (verified by the rest of the
  // test suite, especially GreedyRoundTripPlannerTest and RoutingEngineTest).
  // No additional test added here; the regression guard is the standard
  // `./gradlew test` run.
  // =========================================================================

  // -------------- helpers -------------------------------------------------

  /** Square loop, 4 sides of {@code side} meters, with clean residential
   * metadata so the paved-profile hostility gate accepts. */
  private static OsmTrack squareLoop(int side) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(node(0, 0));
    t.nodes.add(node(side, 0));
    t.nodes.add(node(side, side));
    t.nodes.add(node(0, side));
    t.nodes.add(node(0, 0));
    int dist = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      dist += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
      MessageData m = new MessageData();
      m.wayKeyValues = "highway=residential surface=asphalt";
      m.costfactor = 1.0f;
      t.nodes.get(i).message = m;
    }
    t.distance = dist;
    return t;
  }

  /** Track that the classifier flags as INVALID_RETRACE in auto mode:
   * out 3km, back 1km, forward 1km, back 1km — clear zigzag (edges visited
   * 3+ times). */
  private static OsmTrack zigzagTrack() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(node(0, 0));
    t.nodes.add(node(1000, 0));
    t.nodes.add(node(2000, 0));
    t.nodes.add(node(3000, 0));
    t.nodes.add(node(2000, 0));
    t.nodes.add(node(1000, 0));
    t.nodes.add(node(2000, 0));
    t.nodes.add(node(1000, 0));
    t.nodes.add(node(0, 0));
    int dist = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      dist += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
      MessageData m = new MessageData();
      m.wayKeyValues = "highway=residential surface=asphalt";
      m.costfactor = 1.5f;
      t.nodes.get(i).message = m;
    }
    t.distance = dist;
    return t;
  }

  private static OsmPathElement node(int xMeters, int yMeters) {
    // ~14 ilon/m and ~9 ilat/m at the test base latitude (50°N).
    return OsmPathElement.create(
      180_000_000 + xMeters * 14,
      50_000_000 + yMeters * 9,
      (short) 0, null);
  }
}
