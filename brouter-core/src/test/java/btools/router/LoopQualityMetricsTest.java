package btools.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LoopQualityMetricsTest {

  // ~8.72E, ~50.0N in BRouter internal coords
  private static final int BASE_ILON = 188720000;
  private static final int BASE_ILAT = 140000000;

  @Test
  public void noReuseOnSimpleLoop() {
    // Square loop: A -> B -> C -> D -> A (no edge traversed twice)
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT + 10000, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT + 10000, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));

    double reuse = LoopQualityMetrics.computeRoadReusePercent(nodes);
    Assert.assertEquals("clean loop should have 0% reuse", 0.0, reuse, 0.1);
  }

  @Test
  public void fullReuseOnOutAndBack() {
    // Out-and-back: A -> B -> C -> B -> A (every edge traversed twice)
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 5000, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 5000, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));

    double reuse = LoopQualityMetrics.computeRoadReusePercent(nodes);
    Assert.assertEquals("out-and-back should have ~50% reuse", 50.0, reuse, 1.0);
  }

  @Test
  public void distanceRatioExact() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.distance = 5000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 5000, 0);
    Assert.assertEquals("distance ratio should be 1.0", 1.0, m.getDistanceRatio(), 0.01);
  }

  @Test
  public void distanceRatioDouble() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.distance = 10000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 5000, 0);
    Assert.assertEquals("distance ratio should be 2.0", 2.0, m.getDistanceRatio(), 0.01);
  }

  @Test
  public void directionDeltaNorth() {
    // Track heading north (increasing lat, same lon)
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT + 10000, (short) 0, null));

    double delta = LoopQualityMetrics.computeDirectionDelta(nodes, 0);
    Assert.assertTrue("heading north with direction 0 should have small delta, got " + delta,
      delta < 15);
  }

  @Test
  public void directionDeltaEast() {
    // Track heading east (increasing lon, same lat)
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));

    double delta = LoopQualityMetrics.computeDirectionDelta(nodes, 90);
    Assert.assertTrue("heading east with direction 90 should have small delta, got " + delta,
      delta < 15);
  }

  @Test
  public void directionDeltaOpposite() {
    // Track heading north, requested south
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT + 10000, (short) 0, null));

    double delta = LoopQualityMetrics.computeDirectionDelta(nodes, 180);
    Assert.assertTrue("opposite direction should have ~180 delta, got " + delta,
      delta > 150);
  }

  @Test
  public void singleNodeTrack() {
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));

    Assert.assertEquals(0.0, LoopQualityMetrics.computeRoadReusePercent(nodes), 0.01);
    Assert.assertEquals(0.0, LoopQualityMetrics.computeDirectionDelta(nodes, 90), 0.01);
  }

  @Test
  public void emptyTrack() {
    List<OsmPathElement> nodes = new ArrayList<>();

    Assert.assertEquals(0.0, LoopQualityMetrics.computeRoadReusePercent(nodes), 0.01);
    Assert.assertEquals(0.0, LoopQualityMetrics.computeDirectionDelta(nodes, 0), 0.01);
  }

  @Test
  public void toStringFormat() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.distance = 5000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 5000, 0);
    String s = m.toString();
    Assert.assertTrue("toString should contain reuse", s.contains("reuse="));
    Assert.assertTrue("toString should contain distRatio", s.contains("distRatio="));
    Assert.assertTrue("toString should contain dirDelta", s.contains("dirDelta="));
  }
}
