package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.util.CheapRuler;
import btools.router.roundtrip.FrontierAxis;
import btools.router.roundtrip.PlacementGeometry;
import btools.router.roundtrip.RoundTripAlgorithm;

public class RoutingEngineTest {
  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  private File projectDir;

  @Before
  public void before() throws Exception {
    // Gradle sets cwd to the module directory (brouter-core/)
    projectDir = new File(".").getCanonicalFile().getParentFile();
  }

  @Test
  public void routeCrossingSegmentBorder() throws Exception {
    // Copy reference track into temp dir so engine finds it for comparison and produces an alternative
    copyResourceToDir("/testtrack0.gpx", outputDir.getRoot());
    String msg = calcRoute(8.720897, 50.002515, 8.723658, 49.997510, outputDir.getRoot(), "testtrack", new RoutingContext());
    Assert.assertNull("routing failed: " + msg, msg);

    File a1 = new File(outputDir.getRoot(), "testtrack1.gpx");
    Assert.assertTrue("result content mismatch", a1.exists());
  }

  // Pins the historic node-membership refTrack anti-reuse penalty for GENERAL
  // (non-round-trip) alternative routing. The round-trip edge-membership penalty
  // (OsmPath: containsTraveledSegment) is gated behind RoutingContext.roundTrip,
  // so a plain alternative must reuse the OLD both-endpoints containsNode test and
  // its output must be unchanged. When that gate is later lifted (made global),
  // re-capture this golden: any change in cost/node-count is the alternative-route
  // delta to review before flipping the default.
  @Test
  public void generalAlternativeRefTrackPenaltyIsHistoric() throws Exception {
    copyResourceToDir("/testtrack0.gpx", outputDir.getRoot());
    RoutingEngine re = calcRouteEngine(8.720897, 50.002515, 8.723658, 49.997510,
      outputDir.getRoot(), "testtrack", new RoutingContext());
    Assert.assertNull("routing failed: " + re.getErrorMessage(), re.getErrorMessage());

    // The gate is real, not a no-op: a ROUTING-mode engine leaves roundTrip=false,
    // while a round-trip engine's constructor (engineMode=4) turns it on. Asserting
    // only the false side would just restate the field default; the true side below
    // verifies the constructor actually drives the node-vs-edge membership switch.
    Assert.assertFalse("ROUTING-mode engine must not enable the edge-membership gate",
      re.routingContext.roundTrip);
    RoutingContext rtCtx = new RoutingContext();
    rtCtx.startDirection = 0;
    rtCtx.roundTripDistance = 1000;
    RoutingEngine rtEngine = calcRoundTrip(8.720, 50.000, "rtGateCheck", rtCtx);
    Assert.assertTrue("round-trip engine constructor must enable the edge-membership gate",
      rtEngine.routingContext.roundTrip);

    OsmTrack alt = re.getFoundTrack();
    Assert.assertNotNull("alternative track expected", alt);
    Assert.assertTrue("alternative should be a real track", alt.nodes.size() > 2);
    Assert.assertEquals("alternative-route cost (historic node-membership refTrack penalty)",
      GOLDEN_ALT_COST, alt.cost);
  }

  // Captured 2026-06-22 with the refTrack edge-membership change gated behind
  // RoutingContext.roundTrip (i.e. the historic containsNode penalty for general
  // routing). See generalAlternativeRefTrackPenaltyIsHistoric.
  private static final int GOLDEN_ALT_COST = 1327;

  @Test
  public void routeDestinationPointFarOff() {
    String msg = calcRoute(8.720897, 50.002515, 16.723658, 49.997510, outputDir.getRoot(), "notrack", new RoutingContext());
    Assert.assertTrue(msg, msg != null && msg.contains("not found"));
  }

  // check that a (short) route and an alternative route can be computed
  // while explicitely overriding a routing profile parameter
  @Test
  public void overrideParam() {
    // 1st route computing (with param) — writes paramTrack0.gpx
    RoutingContext rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    String msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, outputDir.getRoot(), "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 1st route): " + msg, msg);
    // 2nd route computing (same from/to & same param) — finds paramTrack0.gpx, produces alternative
    rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, outputDir.getRoot(), "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 2nd route): " + msg, msg);

    File trackFile = new File(outputDir.getRoot(), "paramTrack1.gpx");
    Assert.assertTrue("result content mismatch", trackFile.exists());
  }

  // Round-trip from center of test area should produce a valid loop
  @Test
  public void roundTripBasicLoop() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0; // north
    rctx.roundTripDistance = 1000;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtBasic", rctx);

    RoundTripFixture.assertNoEngineErrorOrSkip(re, "round-trip routing");
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("round-trip should produce a track", track);
    Assert.assertTrue("round-trip track should have nodes", track.nodes.size() > 2);

    // track should start and end near the origin
    OsmPathElement first = track.nodes.get(0);
    OsmPathElement last = track.nodes.get(track.nodes.size() - 1);
    int closingDistance = first.calcDistance(last);
    Assert.assertTrue("loop should close near origin, but gap is " + closingDistance + "m",
      closingDistance < 500);

    // verify no micro-detours remain: no node should appear twice within 350m of track distance
    Map<Long, Integer> lastSeen = new HashMap<>();
    int cumDist = 0;
    for (int i = 0; i < track.nodes.size(); i++) {
      if (i > 0) cumDist += track.nodes.get(i).calcDistance(track.nodes.get(i - 1));
      long id = track.nodes.get(i).getIdFromPos();
      Integer prevDist = lastSeen.put(id, cumDist);
      if (prevDist != null) {
        int loopDist = cumDist - prevDist;
        Assert.assertTrue("micro-detour found: node " + id + " revisited after " + loopDist + "m",
          loopDist > 350);
      }
    }
  }

  // Round-trip with large radius pushing waypoints outside road data area
  // Near the data edge the generated waypoints are filtered; if fewer than the loop
  // minimum (2 intermediate) remain, the engine must fail cleanly rather than emit a
  // degenerate single-waypoint out-and-back.
  @Test
  public void roundTripFailsCleanlyWhenDataEdgeFiltersWaypoints() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 90; // east, dreieich data runs out quickly
    rctx.roundTripDistance = 5000;
    // This test asserts the HARD-reject contract at the data edge; the engine
    // now defaults to lenient (return quality-failed routes with a warning).
    rctx.roundTripStrictQuality = true;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtEdge", rctx);

    Assert.assertNotNull("expected a clean failure at the data edge", re.getErrorMessage());
    // AUTO runs a candidate competition (ISO_GREEDY → GREEDY → WAYPOINT →
    // ISOCHRONE fallback); at the data edge every candidate fails, so the
    // surfaced message is the competition wrapper rather than one specific
    // candidate's diagnosis. The contract under test is the clean failure
    // itself: an explained rejection and no degenerate out-and-back track.
    Assert.assertTrue("error should report the AUTO competition failure: " + re.getErrorMessage(),
      re.getErrorMessage().contains("no acceptable route"));
    Assert.assertNull("no track should be returned on failure", re.getFoundTrack());
  }

  // allowSamewayback round-trip should produce valid out-and-back route
  @Test
  public void roundTripAllowSamewayback() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0; // north
    rctx.roundTripDistance = 1000;
    rctx.allowSamewayback = true;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtSameway", rctx);

    RoundTripFixture.assertNoEngineErrorOrSkip(re, "allowSamewayback routing");
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("allowSamewayback should produce a track", track);
    Assert.assertTrue("track should have significant length", track.distance > 200);
  }

  // No-beeline invariant: successful round-trip routes from any algorithm
  // (waypoint, isochrone, samewayback) must not contain generated DIRECT
  // waypoints or direct_segment messages, even with add_beeline enabled.
  @Test
  public void roundTripWaypointNoBeelineWithAddBeeline() {
    assertRoundTripHasNoBeeline("rtWpNoBl", false, false);
  }

  @Test
  public void roundTripIsochroneNoBeelineWithAddBeeline() {
    assertRoundTripHasNoBeeline("rtIsoNoBl", true, false);
  }

  @Test
  public void roundTripSamewaybackNoBeelineWithAddBeeline() {
    assertRoundTripHasNoBeeline("rtSwbNoBl", false, true);
  }

  private void assertRoundTripHasNoBeeline(String trackname, boolean isochrone, boolean samewayback) {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 1000;
    // Enable dynamic beeline insertion. With add_beeline=true the routing
    // engine would normally splice WAYPOINT_TYPE_DIRECT segments when a
    // waypoint can't be matched within the catching range — round-trip code
    // must defeat this by snapping points to the road graph beforehand.
    rctx.buildBeelineOnRange = true;
    if (isochrone) {
      rctx.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;
    }
    rctx.allowSamewayback = samewayback;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, trackname, rctx);

    RoundTripFixture.assertNoEngineErrorOrSkip(re, trackname);
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull(trackname + " should produce a track", track);

    if (track.matchedWaypoints != null) {
      for (MatchedWaypoint mwp : track.matchedWaypoints) {
        Assert.assertNotEquals(trackname + ": no DIRECT waypoint allowed (" + mwp.name + ")",
          MatchedWaypoint.WAYPOINT_TYPE_DIRECT, mwp.wpttype);
      }
    }
    if (track.messageList != null) {
      for (String msg : track.messageList) {
        Assert.assertFalse(trackname + ": message must not contain direct_segment: " + msg,
          msg != null && msg.contains("direct_segment="));
      }
    }
  }

  // Unsnappable user via must surface as a clear error, not be silently dropped.
  @Test
  public void unsnappableUserViaFailsClearly() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 1000;

    RoutingEngine re = calcRoundTripWithVias(8.720, 50.000, "rtUnsnap", rctx,
      new double[][]{{9.5, 50.0}}); // far outside test data

    Assert.assertNotNull("expected an error for unsnappable user via", re.getErrorMessage());
    Assert.assertTrue("error must mention the user waypoint: " + re.getErrorMessage(),
      re.getErrorMessage().contains("user waypoint"));
  }

  // GREEDY + user vias falls back to WAYPOINT and preserves the user vias.
  @Test
  public void greedyWithUserViaFallsBackAndPreservesVia() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 1000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;

    RoutingEngine re = calcRoundTripWithVias(8.720, 50.000, "rtGreedyVia", rctx,
      new double[][]{{8.722, 50.001}});

    RoundTripFixture.assertNoEngineErrorOrSkip(re, "greedy+via fallback");
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("greedy+via should produce a track", track);
    boolean foundVia = false;
    if (track.matchedWaypoints != null) {
      for (MatchedWaypoint mwp : track.matchedWaypoints) {
        if ("via1".equals(mwp.name)) foundVia = true;
        Assert.assertNotEquals("greedy+via fallback must not produce DIRECT (" + mwp.name + ")",
          MatchedWaypoint.WAYPOINT_TYPE_DIRECT, mwp.wpttype);
      }
    }
    Assert.assertTrue("user via1 must be present in matched waypoints", foundVia);
  }

  private RoutingEngine calcRoundTripWithVias(double lon, double lat, String trackname,
                                              RoutingContext rctx, double[][] vias) {
    String out = new File(outputDir.getRoot(), trackname).getAbsolutePath();
    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    start.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    wplist.add(start);
    for (int i = 0; i < vias.length; i++) {
      OsmNodeNamed via = new OsmNodeNamed();
      via.name = "via" + (i + 1);
      via.ilon = 180000000 + (int) (vias[i][0] * 1000000 + 0.5);
      via.ilat = 90000000 + (int) (vias[i][1] * 1000000 + 0.5);
      wplist.add(via);
    }
    rctx.localFunction = profileFile().getAbsolutePath();
    RoutingEngine re = new RoutingEngine(out, out, segmentDir(), wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.doRun(0);
    return re;
  }

  @Test
  public void buildPointsFromCircleGeometry() {
    double searchRadius = 5000;
    double startAngle = 90;
    int points = 5;

    List<OsmNodeNamed> wps = buildStartWaypointList();
    OsmNodeNamed start = wps.get(0);

    RoutingEngine re = createDummyEngine(searchRadius);
    re.buildPointsFromCircle(wps, startAngle, searchRadius, points);

    // should add (points-1) intermediate + 1 return = points total added
    Assert.assertEquals("should have 1 start + 4 intermediate + 1 return", 1 + points, wps.size());

    // last waypoint should be at same position as start (return to origin)
    OsmNodeNamed last = wps.get(wps.size() - 1);
    Assert.assertEquals("return waypoint lon should match start", start.ilon, last.ilon);
    Assert.assertEquals("return waypoint lat should match start", start.ilat, last.ilat);
    Assert.assertEquals("to_rt", last.name);

    // intermediate waypoints should be approximately searchRadius from start
    for (int i = 1; i < wps.size() - 1; i++) {
      double dist = CheapRuler.distance(start.ilon, start.ilat, wps.get(i).ilon, wps.get(i).ilat);
      Assert.assertTrue("waypoint " + i + " distance " + (int) dist + "m should be near searchRadius",
        Math.abs(dist - searchRadius) < searchRadius * 0.1);
      Assert.assertTrue(wps.get(i).name.startsWith("rt"));
    }
  }

  // --- Isochrone + combined strategy tests ---

  // Integration: isochrone=true produces a valid closed loop
  @Test
  public void roundTripIsochroneBasicLoop() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 1000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtIsochrone", rctx);

    RoundTripFixture.assertNoEngineErrorOrSkip(re, "isochrone round-trip");
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("isochrone should produce a track", track);
    Assert.assertTrue("track should have nodes", track.nodes.size() > 2);

    OsmPathElement first = track.nodes.get(0);
    OsmPathElement last = track.nodes.get(track.nodes.size() - 1);
    int closingDistance = first.calcDistance(last);
    Assert.assertTrue("loop should close near origin, gap=" + closingDistance + "m",
      closingDistance < 500);
  }

  // Integration: isochrone produces valid loops in all 4 directions
  @Test
  public void roundTripIsochroneAllDirections() {
    for (int dir : new int[]{0, 90, 180, 270}) {
      RoutingContext rctx = new RoutingContext();
      rctx.startDirection = dir;
      rctx.roundTripDistance = 3000; // need realistic radius for indirectness-based placement
      rctx.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;

      RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtIsoDir" + dir, rctx);

      RoundTripFixture.assertNoEngineErrorOrSkip(re, "isochrone dir=" + dir);
      OsmTrack track = re.getFoundTrack();
      Assert.assertNotNull("isochrone dir=" + dir + " should produce track", track);
      Assert.assertTrue("isochrone dir=" + dir + " should have >2 nodes", track.nodes.size() > 2);
      Assert.assertTrue("isochrone dir=" + dir + " should have positive distance", track.distance > 500);
    }
  }

  // Integration: isochrone and probe both produce valid routes for the same input
  @Test
  public void roundTripIsochroneAndProbeBothSucceed() {
    RoutingContext probeCtx = new RoutingContext();
    probeCtx.startDirection = 0;
    probeCtx.roundTripDistance = 1000;
    RoutingEngine probeRe = calcRoundTrip(8.720, 50.000, "rtBothProbe", probeCtx);

    RoutingContext isoCtx = new RoutingContext();
    isoCtx.startDirection = 0;
    isoCtx.roundTripDistance = 1000;
    isoCtx.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;
    RoutingEngine isoRe = calcRoundTrip(8.720, 50.000, "rtBothIso", isoCtx);

    Assert.assertNull("probe failed: " + probeRe.getErrorMessage(), probeRe.getErrorMessage());
    Assert.assertNull("isochrone failed: " + isoRe.getErrorMessage(), isoRe.getErrorMessage());

    OsmTrack probeTrack = probeRe.getFoundTrack();
    OsmTrack isoTrack = isoRe.getFoundTrack();
    Assert.assertNotNull(probeTrack);
    Assert.assertNotNull(isoTrack);
    Assert.assertTrue("probe should produce positive distance", probeTrack.distance > 100);
    Assert.assertTrue("isochrone should produce positive distance", isoTrack.distance > 100);
  }

  // Integration: isochrone distance accuracy is reasonable
  @Test
  public void roundTripIsochroneDistanceAccuracy() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 90;
    rctx.roundTripDistance = 3000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtIsoAccuracy", rctx);

    RoundTripFixture.assertNoEngineErrorOrSkip(re, "isochrone routing");
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull(track);

    double ratio = (double) track.distance / 3000;
    Assert.assertTrue("distance ratio " + String.format("%.2f", ratio) + " should be < 5.0", ratio < 5.0);
    Assert.assertTrue("distance ratio " + String.format("%.2f", ratio) + " should be > 0.2", ratio > 0.2);
  }

  // Integration: default (no flag) uses probe, not isochrone
  @Test
  public void roundTripDefaultUsesProbe() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 1000;
    // no algorithm set — must default to AUTO (the probe-based competition)
    Assert.assertEquals(RoundTripAlgorithm.AUTO, rctx.roundTripAlgorithm);

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtDefaultProbe", rctx);
    Assert.assertNull(re.getErrorMessage());
    Assert.assertNotNull(re.getFoundTrack());
  }

  // --- mergeIsochroneWithProbe unit tests ---

  // --- direct ISOCHRONE road-native waypoint placement ---

  // --- Reachability-aware waypoint placement tests ---

  @Test
  public void angleDiffBasic() {
    Assert.assertEquals(0, PlacementGeometry.angleDiff(0, 0), 0.01);
    Assert.assertEquals(90, PlacementGeometry.angleDiff(0, 90), 0.01);
    Assert.assertEquals(180, PlacementGeometry.angleDiff(0, 180), 0.01);
    Assert.assertEquals(90, PlacementGeometry.angleDiff(0, 270), 0.01);
    Assert.assertEquals(10, PlacementGeometry.angleDiff(355, 5), 0.01);
  }

  // --- Loop perimeter scaling tests ---

  private static OsmNodeNamed createNode(String name, int ilon, int ilat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = ilon;
    n.ilat = ilat;
    return n;
  }

  private List<OsmNodeNamed> buildStartWaypointList() {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(createNode("from", START_ILON, START_ILAT));
    return wps;
  }

  private MatchedWaypoint createMatchedWaypoint(String name, int wpIlon, int wpIlat, int cpIlon, int cpIlat) {
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.name = name;
    mwp.waypoint = new OsmNode(wpIlon, wpIlat);
    mwp.crosspoint = new OsmNode(cpIlon, cpIlat);
    mwp.node1 = new OsmNode(cpIlon, cpIlat);
    mwp.node2 = new OsmNode(cpIlon + 100, cpIlat + 100);
    mwp.radius = mwp.waypoint.calcDistance(mwp.crosspoint);
    return mwp;
  }

  private void copyResourceToDir(String resource, File dir) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      Assert.assertNotNull("resource not found: " + resource, in);
      Files.copy(in, new File(dir, resource.substring(resource.lastIndexOf('/') + 1)).toPath());
    }
  }

  private File profileFile() {
    return new File(projectDir, "misc/profiles2/trekking.brf");
  }

  private File segmentDir() {
    return new File(projectDir, "brouter-map-creator/build/resources/test/tmp/segments");
  }

  private RoutingEngine createDummyEngine(double searchRadius) {
    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = START_ILON;
    n.ilat = START_ILAT;
    wplist.add(n);
    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile().getAbsolutePath();
    RoutingEngine re = new RoutingEngine(
      null, null,
      segmentDir(),
      wplist, rctx, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.roundTripSearchRadius = searchRadius;
    return re;
  }

  private RoutingEngine calcRoundTrip(double lon, double lat, String trackname, RoutingContext rctx) {
    String out = new File(outputDir.getRoot(), trackname).getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    wplist.add(n);

    rctx.localFunction = profileFile().getAbsolutePath();

    RoutingEngine re = new RoutingEngine(
      out, out,
      segmentDir(),
      wplist,
      rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);

    re.doRun(0);

    return re;
  }

  private String calcRoute(double flon, double flat, double tlon, double tlat, File dir, String trackname, RoutingContext rctx) {
    return calcRouteEngine(flon, flat, tlon, tlat, dir, trackname, rctx).getErrorMessage();
  }

  private RoutingEngine calcRouteEngine(double flon, double flat, double tlon, double tlat, File dir, String trackname, RoutingContext rctx) {
    String out = new File(dir, trackname).getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n;
    n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 + (int) (flon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (flat * 1000000 + 0.5);
    wplist.add(n);

    n = new OsmNodeNamed();
    n.name = "to";
    n.ilon = 180000000 + (int) (tlon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (tlat * 1000000 + 0.5);
    wplist.add(n);

    rctx.localFunction = profileFile().getAbsolutePath();

    RoutingEngine re = new RoutingEngine(
      out, out,
      segmentDir(),
      wplist,
      rctx);

    re.doRun(0);

    return re;
  }

  // --- Phase 2.0: iso-asymmetry bearing computation -----------------------

  /** Build a synthetic 36-bucket frontier table. Bucket i is at bearing
   *  i*10°. Each bucket = [direction_deg, airDist_m, cost, hits, ilon, ilat].
   *  ilon/ilat are zeroed (not used by the bias computation). */
  /** Uniform-reach helper: every bucket has the same airDist/cost/hits. */
  // --- Phase 2.1: frontier-axis PCA + perpendicularity --------------------

  /** Local copy of RoutingEngine's private angularDiff for test fixture setup. */
  private static double angularDiff(double x, double y) {
    double d = Math.abs(x - y) % 360;
    return d > 180 ? 360 - d : d;
  }

  @Test
  public void frontierAxisNone_carriesSentinels() {
    FrontierAxis none = FrontierAxis.NONE;
    Assert.assertFalse(none.hasStrongAxis);
    Assert.assertTrue(Double.isNaN(none.axisBearingDegrees));
    Assert.assertEquals(0.0, none.strength, 0.0);
  }

  // ---- via-pinned bulge detection -----------------------------------------

}
