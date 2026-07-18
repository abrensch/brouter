package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoundTripFixture;

/**
 * Mirrored tests for {@link RoundTripTrackCleanup} — the spur/detour/teardrop
 * post-processing tests moved out of RoutingEngineTest with the extraction.
 */
public class RoundTripTrackCleanupTest {
  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N

  private RoundTripTrackCleanup cleanup(double searchRadius) {
    RoundTripEngineOps ops =
      RoundTripFixture.dummyRoundTripEngine("trekking", searchRadius).roundTripOps();
    return new RoundTripTrackCleanup(new WaypointSnapper(ops, ops, ops), ops, ops, ops);
  }

  // removeBackAndForthSegments removes overlapping nodes around a waypoint
  @Test
  public void removeBackAndForthAtWaypoint() {
    double searchRadius = 5000;
    RoundTripTrackCleanup cleanup = cleanup(searchRadius);

    // Build a track: A, B, C, W, C, B, D, E
    // where W is a waypoint and C, B appear on both sides (back-and-forth)
    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(1000, 1000, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(2000, 2000, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(3000, 3000, (short) 0, null);
    OsmPathElement nodeW = OsmPathElement.create(4000, 4000, (short) 0, null);
    OsmPathElement nodeC2 = OsmPathElement.create(3000, 3000, (short) 0, null); // same pos as C
    OsmPathElement nodeB2 = OsmPathElement.create(2000, 2000, (short) 0, null); // same pos as B
    OsmPathElement nodeD = OsmPathElement.create(5000, 5000, (short) 0, null);
    OsmPathElement nodeE = OsmPathElement.create(6000, 6000, (short) 0, null);

    track.nodes.add(nodeA);   // 0
    track.nodes.add(nodeB);   // 1
    track.nodes.add(nodeC);   // 2
    track.nodes.add(nodeW);   // 3 - waypoint
    track.nodes.add(nodeC2);  // 4 - duplicate of C
    track.nodes.add(nodeB2);  // 5 - duplicate of B
    track.nodes.add(nodeD);   // 6
    track.nodes.add(nodeE);   // 7

    // Set up waypoints: start at index 0, intermediate waypoint W at index 3, end at index 7
    List<MatchedWaypoint> wpts = new ArrayList<>();
    MatchedWaypoint startWp = createMatchedWaypoint("from", 1000, 1000, 1000, 1000);
    startWp.indexInTrack = 0;
    wpts.add(startWp);

    MatchedWaypoint midWp = createMatchedWaypoint("rt1", 4000, 4000, 4000, 4000);
    midWp.indexInTrack = 3;
    wpts.add(midWp);

    MatchedWaypoint endWp = createMatchedWaypoint("to_rt", 6000, 6000, 6000, 6000);
    endWp.indexInTrack = 7;
    wpts.add(endWp);

    cleanup.removeBackAndForthSegments(track, wpts);

    // After removal: A, B, D, E. The whole B-C-W-C-B spur is removed so the
    // cleanup does not create a synthetic W-D shortcut.
    Assert.assertEquals("should have 4 nodes after removal", 4, track.nodes.size());
    Assert.assertSame("node 0 should be A", nodeA, track.nodes.get(0));
    Assert.assertSame("node 1 should be B", nodeB, track.nodes.get(1));
    Assert.assertSame("node 2 should be D", nodeD, track.nodes.get(2));
    Assert.assertSame("node 3 should be E", nodeE, track.nodes.get(3));

    // Waypoint indices should be updated to the surviving branch/end.
    Assert.assertEquals("mid waypoint index should move to branch", 1, midWp.indexInTrack);
    Assert.assertEquals("end waypoint index should be adjusted", 3, endWp.indexInTrack);
  }

  // removeBackAndForthSegments does nothing when there's no overlap
  @Test
  public void removeBackAndForthNoOverlap() {
    double searchRadius = 5000;
    RoundTripTrackCleanup cleanup = cleanup(searchRadius);

    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(1000, 1000, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(2000, 2000, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(3000, 3000, (short) 0, null);
    OsmPathElement nodeD = OsmPathElement.create(4000, 4000, (short) 0, null);
    OsmPathElement nodeE = OsmPathElement.create(5000, 5000, (short) 0, null);

    track.nodes.add(nodeA);
    track.nodes.add(nodeB);
    track.nodes.add(nodeC);
    track.nodes.add(nodeD);
    track.nodes.add(nodeE);

    List<MatchedWaypoint> wpts = new ArrayList<>();
    MatchedWaypoint startWp = createMatchedWaypoint("from", 1000, 1000, 1000, 1000);
    startWp.indexInTrack = 0;
    wpts.add(startWp);

    MatchedWaypoint midWp = createMatchedWaypoint("rt1", 3000, 3000, 3000, 3000);
    midWp.indexInTrack = 2;
    wpts.add(midWp);

    MatchedWaypoint endWp = createMatchedWaypoint("to_rt", 5000, 5000, 5000, 5000);
    endWp.indexInTrack = 4;
    wpts.add(endWp);

    cleanup.removeBackAndForthSegments(track, wpts);

    Assert.assertEquals("should have 5 nodes (unchanged)", 5, track.nodes.size());
  }

  // removeMicroDetours removes small loops where the route visits the same node twice
  @Test
  public void removeMicroDetoursSimpleLoop() {
    RoundTripTrackCleanup cleanup = cleanup(5000);

    // At ~50N: 1 ilon unit ≈ 0.072m, 1 ilat unit ≈ 0.111m
    // A is 215m from B (well beyond 50m proximity), loop B→C→D→B2 ≈ 126m
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(baseLon, baseLat, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(baseLon + 3000, baseLat, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(baseLon + 3000, baseLat + 400, (short) 0, null);
    OsmPathElement nodeD = OsmPathElement.create(baseLon + 3400, baseLat + 400, (short) 0, null);
    OsmPathElement nodeB2 = OsmPathElement.create(baseLon + 3000, baseLat, (short) 0, null); // same pos as B
    OsmPathElement nodeE = OsmPathElement.create(baseLon + 6000, baseLat, (short) 0, null);

    track.nodes.add(nodeA);
    track.nodes.add(nodeB);
    track.nodes.add(nodeC);
    track.nodes.add(nodeD);
    track.nodes.add(nodeB2);
    track.nodes.add(nodeE);

    cleanup.removeMicroDetours(track, 150, new ArrayList<>());

    // After removal: A, B, E (loop C→D→B2 removed)
    Assert.assertEquals("should have 3 nodes after micro-detour removal", 3, track.nodes.size());
    Assert.assertSame("node 0 should be A", nodeA, track.nodes.get(0));
    Assert.assertSame("node 1 should be B", nodeB, track.nodes.get(1));
    Assert.assertSame("node 2 should be E", nodeE, track.nodes.get(2));
  }

  // removeMicroDetours catches loops returning to a nearby (but not identical) node
  @Test
  public void removeMicroDetoursProximityMatch() {
    RoundTripTrackCleanup cleanup = cleanup(5000);

    // At ~50N: 1 ilon unit ≈ 0.072m, 1 ilat unit ≈ 0.111m
    // B and B2 are ~7m apart (within 50m proximity threshold).
    // A is ~72m from B2 (beyond proximity threshold).
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(baseLon, baseLat, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(baseLon + 1000, baseLat, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(baseLon + 1000, baseLat + 200, (short) 0, null);
    OsmPathElement nodeD = OsmPathElement.create(baseLon + 1200, baseLat + 200, (short) 0, null);
    OsmPathElement nodeB2 = OsmPathElement.create(baseLon + 1050, baseLat + 50, (short) 0, null); // ~7m from B
    OsmPathElement nodeE = OsmPathElement.create(baseLon + 2000, baseLat, (short) 0, null);

    track.nodes.add(nodeA);
    track.nodes.add(nodeB);
    track.nodes.add(nodeC);
    track.nodes.add(nodeD);
    track.nodes.add(nodeB2);
    track.nodes.add(nodeE);

    cleanup.removeMicroDetours(track, 350, new ArrayList<>());

    // Loop B→C→D→B2 (~56m total) should be removed via proximity match
    Assert.assertEquals("should have 3 nodes after proximity detour removal", 3, track.nodes.size());
    Assert.assertSame(nodeA, track.nodes.get(0));
    Assert.assertSame(nodeB, track.nodes.get(1));
    Assert.assertSame(nodeE, track.nodes.get(2));
  }

  // removeMicroDetours does NOT remove loops that are too large
  @Test
  public void removeMicroDetoursKeepsLargeLoop() {
    RoundTripTrackCleanup cleanup = cleanup(5000);

    // Build a track with a large loop using realistic coordinates (~50N, ~8E)
    // Each 1000 units ≈ 0.001 degrees ≈ 70-110m, so 5000 units apart ≈ 350-550m
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(baseLon, baseLat, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(baseLon + 5000, baseLat, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(baseLon + 10000, baseLat, (short) 0, null);
    OsmPathElement nodeB2 = OsmPathElement.create(baseLon + 5000, baseLat, (short) 0, null); // same as B
    OsmPathElement nodeD = OsmPathElement.create(baseLon + 15000, baseLat, (short) 0, null);

    track.nodes.add(nodeA);
    track.nodes.add(nodeB);
    track.nodes.add(nodeC);
    track.nodes.add(nodeB2);
    track.nodes.add(nodeD);

    cleanup.removeMicroDetours(track, 150, new ArrayList<>());

    Assert.assertEquals("should have 5 nodes (loop too large to remove)", 5, track.nodes.size());
  }

  @Test
  public void removeMicroDetoursNoLoop() {
    RoundTripTrackCleanup cleanup = cleanup(5000);

    // Nodes ~132m apart at 50N — well beyond 50m proximity threshold
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 1000, baseLat + 1000, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 2000, baseLat + 2000, (short) 0, null));

    cleanup.removeMicroDetours(track, 150, new ArrayList<>());

    Assert.assertEquals("should have 3 nodes (unchanged)", 3, track.nodes.size());
  }

  @Test
  public void removeMicroDetoursMultipleLoops() {
    RoundTripTrackCleanup cleanup = cleanup(5000);

    // Track: A → B → C → B → D → E → F → E → G
    // Two micro-detours: B→C→B and E→F→E
    // Groups are 3000 units (215m) apart — well beyond proximity threshold
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    OsmPathElement nodeA = OsmPathElement.create(baseLon, baseLat, (short) 0, null);
    OsmPathElement nodeB = OsmPathElement.create(baseLon + 3000, baseLat, (short) 0, null);
    OsmPathElement nodeC = OsmPathElement.create(baseLon + 3000, baseLat + 400, (short) 0, null);
    OsmPathElement nodeB2 = OsmPathElement.create(baseLon + 3000, baseLat, (short) 0, null);
    OsmPathElement nodeD = OsmPathElement.create(baseLon + 6000, baseLat, (short) 0, null);
    OsmPathElement nodeE = OsmPathElement.create(baseLon + 9000, baseLat, (short) 0, null);
    OsmPathElement nodeF = OsmPathElement.create(baseLon + 9000, baseLat + 400, (short) 0, null);
    OsmPathElement nodeE2 = OsmPathElement.create(baseLon + 9000, baseLat, (short) 0, null);
    OsmPathElement nodeG = OsmPathElement.create(baseLon + 12000, baseLat, (short) 0, null);

    track.nodes.add(nodeA);
    track.nodes.add(nodeB);
    track.nodes.add(nodeC);
    track.nodes.add(nodeB2);
    track.nodes.add(nodeD);
    track.nodes.add(nodeE);
    track.nodes.add(nodeF);
    track.nodes.add(nodeE2);
    track.nodes.add(nodeG);

    cleanup.removeMicroDetours(track, 150, new ArrayList<>());

    // After removal: A, B, D, E, G
    Assert.assertEquals("should have 5 nodes after removing two detours", 5, track.nodes.size());
    Assert.assertSame(nodeA, track.nodes.get(0));
    Assert.assertSame(nodeB, track.nodes.get(1));
    Assert.assertSame(nodeD, track.nodes.get(2));
    Assert.assertSame(nodeE, track.nodes.get(3));
    Assert.assertSame(nodeG, track.nodes.get(4));
  }

  // Via-pinned thin teardrop beyond the plain 1500m cap is removed when the
  // span is pinned at a generated round-trip via (greedy planner placement).
  @Test
  public void removeMicroDetoursRemovesViaPinnedThinTeardrop() {
    RoundTripTrackCleanup cleanup = cleanup(5000);
    OsmTrack track = buildViaTeardropTrack();
    OsmPathElement pinchOut = track.nodes.get(3);
    OsmPathElement afterSpur = track.nodes.get(9);

    List<MatchedWaypoint> wpts = new ArrayList<>();
    MatchedWaypoint via = createMatchedWaypoint("via2",
      START_ILON + 205000, START_ILAT + 10800, START_ILON + 205000, START_ILAT + 10800);
    via.generated = true; // greedy planner via — not a user waypoint
    via.indexInTrack = 5;
    wpts.add(via);

    cleanup.removeMicroDetours(track, 1500, wpts);

    Assert.assertEquals("teardrop (arc ~2.5km > plain cap) removed via the via-pinned band",
      6, track.nodes.size());
    Assert.assertSame("pinch-out node survives", pinchOut, track.nodes.get(3));
    Assert.assertSame("track continues after the removed spur", afterSpur, track.nodes.get(4));
  }

  // The same-size span enclosing real area (a fat petal — possibly a scenic
  // sub-loop) is NOT removed: the via-pinned band requires thinness.
  @Test
  public void removeMicroDetoursKeepsFatViaPetal() {
    RoundTripTrackCleanup cleanup = cleanup(5000);
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 100000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 200000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat, (short) 0, null)); // B (pinch-out)
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat + 5600, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 213600, baseLat + 5600, (short) 0, null)); // petal far corner
    track.nodes.add(OsmPathElement.create(baseLon + 213600, baseLat + 200, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205300, baseLat + 100, (short) 0, null)); // B2 (~24m from B)
    track.nodes.add(OsmPathElement.create(baseLon + 208000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 300000, baseLat, (short) 0, null));

    List<MatchedWaypoint> wpts = new ArrayList<>();
    MatchedWaypoint via = createMatchedWaypoint("via2",
      baseLon + 213600, baseLat + 5600, baseLon + 213600, baseLat + 5600);
    via.generated = true;
    via.indexInTrack = 5;
    wpts.add(via);

    cleanup.removeMicroDetours(track, 1500, wpts);

    Assert.assertEquals("fat petal (compactness ~0.8) survives the via-pinned band",
      10, track.nodes.size());
  }

  // Without a generated via pinning the span, the extended band never opens:
  // user waypoints (generated=false, non-"rt" name) keep legacy behaviour.
  @Test
  public void removeMicroDetoursKeepsLargeTeardropWithoutGeneratedVia() {
    RoundTripTrackCleanup cleanup = cleanup(5000);
    OsmTrack track = buildViaTeardropTrack();

    List<MatchedWaypoint> wpts = new ArrayList<>();
    MatchedWaypoint userVia = createMatchedWaypoint("userVia",
      START_ILON + 205000, START_ILAT + 10800, START_ILON + 205000, START_ILAT + 10800);
    userVia.indexInTrack = 5; // generated stays false — a user-chosen destination
    wpts.add(userVia);

    cleanup.removeMicroDetours(track, 1500, wpts);

    Assert.assertEquals("user-via teardrop is the user's choice — kept (legacy cap applies)",
      11, track.nodes.size());
  }

  // removeArtifactSpurSpans: generalized spur repair — removes thin
  // near-revisit excursions even when NOT pinned at a generated via (the
  // start-stem antenna class), keeps fat petals, never touches user vias.
  @Test
  public void removeArtifactSpurSpansRemovesThinSpurWithoutVia() {
    // Request context: ~24km loop requested; removing the 2.4km spur keeps the
    // track at distR ~0.9, above the SPUR_REPAIR_MIN_DISTR floor.
    RoundTripEngineOps ops =
      RoundTripFixture.dummyRoundTripEngine("trekking", 5000, 24000).roundTripOps();
    RoundTripTrackCleanup cleanup =
      new RoundTripTrackCleanup(new WaypointSnapper(ops, ops, ops), ops, ops, ops);
    OsmTrack track = buildViaTeardropTrack();
    OsmPathElement pinchOut = track.nodes.get(3);

    cleanup.removeArtifactSpurSpans(track, new ArrayList<>());

    // Interior removed, both span endpoints kept (pinch-out + rejoin node,
    // ~24m apart): 11 - 4 interior nodes = 7.
    Assert.assertEquals("thin 2.5km spur removed without any via pinning it",
      7, track.nodes.size());
    Assert.assertSame("pinch-out node survives", pinchOut, track.nodes.get(3));
  }

  @Test
  public void removeArtifactSpurSpansKeepsFatPetal() {
    RoundTripTrackCleanup cleanup = cleanup(5000);
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 100000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 200000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat + 5600, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 213600, baseLat + 5600, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 213600, baseLat + 200, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205300, baseLat + 100, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 208000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 300000, baseLat, (short) 0, null));

    cleanup.removeArtifactSpurSpans(track, new ArrayList<>());

    Assert.assertEquals("fat petal (compactness ~0.8, normal cost) is scenic — kept",
      10, track.nodes.size());
  }

  @Test
  public void removeArtifactSpurSpansProtectsUserVia() {
    RoundTripTrackCleanup cleanup = cleanup(5000);
    OsmTrack track = buildViaTeardropTrack();

    List<MatchedWaypoint> wpts = new ArrayList<>();
    wpts.add(createMatchedWaypoint("from", START_ILON, START_ILAT, START_ILON, START_ILAT));
    MatchedWaypoint userVia = createMatchedWaypoint("userVia",
      START_ILON + 205000, START_ILAT + 10800, START_ILON + 205000, START_ILAT + 10800);
    userVia.indexInTrack = 5; // tip of the spur — the user asked to go there
    wpts.add(userVia);
    MatchedWaypoint to = createMatchedWaypoint("to", START_ILON + 300000, START_ILAT,
      START_ILON + 300000, START_ILAT);
    to.indexInTrack = track.nodes.size() - 1;
    wpts.add(to);

    cleanup.removeArtifactSpurSpans(track, wpts);

    Assert.assertEquals("span holds a user via — never repaired", 11, track.nodes.size());
  }

  // petalCompactness: thin out-and-back-ish spans near 0, round petals high.
  @Test
  public void petalCompactnessSeparatesThinTeardropFromFatPetal() {
    OsmTrack thin = buildViaTeardropTrack();
    double thinLoopDist = 0;
    for (int j = 4; j <= 8; j++) {
      thinLoopDist += thin.nodes.get(j).calcDistance(thin.nodes.get(j - 1));
    }
    double thinScore = RoundTripTrackCleanup.petalCompactness(thin.nodes, 3, 8, thinLoopDist);
    Assert.assertTrue("thin teardrop compactness " + thinScore + " below ceiling",
      thinScore <= RoundTripTrackCleanup.VIA_TEARDROP_MAX_COMPACTNESS);

    // Degenerate span (fewer than 2 interior segments) is treated as fat (kept).
    Assert.assertEquals(1.0, RoundTripTrackCleanup.petalCompactness(thin.nodes, 3, 4, 600), 1e-9);
  }

  /**
   * Thin teardrop pinned at a generated via, ~2.5km arc on a ~24km track:
   * out 1.2km north, cross 72m, back down a parallel arm, rejoining within
   * ~24m of the divergence point. The arms are ~72m apart (beyond the 50m
   * proximity threshold) so only the pinch pair matches — the shape the
   * anti-reuse penalty produces at a dead-end via, which the symmetric
   * back-and-forth remover cannot strip. Node indices: 0..2 corridor,
   * 3 = pinch-out B, 4..7 = excursion (tip at index 5), 8 = pinch-return B2,
   * 9..10 corridor.
   */
  private OsmTrack buildViaTeardropTrack() {
    int baseLon = START_ILON;
    int baseLat = START_ILAT;
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(baseLon, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 100000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 200000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat, (short) 0, null)); // B (pinch-out)
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat + 5400, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205000, baseLat + 10800, (short) 0, null)); // tip
    track.nodes.add(OsmPathElement.create(baseLon + 206000, baseLat + 10800, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 206000, baseLat + 5400, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 205300, baseLat + 100, (short) 0, null)); // B2 (~24m from B)
    track.nodes.add(OsmPathElement.create(baseLon + 208000, baseLat, (short) 0, null));
    track.nodes.add(OsmPathElement.create(baseLon + 300000, baseLat, (short) 0, null));
    return track;
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
}
