package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.OsmPathElement;
import btools.router.RoundTripFixture;
import btools.util.CheapRuler;

/**
 * Mirrored tests for {@link WaypointSnapper} — the snap/validate/probe tests
 * moved out of RoutingEngineTest with the extraction.
 */
public class WaypointSnapperTest {
  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N

  private WaypointSnapper snapper(double searchRadius) {
    RoundTripEngineOps ops =
      RoundTripFixture.dummyRoundTripEngine("trekking", searchRadius).roundTripOps();
    return new WaypointSnapper(ops, ops, ops);
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

  // filterRoundTripWaypoints removes waypoints that snapped too far
  @Test
  public void filterRemovesDistantSnaps() {
    double searchRadius = 5000;
    WaypointSnapper snapper = snapper(searchRadius);

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

    snapper.filterRoundTripWaypoints(wpts);

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
    WaypointSnapper snapper = snapper(searchRadius);

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

    snapper.filterRoundTripWaypoints(wpts);

    Assert.assertEquals("should have 4 waypoints (start, rt1, rt3, end)", 4, wpts.size());
    Assert.assertEquals("rt1", wpts.get(1).name);
    Assert.assertEquals("rt3", wpts.get(2).name);
  }

  // filterRoundTripWaypoints preserves at least one intermediate waypoint
  @Test
  public void filterPreservesMinimumOneIntermediate() {
    double searchRadius = 1000;
    WaypointSnapper snapper = snapper(searchRadius);

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

    snapper.filterRoundTripWaypoints(wpts);

    // at least one rt waypoint must remain
    int rtCount = 0;
    for (MatchedWaypoint mwp : wpts) {
      if (mwp.name != null && mwp.name.startsWith("rt")) rtCount++;
    }
    Assert.assertTrue("must preserve at least one intermediate waypoint", rtCount >= 1);
    Assert.assertTrue("total waypoints >= 3 (start + 1 intermediate + end)", wpts.size() >= 3);
  }

  @Test
  public void selectProbeDirectionKeepsFartherUsableMatch() {
    WaypointSnapper snapper = snapper(5000);
    List<MatchedWaypoint> matches = new ArrayList<>();

    MatchedWaypoint nearerFerry = createMatchedWaypoint("near_ferry",
      START_ILON, START_ILAT, START_ILON, START_ILAT);
    nearerFerry.node1 = new OsmNode(START_ILON, START_ILAT);
    nearerFerry.node2 = new OsmNode(START_ILON + 50000, START_ILAT);
    nearerFerry.radius = 10;
    matches.add(nearerFerry);

    MatchedWaypoint fartherRoad = createMatchedWaypoint("farther_road",
      START_ILON, START_ILAT, START_ILON, START_ILAT);
    fartherRoad.radius = 25;
    matches.add(fartherRoad);

    ProbeDirection selected = snapper.selectProbeDirection(90, matches, 0, 2,
      true, 2000, 10.0);

    Assert.assertNotNull(selected);
    Assert.assertEquals(1, selected.successfulProbeCount);
    Assert.assertSame(fartherRoad, selected.bestMatch);
  }

  @Test
  public void filterRoundTripWaypointsRemovesFerrySegments() throws Exception {
    WaypointSnapper snapper = snapper(5000);

    List<MatchedWaypoint> waypoints = new ArrayList<>();
    waypoints.add(createMatchedWaypoint("from", START_ILON, START_ILAT, START_ILON, START_ILAT));

    // Normal road waypoint at ~700m north (well-spaced, node1-node2 ~7m)
    waypoints.add(createMatchedWaypoint("rt1", START_ILON, START_ILAT + 10000,
      START_ILON, START_ILAT + 10000));

    // Ferry-like waypoint: node1-node2 very far apart (simulating a ferry segment)
    MatchedWaypoint ferryWp = createMatchedWaypoint("rt2", START_ILON + 10000, START_ILAT,
      START_ILON + 10000, START_ILAT);
    // Override node1/node2: ~3.6km apart geographically → ferry-like
    ferryWp.node1 = new OsmNode(START_ILON, START_ILAT);
    ferryWp.node2 = new OsmNode(START_ILON + 50000, START_ILAT);
    ferryWp.radius = 100;
    waypoints.add(ferryWp);

    // Normal road waypoint at ~700m west (well-spaced from rt1)
    waypoints.add(createMatchedWaypoint("rt3", START_ILON - 10000, START_ILAT,
      START_ILON - 10000, START_ILAT));

    waypoints.add(createMatchedWaypoint("to_rt", START_ILON, START_ILAT, START_ILON, START_ILAT));

    snapper.filterRoundTripWaypoints(waypoints);

    // The ferry waypoint (rt2) should be removed, others kept
    boolean ferryRemoved = true;
    for (MatchedWaypoint mwp : waypoints) {
      if ("rt2".equals(mwp.name)) ferryRemoved = false;
    }
    Assert.assertTrue("ferry waypoint rt2 should have been removed", ferryRemoved);
    // rt1 and rt3 should still be present
    boolean hasRt1 = false, hasRt3 = false;
    for (MatchedWaypoint mwp : waypoints) {
      if ("rt1".equals(mwp.name)) hasRt1 = true;
      if ("rt3".equals(mwp.name)) hasRt3 = true;
    }
    Assert.assertTrue("normal waypoint rt1 should be kept", hasRt1);
    Assert.assertTrue("normal waypoint rt3 should be kept", hasRt3);
  }

  /**
   * Wide-mouth bulge (the Basel "Im Stein" shape): northbound road with a
   * rectangular detour — 600m west, 400m north, 600m back east — whose mouth
   * nodes are 400m apart. No ≤50m pinch exists, so removeMicroDetours cannot
   * see it; findViaPinnedBulgeSpan must return exactly the mouth pair.
   */
  @Test
  public void findViaPinnedBulgeSpan_wideMouthRectangle() {
    List<OsmPathElement> nodes = new ArrayList<>();
    for (int y = 0; y <= 500; y += 100) nodes.add(bulgeNode(0, y));          // 0-5, mouth in = 5
    for (int x = -150; x >= -600; x -= 150) nodes.add(bulgeNode(x, 500));    // 6-9 west leg
    nodes.add(bulgeNode(-600, 650));                                         // 10
    nodes.add(bulgeNode(-600, 800));                                         // 11 = pinned via
    nodes.add(bulgeNode(-600, 900));                                         // 12 apex corner
    for (int x = -450; x <= 0; x += 150) nodes.add(bulgeNode(x, 900));       // 13-16, mouth out = 16
    for (int y = 1000; y <= 1400; y += 100) nodes.add(bulgeNode(0, y));      // 17-21

    int[] span = WaypointSnapper.findViaPinnedBulgeSpan(nodes, 11, 0, nodes.size() - 1, 4000);

    Assert.assertNotNull("bulge span should be detected", span);
    Assert.assertEquals("span start should be the inbound mouth node", 5, span[0]);
    Assert.assertEquals("span end should be the outbound mouth node", 16, span[1]);
  }

  /** Normal forward progression near a via (arc ≈ crow-fly) must not fire. */
  @Test
  public void findViaPinnedBulgeSpan_straightRoadIsClean() {
    List<OsmPathElement> nodes = new ArrayList<>();
    for (int y = 0; y <= 2000; y += 100) nodes.add(bulgeNode(0, y));

    Assert.assertNull(WaypointSnapper.findViaPinnedBulgeSpan(nodes, 10, 0, nodes.size() - 1, 4000));
  }

  /** A gentle curve (ratio well under 3x) must not fire either. */
  @Test
  public void findViaPinnedBulgeSpan_gentleCurveIsClean() {
    List<OsmPathElement> nodes = new ArrayList<>();
    // quarter-circle of radius 1000m: arc ≈ 1571m, chord ≈ 1414m, ratio ≈ 1.1
    for (int k = 0; k <= 18; k++) {
      double a = Math.PI / 2 * k / 18.0;
      nodes.add(bulgeNode(1000 * Math.sin(a), 1000 - 1000 * Math.cos(a)));
    }
    Assert.assertNull(WaypointSnapper.findViaPinnedBulgeSpan(nodes, 9, 0, nodes.size() - 1, 4000));
  }

  /**
   * spanCostPerMeter sums positive per-edge cost deltas and skips the negative
   * delta of a leg-boundary reset (planner-merged tracks reset cumulative cost
   * to 0 at each via join).
   */
  @Test
  public void spanCostPerMeter_skipsLegBoundaryReset() {
    List<OsmPathElement> nodes = new ArrayList<>();
    for (int k = 0; k <= 4; k++) nodes.add(bulgeNode(0, k * 100));
    nodes.get(0).cost = 0;
    nodes.get(1).cost = 150;
    nodes.get(2).cost = 300;  // leg 1 ends here
    nodes.get(3).cost = 10;   // leg 2 restarts cumulative cost
    nodes.get(4).cost = 160;

    // positive deltas: 150 + 150 + 150 = 450 over ~400m
    double cpm = WaypointSnapper.spanCostPerMeter(nodes, 0, 4);
    Assert.assertEquals(450.0 / 400.0, cpm, 0.02);
  }

  /** Node at (xMeters east, yMeters north) of the test origin. */
  private static OsmPathElement bulgeNode(double xMeters, double yMeters) {
    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int ilon = START_ILON + (int) Math.round(xMeters / kxky[0]);
    int ilat = START_ILAT + (int) Math.round(yMeters / kxky[1]);
    return OsmPathElement.create(ilon, ilat, (short) 0, null);
  }

}
