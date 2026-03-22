package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.util.CheapRuler;

public class RoutingEngineTest {
  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N
  private static final String PROFILE_PATH = "/../../../../misc/profiles2/trekking.brf";
  private static final String SEGMENTS_PATH = "/../../../../brouter-map-creator/build/resources/test/tmp/segments";

  private File workingDir;

  @Before
  public void before() {
    URL resulturl = this.getClass().getResource("/testtrack0.gpx");
    Assert.assertNotNull("reference result not found: ", resulturl);
    File resultfile = new File(resulturl.getFile());
    workingDir = resultfile.getParentFile();
  }

  @Test
  public void routeCrossingSegmentBorder() {
    String msg = calcRoute(8.720897, 50.002515, 8.723658, 49.997510, "testtrack", new RoutingContext());
    // error message from router?
    Assert.assertNull("routing failed: " + msg, msg);

    // if the track didn't change, we expect the first alternative also
    File a1 = new File(workingDir, "testtrack1.gpx");
    a1.deleteOnExit();
    Assert.assertTrue("result content mismatch", a1.exists());
  }

  @Test
  public void routeDestinationPointFarOff() {
    String msg = calcRoute(8.720897, 50.002515, 16.723658, 49.997510, "notrack", new RoutingContext());
    Assert.assertTrue(msg, msg != null && msg.contains("not found"));
  }

  // check that a (short) route and an alternative route can be computed
  // while explicitely overriding a routing profile parameter
  @Test
  public void overrideParam() {
    // 1st route computing (with param)
    RoutingContext rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    String msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 1st route): " + msg, msg);
    // 2nd route computing (same from/to & same param)
    rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 2nd route): " + msg, msg);

    File trackFile = new File(workingDir, "paramTrack0.gpx");
    trackFile.deleteOnExit();
    trackFile = new File(workingDir, "paramTrack1.gpx");
    trackFile.deleteOnExit();
    // checks if a gpx file has been created for the alternative route
    Assert.assertTrue("result content mismatch", trackFile.exists());
  }

  // Round-trip from center of test area should produce a valid loop
  @Test
  public void roundTripBasicLoop() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0; // north
    rctx.roundTripDistance = 1000;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtBasic", rctx);

    Assert.assertNull("round-trip routing failed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("round-trip should produce a track", track);
    Assert.assertTrue("round-trip track should have nodes", track.nodes.size() > 2);

    // track should start and end near the origin
    OsmPathElement first = track.nodes.get(0);
    OsmPathElement last = track.nodes.get(track.nodes.size() - 1);
    int closingDistance = first.calcDistance(last);
    Assert.assertTrue("loop should close near origin, but gap is " + closingDistance + "m",
      closingDistance < 500);
  }

  // Round-trip with large radius pushing waypoints outside road data area
  // should still succeed (bad waypoints filtered, no beeline segments)
  @Test
  public void roundTripFiltersDistantWaypoints() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 90; // east, dreieich data runs out quickly
    rctx.roundTripDistance = 5000;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtEdge", rctx);

    Assert.assertNull("round-trip near data edge failed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("round-trip near data edge should produce a track", track);

    // verify no beeline segments: check that all consecutive node pairs are
    // reasonably close (no giant jumps that indicate a straight-line beeline)
    for (int i = 1; i < track.nodes.size(); i++) {
      int segDist = track.nodes.get(i).calcDistance(track.nodes.get(i - 1));
      Assert.assertTrue("segment " + i + " too long (" + segDist + "m), likely a beeline",
        segDist < 2000);
    }
  }

  // filterRoundTripWaypoints removes waypoints that snapped too far
  @Test
  public void filterRemovesDistantSnaps() {
    double searchRadius = 5000;
    RoutingEngine re = createDummyEngine(searchRadius);

    // Build matched waypoints: start, 4 intermediates, end (return to start)
    List<MatchedWaypoint> wpts = new ArrayList<>();
    int startIlon = START_ILON;
    int startIlat = START_ILAT;

    // start waypoint
    wpts.add(createMatchedWaypoint("from", startIlon, startIlat, startIlon, startIlat));
    // rt1: matched close (500m) — should be kept
    wpts.add(createMatchedWaypoint("rt1", startIlon + 7000, startIlat + 3000,
      startIlon + 7200, startIlat + 3100));
    // rt2: waypoint 60000 east, crosspoint 10000 east → snap ~3.5km > 2500m threshold
    wpts.add(createMatchedWaypoint("rt2", startIlon + 60000, startIlat,
      startIlon + 10000, startIlat));
    // rt3: waypoint 55000 east, crosspoint 8000 east → snap ~3.3km > 2500m threshold
    wpts.add(createMatchedWaypoint("rt3", startIlon + 55000, startIlat - 3000,
      startIlon + 8000, startIlat - 2000));
    // rt4: matched close (300m) — should be kept
    wpts.add(createMatchedWaypoint("rt4", startIlon + 3000, startIlat - 5000,
      startIlon + 3200, startIlat - 5100));
    // end waypoint (return to start)
    wpts.add(createMatchedWaypoint("to_rt", startIlon, startIlat, startIlon, startIlat));

    re.filterRoundTripWaypoints(wpts);

    Assert.assertEquals("should have 4 waypoints (start, rt1, rt4, end)", 4, wpts.size());
    Assert.assertEquals("from", wpts.get(0).name);
    Assert.assertEquals("rt1", wpts.get(1).name);
    Assert.assertEquals("rt4", wpts.get(2).name);
    Assert.assertEquals("to_rt", wpts.get(3).name);
  }

  // filterRoundTripWaypoints removes consecutive waypoints that matched too close
  @Test
  public void filterRemovesCloseConsecutive() {
    double searchRadius = 5000;
    RoutingEngine re = createDummyEngine(searchRadius);

    List<MatchedWaypoint> wpts = new ArrayList<>();
    int startIlon = START_ILON;
    int startIlat = START_ILAT;

    wpts.add(createMatchedWaypoint("from", startIlon, startIlat, startIlon, startIlat));
    // rt1 and rt2: crosspoints only 100m apart (< 500m threshold)
    wpts.add(createMatchedWaypoint("rt1", startIlon + 5000, startIlat + 5000,
      startIlon + 5000, startIlat + 5000));
    wpts.add(createMatchedWaypoint("rt2", startIlon + 5100, startIlat + 5100,
      startIlon + 5100, startIlat + 5100));
    // rt3: far enough from rt1 — should be kept
    wpts.add(createMatchedWaypoint("rt3", startIlon + 3000, startIlat - 5000,
      startIlon + 3000, startIlat - 5000));
    wpts.add(createMatchedWaypoint("to_rt", startIlon, startIlat, startIlon, startIlat));

    re.filterRoundTripWaypoints(wpts);

    Assert.assertEquals("should have 4 waypoints (start, rt1, rt3, end)", 4, wpts.size());
    Assert.assertEquals("rt1", wpts.get(1).name);
    Assert.assertEquals("rt3", wpts.get(2).name);
  }

  // filterRoundTripWaypoints preserves at least one intermediate waypoint
  @Test
  public void filterPreservesMinimumOneIntermediate() {
    double searchRadius = 1000;
    RoutingEngine re = createDummyEngine(searchRadius);

    List<MatchedWaypoint> wpts = new ArrayList<>();
    int startIlon = START_ILON;
    int startIlat = START_ILAT;

    wpts.add(createMatchedWaypoint("from", startIlon, startIlat, startIlon, startIlat));
    // all intermediates snap far — but one must be preserved
    wpts.add(createMatchedWaypoint("rt1", startIlon + 20000, startIlat,
      startIlon + 5000, startIlat));
    wpts.add(createMatchedWaypoint("rt2", startIlon, startIlat + 20000,
      startIlon, startIlat + 5000));
    wpts.add(createMatchedWaypoint("to_rt", startIlon, startIlat, startIlon, startIlat));

    re.filterRoundTripWaypoints(wpts);

    // at least one rt waypoint must remain
    int rtCount = 0;
    for (MatchedWaypoint mwp : wpts) {
      if (mwp.name != null && mwp.name.startsWith("rt")) rtCount++;
    }
    Assert.assertTrue("must preserve at least one intermediate waypoint", rtCount >= 1);
    Assert.assertTrue("total waypoints >= 3 (start + 1 intermediate + end)", wpts.size() >= 3);
  }

  // allowSamewayback round-trip should produce valid out-and-back route
  @Test
  public void roundTripAllowSamewayback() {
    RoutingContext rctx = new RoutingContext();
    rctx.startDirection = 0; // north
    rctx.roundTripDistance = 1000;
    rctx.allowSamewayback = true;

    RoutingEngine re = calcRoundTrip(8.720, 50.000, "rtSameway", rctx);

    Assert.assertNull("allowSamewayback routing failed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("allowSamewayback should produce a track", track);
    Assert.assertTrue("track should have significant length", track.distance > 200);
  }

  // buildPointsFromCircle generates correct number of waypoints at expected distances
  @Test
  public void buildPointsFromCircleGeometry() {
    double searchRadius = 5000;
    double startAngle = 90; // east
    int points = 5;

    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = START_ILON;
    start.ilat = START_ILAT;
    start.name = "from";
    wps.add(start);

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

  private RoutingEngine createDummyEngine(double searchRadius) {
    String wd = workingDir.getAbsolutePath();
    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = START_ILON;
    n.ilat = START_ILAT;
    wplist.add(n);
    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = wd + PROFILE_PATH;
    RoutingEngine re = new RoutingEngine(
      null, null,
      new File(wd, SEGMENTS_PATH),
      wplist, rctx, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.roundTripSearchRadius = searchRadius;
    return re;
  }

  private RoutingEngine calcRoundTrip(double lon, double lat, String trackname, RoutingContext rctx) {
    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    wplist.add(n);

    rctx.localFunction = wd + PROFILE_PATH;

    RoutingEngine re = new RoutingEngine(
      wd + "/" + trackname,
      wd + "/" + trackname,
      new File(wd, SEGMENTS_PATH),
      wplist,
      rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);

    re.doRun(0);

    return re;
  }

  private String calcRoute(double flon, double flat, double tlon, double tlat, String trackname, RoutingContext rctx) {
    String wd = workingDir.getAbsolutePath();

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

    rctx.localFunction = wd + PROFILE_PATH;

    RoutingEngine re = new RoutingEngine(
      wd + "/" + trackname,
      wd + "/" + trackname,
      new File(wd, SEGMENTS_PATH),
      wplist,
      rctx);

    re.doRun(0);

    return re.getErrorMessage();
  }

}
