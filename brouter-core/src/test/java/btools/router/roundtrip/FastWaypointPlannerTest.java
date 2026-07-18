package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.util.CheapRuler;

/**
 * Mirrored tests for {@link FastWaypointPlanner} — the characterization and
 * fake-ops scenario tests moved out of RoutingEngineTest with the planner.
 */
public class FastWaypointPlannerTest {

  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N

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

  @Test
  public void fastRingRadiusScaleUsesFinalLoopOrder() {
    double[] viable = {0, 30, 60, 90, 120, 150, 180};
    ProbeResult probe = new ProbeResult(viable, new ArrayList<>());

    double scale = FastWaypointPlanner.fastRingRadiusScale(probe, 5, 0);

    // Spread selection returns {0, 180, 90, 30}; placement routes
    // {0, 30, 90, 180}. The latter perimeter needs the larger scale.
    Assert.assertEquals(0.78, scale, 0.01);
  }

  @Test
  public void fastRingRadiusScaleUsesConfidenceFilteredDirections() {
    double[] viable = PlacementGeometry.fullCircleBearings(12);
    List<ProbeDirection> scored = new ArrayList<>();
    for (int i = 0; i < viable.length; i++) {
      // Eight strong bearings form one contiguous 0-210 degree arc. The four
      // weak bearings complete the apparent ring but placement filters them.
      scored.add(new ProbeDirection(viable[i], i < 8 ? 2 : 1, null));
    }
    ProbeResult probe = new ProbeResult(viable, scored);

    double scale = FastWaypointPlanner.fastRingRadiusScale(probe, 5, 0);

    Assert.assertEquals(0.78, scale, 0.01);
  }

  @Test
  public void directionalLobeBearingsReproducePre903Arc() {
    // 4 vias toward 90°: arc half-width 90-180/5 = 54°, evenly fanned.
    double[] b = FastWaypointPlanner.directionalLobeBearings(90, 4);
    Assert.assertArrayEquals(new double[]{36, 72, 108, 144}, b, 0.01);

    // Wrap-around at north: same fan shifted to direction 0.
    double[] north = FastWaypointPlanner.directionalLobeBearings(0, 4);
    Assert.assertArrayEquals(new double[]{306, 342, 18, 54}, north, 0.01);
  }

  @Test
  public void maxViaDistFromStartReturnsFarthestVia() {
    List<OsmNodeNamed> wps = buildStartWaypointList();
    wps.add(createNode("rt1", START_ILON + 5000, START_ILAT));
    wps.add(createNode("rt2", START_ILON, START_ILAT + 20000));
    double expected = CheapRuler.distance(START_ILON, START_ILAT,
      START_ILON, START_ILAT + 20000);
    Assert.assertEquals(expected, FastWaypointPlanner.maxViaDistFromStart(wps), 0.01);
  }

  @Test
  public void placeWaypointsFromProbeMatchesOrdersRingAndClosesLoop() {
    double[] viable = {240, 0, 120, 300, 60, 180}; // deliberately unsorted
    ProbeResult probe = probeWithDistinctMatches(viable);
    List<OsmNodeNamed> wps = buildStartWaypointList();

    // targetPoints 7 -> needed 6 -> all bearings, no spread selection.
    int placed = createDummyPlanner().placeWaypointsFromProbeMatches(wps, probe, -1, 7);

    Assert.assertEquals(6, placed);
    Assert.assertEquals(8, wps.size()); // from + 6 vias + closing
    // Loop order from anchor 0: ascending bearings; vias carry the CROSSPOINT
    // coordinates of their bearing's retained match.
    double[] loopOrder = {0, 60, 120, 180, 240, 300};
    for (int i = 0; i < 6; i++) {
      OsmNodeNamed via = wps.get(1 + i);
      Assert.assertEquals("rt" + (i + 1), via.name);
      OsmNode expected = crosspointFor(loopOrder[i]);
      Assert.assertEquals(expected.ilon, via.ilon);
      Assert.assertEquals(expected.ilat, via.ilat);
    }
    OsmNodeNamed closing = wps.get(7);
    Assert.assertEquals("to_rt", closing.name);
    Assert.assertEquals(START_ILON, closing.ilon);
    Assert.assertEquals(START_ILAT, closing.ilat);
  }

  @Test
  public void placeWaypointsFromProbeMatchesCapsToTargetViaCount() {
    // Dense 12-bearing ring (the encircle fallback probes denser than needed).
    double[] viable = new double[12];
    for (int i = 0; i < 12; i++) viable[i] = i * 30;
    ProbeResult probe = probeWithDistinctMatches(viable);
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = createDummyPlanner().placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed); // needed = targetPoints - 1
    Assert.assertEquals(6, wps.size());
  }

  @Test
  public void placeWaypointsFromProbeMatchesDedupsAndSubstitutesFromPool() {
    // Spread selection (anchor 0, needed 4) picks {0, 180, 90, 270}; 45 stays
    // in the pool. 90 snaps onto 0's node -> deduped -> 45 substitutes.
    double[] viable = {0, 90, 180, 270, 45};
    ProbeResult probe = probeWithDistinctMatches(viable);
    matchFor(probe, 90).crosspoint = crosspointFor(0);
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = createDummyPlanner().placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed);
    assertUniqueViaNodes(wps);
    Assert.assertTrue("substitute 45 not placed", containsVia(wps, crosspointFor(45)));
  }

  @Test
  public void placeWaypointsFromProbeMatchesNeverCommitsFerryLikeMatch() {
    double[] viable = {0, 90, 180, 270, 45};
    ProbeResult probe = probeWithDistinctMatches(viable);
    // 90's retained match is ferry-like: node1-node2 ~3.5km apart (> 1500m).
    MatchedWaypoint ferry = matchFor(probe, 90);
    ferry.node1 = new OsmNode(START_ILON, START_ILAT);
    ferry.node2 = new OsmNode(START_ILON + 50000, START_ILAT);
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = createDummyPlanner().placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed);
    Assert.assertFalse("ferry-like via committed", containsVia(wps, crosspointFor(90)));
    Assert.assertTrue("substitute 45 not placed", containsVia(wps, crosspointFor(45)));
  }

  @Test
  public void placeWaypointsFromProbeMatchesDropsViaOnStartNode() {
    double[] viable = {0, 90, 180, 270, 45};
    ProbeResult probe = probeWithDistinctMatches(viable);
    matchFor(probe, 90).crosspoint = new OsmNode(START_ILON, START_ILAT);
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = createDummyPlanner().placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed);
    for (int i = 1; i <= placed; i++) {
      OsmNodeNamed via = wps.get(i);
      Assert.assertFalse("via " + via.name + " sits on the start node",
        via.ilon == START_ILON && via.ilat == START_ILAT);
    }
    Assert.assertTrue("substitute 45 not placed", containsVia(wps, crosspointFor(45)));
  }

  @Test
  public void placeWaypointsFromProbeMatchesIsDeterministic() {
    double[] viable = new double[12];
    for (int i = 0; i < 12; i++) viable[i] = i * 30;

    List<OsmNodeNamed> first = buildStartWaypointList();
    createDummyPlanner().placeWaypointsFromProbeMatches(first, probeWithDistinctMatches(viable), 45, 6);
    List<OsmNodeNamed> second = buildStartWaypointList();
    createDummyPlanner().placeWaypointsFromProbeMatches(second, probeWithDistinctMatches(viable), 45, 6);

    Assert.assertEquals(first.size(), second.size());
    for (int i = 0; i < first.size(); i++) {
      Assert.assertEquals(first.get(i).name, second.get(i).name);
      Assert.assertEquals(first.get(i).ilon, second.get(i).ilon);
      Assert.assertEquals(first.get(i).ilat, second.get(i).ilat);
    }
  }

  @Test
  public void plannerDropsIslandedViaAndSubstitutes() {
    FakePlacementOps ops = new FakePlacementOps();
    double[] viable = {0, 90, 180, 270, 45};
    ProbeResult probe = probeWithDistinctMatches(viable);
    ops.unreachableNodes.add(crosspointFor(90).getIdFromPos());
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = new FastWaypointPlanner(ops).placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed);
    Assert.assertFalse("islanded via committed", containsVia(wps, crosspointFor(90)));
    Assert.assertTrue("substitute 45 not placed", containsVia(wps, crosspointFor(45)));
  }

  @Test
  public void plannerDropsProfileHostileViaAndSubstitutes() {
    FakePlacementOps ops = new FakePlacementOps();
    double[] viable = {0, 90, 180, 270, 45};
    ProbeResult probe = probeWithDistinctMatches(viable);
    ops.hostileNodes.add(crosspointFor(90).getIdFromPos());
    List<OsmNodeNamed> wps = buildStartWaypointList();

    int placed = new FastWaypointPlanner(ops).placeWaypointsFromProbeMatches(wps, probe, -1, 5);

    Assert.assertEquals(4, placed);
    Assert.assertFalse("hostile via committed", containsVia(wps, crosspointFor(90)));
    Assert.assertTrue("substitute 45 not placed", containsVia(wps, crosspointFor(45)));
  }

  @Test
  public void placeReturnsValidatedRingSkeleton() {
    FakePlacementOps ops = new FakePlacementOps();
    double[] ring = new double[12];
    for (int i = 0; i < 12; i++) ring[i] = i * 30;
    ops.probeResults.add(probeWithDistinctMatches(ring));
    OsmNodeNamed start = createNode("from", START_ILON, START_ILAT);

    FastPlacementOutcome outcome = new FastWaypointPlanner(ops)
      .place(new FastPlacementRequest(start, 5000, -1, 6, false));

    Assert.assertTrue(outcome.optimizedPlacement());
    Assert.assertEquals(FastPlacementOutcome.Path.RING, outcome.path);
    Assert.assertEquals(7, outcome.skeleton.size()); // start + 5 vias + closing
    Assert.assertSame(start, outcome.skeleton.get(0));
    Assert.assertEquals("to_rt", outcome.skeleton.get(6).name);
    Assert.assertEquals(0, ops.circleFallbackCalls);
  }

  @Test
  public void placeFallsBackToValidatedCircleWhenProbeFails() {
    FakePlacementOps ops = new FakePlacementOps(); // no probe results -> probe() null
    OsmNodeNamed start = createNode("from", START_ILON, START_ILAT);

    FastPlacementOutcome outcome = new FastWaypointPlanner(ops)
      .place(new FastPlacementRequest(start, 5000, -1, 6, false));

    Assert.assertFalse(outcome.optimizedPlacement());
    Assert.assertEquals(FastPlacementOutcome.Path.CIRCLE_FALLBACK, outcome.path);
    Assert.assertNotNull(outcome.diagnostic);
    Assert.assertEquals("validation must run behind the module's fallback op",
      1, ops.circleFallbackCalls);
    // The failed optimized attempt left nothing behind: start + fake circle only.
    Assert.assertSame(start, outcome.skeleton.get(0));
    Assert.assertEquals(1 + FakePlacementOps.FAKE_CIRCLE_POINTS, outcome.skeleton.size());
  }

  @Test
  public void placeDirectionalLobeFallsBackToRing() {
    FakePlacementOps ops = new FakePlacementOps();
    // Lobe probe too thin to close a loop, ring re-probe healthy.
    ops.probeResults.add(probeWithDistinctMatches(new double[]{80, 100}));
    double[] ring = new double[12];
    for (int i = 0; i < 12; i++) ring[i] = i * 30;
    ops.probeResults.add(probeWithDistinctMatches(ring));
    OsmNodeNamed start = createNode("from", START_ILON, START_ILAT);

    FastPlacementOutcome outcome = new FastWaypointPlanner(ops)
      .place(new FastPlacementRequest(start, 5000, 90, 6, true));

    Assert.assertEquals(FastPlacementOutcome.Path.RING_FALLBACK, outcome.path);
    Assert.assertTrue(outcome.optimizedPlacement());
    Assert.assertEquals(7, outcome.skeleton.size());
    Assert.assertEquals(2, ops.probeCalls);
  }

  /**
   * Deterministic fake of the planner seam: scripted probe results, explicit
   * islanded/hostile node sets, and a counting circle fallback — covers the
   * scenarios a dummy engine cannot (its island guard degrades to
   * conservative-keep and its cost evaluation is neutral).
   */
  private static final class FakePlacementOps implements FastPlacementOps {
    static final int FAKE_CIRCLE_POINTS = 4; // 3 vias + closing
    final List<ProbeResult> probeResults = new ArrayList<>();
    final java.util.Set<Long> unreachableNodes = new java.util.HashSet<>();
    final java.util.Set<Long> hostileNodes = new java.util.HashSet<>();
    int probeCalls = 0;
    int circleFallbackCalls = 0;

    @Override
    public ProbeResult probe(OsmNodeNamed start, double searchRadius, double[] bearings) {
      probeCalls++;
      return probeResults.isEmpty() ? null : probeResults.remove(0);
    }

    @Override
    public SnapUsability snapUsability(MatchedWaypoint m) {
      if (m.node1 == null || m.node2 == null || m.node1.calcDistance(m.node2) > 1500) {
        return SnapUsability.FERRY_LIKE;
      }
      if (m.crosspoint != null && hostileNodes.contains(m.crosspoint.getIdFromPos())) {
        return SnapUsability.PROFILE_HOSTILE;
      }
      return SnapUsability.OK;
    }

    @Override
    public boolean isViaReachable(MatchedWaypoint via, MatchedWaypoint startMatch) {
      return via.crosspoint == null || !unreachableNodes.contains(via.crosspoint.getIdFromPos());
    }

    @Override
    public void circleFallbackValidated(List<OsmNodeNamed> skeleton, double direction,
                                        double searchRadius, int targetPoints) {
      circleFallbackCalls++;
      for (int i = 1; i < FAKE_CIRCLE_POINTS; i++) {
        skeleton.add(createNode("rt" + i, START_ILON + i * 1000, START_ILAT + i * 1000));
      }
      skeleton.add(createNode("to_rt", skeleton.get(0).ilon, skeleton.get(0).ilat));
    }

    @Override
    public void log(String msg) {
    }
  }

  /** Planner over a permissive fake seam — same behavior as a dummy engine's
   *  ops (island guard keeps everything, cost evaluation neutral). */
  private FastWaypointPlanner createDummyPlanner() {
    return new FastWaypointPlanner(new FakePlacementOps());
  }

  /** Deterministic distinct road-like crosspoint for a bearing (see crosspointFor). */
  private ProbeResult probeWithDistinctMatches(double[] viable) {
    List<ProbeDirection> scored = new ArrayList<>();
    for (double dir : viable) {
      OsmNode cp = crosspointFor(dir);
      MatchedWaypoint m = createMatchedWaypoint("m" + (int) dir,
        cp.ilon, cp.ilat, cp.ilon, cp.ilat);
      scored.add(new ProbeDirection(dir, 2, m));
    }
    // startMatch null -> the island guard keeps every via (conservative).
    return new ProbeResult(viable.clone(), scored);
  }

  private OsmNode crosspointFor(double bearing) {
    return new OsmNode(START_ILON + 10000 + (int) (bearing * 37),
      START_ILAT + 10000 + (int) (bearing * 53));
  }

  private MatchedWaypoint matchFor(ProbeResult probe, double bearing) {
    for (ProbeDirection pd : probe.scored) {
      if (pd.direction == bearing) return pd.bestMatch;
    }
    throw new IllegalArgumentException("no match for bearing " + bearing);
  }

  private boolean containsVia(List<OsmNodeNamed> wps, OsmNode node) {
    for (int i = 1; i < wps.size() - 1; i++) { // skip start and closing
      if (wps.get(i).ilon == node.ilon && wps.get(i).ilat == node.ilat) return true;
    }
    return false;
  }

  private void assertUniqueViaNodes(List<OsmNodeNamed> wps) {
    java.util.Set<Long> ids = new java.util.HashSet<>();
    for (int i = 1; i < wps.size() - 1; i++) {
      Assert.assertTrue("duplicate via node at index " + i,
        ids.add(wps.get(i).getIdFromPos()));
    }
  }

}
