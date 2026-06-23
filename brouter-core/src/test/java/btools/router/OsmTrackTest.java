package btools.router;

import org.junit.Assert;
import org.junit.Test;

/**
 * Edge-membership semantics for the anti-reuse refTrack penalty (loop-review
 * backlog item 2): {@link OsmTrack#containsTraveledSegment} must contain the
 * segments the track walked — and must NOT contain a fresh connector between
 * two visited nodes, which is exactly the false-positive the old
 * both-endpoints containsNode() test produced.
 */
public class OsmTrackTest {

  private static OsmTrack track(int[][] lonLat) {
    OsmTrack t = new OsmTrack();
    for (int[] p : lonLat) {
      t.nodes.add(OsmPathElement.create(p[0], p[1], (short) 0, null));
    }
    t.buildMap();
    return t;
  }

  private static long id(int ilon, int ilat) {
    return ((long) ilon) << 32 | ilat;
  }

  private static final int[][] A_B_C_D = {
    {188720000, 140000000}, // A
    {188730000, 140000000}, // B
    {188730000, 140010000}, // C
    {188720000, 140010000}, // D
  };

  @Test
  public void traveledSegmentsAreMembers_bothDirections() {
    OsmTrack t = track(A_B_C_D);
    Assert.assertTrue(t.containsTraveledSegment(id(188720000, 140000000), id(188730000, 140000000)));
    Assert.assertTrue("direction-insensitive",
      t.containsTraveledSegment(id(188730000, 140000000), id(188720000, 140000000)));
    Assert.assertTrue(t.containsTraveledSegment(id(188730000, 140010000), id(188720000, 140010000)));
  }

  @Test
  public void freshConnectorBetweenVisitedNodesIsNotAMember() {
    OsmTrack t = track(A_B_C_D);
    // A and D are both visited, but the track never traveled A-D — the old
    // node-set test taxed this fresh connector, this one must not.
    Assert.assertTrue(t.containsNode(t.nodes.get(0)));
    Assert.assertTrue(t.containsNode(t.nodes.get(3)));
    Assert.assertFalse(t.containsTraveledSegment(id(188720000, 140000000), id(188720000, 140010000)));
    // Diagonal B-D: also unvisited.
    Assert.assertFalse(t.containsTraveledSegment(id(188730000, 140000000), id(188720000, 140010000)));
  }

  @Test
  public void cacheInvalidatedWhenTrackGrows() {
    OsmTrack t = track(new int[][]{{188720000, 140000000}, {188730000, 140000000}});
    Assert.assertFalse(t.containsTraveledSegment(id(188730000, 140000000), id(188730000, 140010000)));
    t.nodes.add(OsmPathElement.create(188730000, 140010000, (short) 0, null));
    t.buildMap(); // mutation always re-buildMaps in production callers
    Assert.assertTrue(t.containsTraveledSegment(id(188730000, 140000000), id(188730000, 140010000)));
  }

  @Test
  public void reuseFraction_separatesRetraceFromFreshLeg() {
    OsmTrack ref = track(A_B_C_D);
    // Full retrace: same corridor back.
    OsmTrack retrace = track(new int[][]{A_B_C_D[3], A_B_C_D[2], A_B_C_D[1], A_B_C_D[0]});
    Assert.assertEquals(1.0, GreedyRoundTripPlanner.reuseFraction(retrace, ref), 1e-9);
    // Fresh return well clear of the reference corridor.
    OsmTrack fresh = track(new int[][]{
      {188720000, 140050000}, {188730000, 140050000}, {188740000, 140050000}});
    Assert.assertEquals(0.0, GreedyRoundTripPlanner.reuseFraction(fresh, ref), 1e-9);
  }
}
