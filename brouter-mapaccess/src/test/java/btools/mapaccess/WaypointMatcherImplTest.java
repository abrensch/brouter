package btools.mapaccess;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WaypointMatcherImplTest {

  // Junction node J (~8.7E, 50N in BRouter internal coords).
  private static final int LON_J = 188_700_000;
  private static final int LAT_J = 140_000_000;

  /**
   * Regression: {@code mwp.wayDescription} must belong to the way of the
   * SELECTED candidate ({@code wayNearest.get(0)}), not to the way that last
   * updated the mutable waypoint in {@code checkSegment}.
   *
   * <p>Setup forces an exact radius tie at a shared junction node J: two
   * collinear east-west ways (A: P→J, B: J→Q) at the same latitude, and a
   * waypoint exactly north of J. Both ways snap to J with bit-identical
   * radius (same segment mean latitude → same meter scales). Way A is fed
   * first and wins {@code get(0)} by stable sort (equal radius, equal
   * directionDiff), so the selected geometry is A's — but way B is fed second
   * and {@code checkSegment} left B's tags on the waypoint. Before the fix,
   * snap scoring then evaluated B's tags against A's geometry.
   */
  @Test
  public void wayDescriptionMatchesSelectedCandidateOnRadiusTie() {
    int lonP = LON_J - 10_000; // ~715m west of J
    int lonQ = LON_J + 10_000; // ~715m east of J

    MatchedWaypoint wp0 = new MatchedWaypoint();
    wp0.waypoint = new OsmNode(LON_J, LAT_J + 900); // ~100m exactly north of J
    wp0.name = "wp0";
    // Direction anchor far east so wp0.directionToNext ≈ east, matching both
    // ways' forward traversal (A: P→J and B: J→Q both head east).
    MatchedWaypoint wp1 = new MatchedWaypoint();
    wp1.waypoint = new OsmNode(LON_J + 1_000_000, LAT_J);
    wp1.name = "wp1";

    List<MatchedWaypoint> waypoints = new ArrayList<>();
    waypoints.add(wp0);
    waypoints.add(wp1);

    WaypointMatcherImpl matcher = new WaypointMatcherImpl(
      waypoints, 250., new OsmNodePairSet(1));

    byte[] tagsA = {1, 2, 3};
    byte[] tagsB = {4, 5, 6};

    // Way A: P → J (fed first; should be the selected candidate).
    Assert.assertTrue(matcher.start(lonP, LAT_J, LON_J, LAT_J, true, tagsA));
    matcher.end();
    // Way B: J → Q (fed second; same snap node J, identical radius —
    // checkSegment overwrites wp0.wayDescription with B's tags).
    Assert.assertTrue(matcher.start(LON_J, LAT_J, lonQ, LAT_J, true, tagsB));
    matcher.end();

    // Sanity: the tie happened and way A's candidate won the ranking.
    Assert.assertFalse("waypoint should have snap candidates", wp0.wayNearest.isEmpty());
    MatchedWaypoint selected = wp0.wayNearest.get(0);
    Assert.assertEquals("way A's forward candidate should rank first (stable sort on full tie)",
      lonP, selected.node1.ilon);
    Assert.assertEquals("selected geometry copied into the waypoint",
      lonP, wp0.node1.ilon);

    // The actual regression: tags must be the SELECTED way's (A), not the
    // last checkSegment writer's (B).
    Assert.assertSame("wayDescription must match the selected candidate's way",
      tagsA, wp0.wayDescription);
  }

  /** Normal case (strictly closer second way): tags follow the winner. */
  @Test
  public void wayDescriptionFollowsStrictlyCloserWay() {
    MatchedWaypoint wp0 = new MatchedWaypoint();
    wp0.waypoint = new OsmNode(LON_J, LAT_J + 900);
    wp0.name = "wp0";
    MatchedWaypoint wp1 = new MatchedWaypoint();
    wp1.waypoint = new OsmNode(LON_J + 1_000_000, LAT_J);
    wp1.name = "wp1";
    List<MatchedWaypoint> waypoints = new ArrayList<>();
    waypoints.add(wp0);
    waypoints.add(wp1);

    WaypointMatcherImpl matcher = new WaypointMatcherImpl(
      waypoints, 250., new OsmNodePairSet(1));

    byte[] tagsFar = {7};
    byte[] tagsNear = {8};

    // Far way: east-west, ~178m north of the waypoint.
    Assert.assertTrue(matcher.start(LON_J - 10_000, LAT_J + 2_500, LON_J + 10_000, LAT_J + 2_500, true, tagsFar));
    matcher.end();
    // Near way: east-west through J, ~100m south of the waypoint — closer.
    Assert.assertTrue(matcher.start(LON_J - 10_000, LAT_J, LON_J + 10_000, LAT_J, true, tagsNear));
    matcher.end();

    Assert.assertSame("closer way's tags selected", tagsNear, wp0.wayDescription);
  }
}
