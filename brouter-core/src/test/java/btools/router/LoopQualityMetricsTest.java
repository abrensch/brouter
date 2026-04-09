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
    Assert.assertTrue("toString should contain continuity", s.contains("continuity="));
    Assert.assertTrue("toString should contain compactness", s.contains("compactness="));
    Assert.assertTrue("toString should contain cost/m", s.contains("cost/m="));
    Assert.assertTrue("toString should contain closure", s.contains("closure="));
    Assert.assertTrue("toString should contain composite", s.contains("composite="));
  }

  // --- Continuity score tests ---

  @Test
  public void continuityPerfectNoGaps() {
    // Points spaced ~7m apart (well under 100m threshold) — no gaps
    List<OsmPathElement> nodes = new ArrayList<>();
    for (int i = 0; i <= 100; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + i * 100, BASE_ILAT, (short) 0, null));
    }
    int[] gapInfo = LoopQualityMetrics.computeGapInfo(nodes);
    Assert.assertEquals("no gaps expected in dense track", 0, gapInfo[0]);
    Assert.assertEquals("no total gap expected in dense track", 0, gapInfo[1]);

    // Full compute: continuity should be 1.0
    OsmTrack track = new OsmTrack();
    track.nodes.addAll(nodes);
    track.distance = 700; // approximate
    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 700, 0);
    Assert.assertEquals("continuity should be 1.0 for gapless track", 1.0, m.getContinuityScore(), 0.01);
  }

  @Test
  public void continuityWithKnownGap() {
    // Build a track with a 500m gap in the middle
    // Normal spacing: ~7m per step. Then a single 500m jump.
    List<OsmPathElement> nodes = new ArrayList<>();
    // First segment: 10 points spaced ~7m apart
    for (int i = 0; i < 10; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + i * 100, BASE_ILAT, (short) 0, null));
    }
    // Gap: jump ~500m (about 7000 ilon units at this latitude)
    nodes.add(OsmPathElement.create(BASE_ILON + 7000000, BASE_ILAT, (short) 0, null));
    // After gap: a few more points
    for (int i = 1; i <= 5; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + 7000000 + i * 100, BASE_ILAT, (short) 0, null));
    }

    int[] gapInfo = LoopQualityMetrics.computeGapInfo(nodes);
    Assert.assertTrue("maxGap should be > 100m", gapInfo[0] > 100);
    Assert.assertTrue("totalGap should be > 0", gapInfo[1] > 0);
  }

  // --- Compactness score tests ---

  @Test
  public void compactnessCircularTrack() {
    // Build a roughly circular track with enough points
    List<OsmPathElement> nodes = new ArrayList<>();
    int numPoints = 100;
    int radius = 100000; // in ilon/ilat units, roughly 7km
    for (int i = 0; i < numPoints; i++) {
      double angle = 2.0 * Math.PI * i / numPoints;
      int ilon = BASE_ILON + (int) (radius * Math.cos(angle));
      int ilat = BASE_ILAT + (int) (radius * Math.sin(angle));
      nodes.add(OsmPathElement.create(ilon, ilat, (short) 0, null));
    }
    // Close the loop
    nodes.add(OsmPathElement.create(BASE_ILON + radius, BASE_ILAT, (short) 0, null));

    // Estimate the circumference to use as distance
    double circumference = 0;
    for (int i = 1; i < nodes.size(); i++) {
      circumference += nodes.get(i - 1).calcDistance(nodes.get(i));
    }

    double compactness = LoopQualityMetrics.computeCompactnessScore(nodes, (int) circumference);
    Assert.assertTrue("circular track should have compactness > 0.3, got " + compactness,
      compactness > 0.3);
  }

  @Test
  public void compactnessLinearTrack() {
    // A straight line out and back — should have near-zero compactness
    List<OsmPathElement> nodes = new ArrayList<>();
    for (int i = 0; i <= 50; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + i * 1000, BASE_ILAT, (short) 0, null));
    }
    // Come back along the same line (offset slightly in lat so not exact same points)
    for (int i = 50; i >= 0; i--) {
      nodes.add(OsmPathElement.create(BASE_ILON + i * 1000, BASE_ILAT + 10, (short) 0, null));
    }

    // Total distance is roughly 2 * 50 * ~70m = ~7000m
    double totalDist = 0;
    for (int i = 1; i < nodes.size(); i++) {
      totalDist += nodes.get(i - 1).calcDistance(nodes.get(i));
    }

    double compactness = LoopQualityMetrics.computeCompactnessScore(nodes, (int) totalDist);
    Assert.assertTrue("linear track should have compactness < 0.1, got " + compactness,
      compactness < 0.1);
  }

  @Test
  public void compactnessTooFewPoints() {
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(OsmPathElement.create(BASE_ILON + 1000, BASE_ILAT, (short) 0, null));

    double compactness = LoopQualityMetrics.computeCompactnessScore(nodes, 1000);
    Assert.assertEquals("2-point track should have compactness 0", 0.0, compactness, 0.001);
  }

  // --- Convex hull area tests ---

  @Test
  public void convexHullAreaOfSquare() {
    // Unit square: area should be 1.0
    double[] xs = {0, 1, 1, 0};
    double[] ys = {0, 0, 1, 1};
    double area = LoopQualityMetrics.convexHullArea(xs, ys);
    Assert.assertEquals("unit square area should be 1.0", 1.0, area, 0.001);
  }

  @Test
  public void convexHullAreaOfTriangle() {
    // Triangle with base 4, height 3: area = 6
    double[] xs = {0, 4, 2};
    double[] ys = {0, 0, 3};
    double area = LoopQualityMetrics.convexHullArea(xs, ys);
    Assert.assertEquals("triangle area should be 6.0", 6.0, area, 0.001);
  }

  @Test
  public void convexHullAreaWithCollinearPoints() {
    // All points on a line: area should be 0
    double[] xs = {0, 1, 2, 3};
    double[] ys = {0, 0, 0, 0};
    double area = LoopQualityMetrics.convexHullArea(xs, ys);
    Assert.assertEquals("collinear points should have area 0", 0.0, area, 0.001);
  }

  // --- Composite score tests ---

  @Test
  public void compositeScoreInRange() {
    // Build a simple square loop track
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT + 10000, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT + 10000, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.distance = 5000;
    track.cost = 10000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 5000, 0);
    double composite = m.compositeScore();
    Assert.assertTrue("composite score should be >= 0, got " + composite, composite >= 0.0);
    Assert.assertTrue("composite score should be <= 1, got " + composite, composite <= 1.0);
  }

  @Test
  public void compositeScorePerfectLoop() {
    // Build a good circular loop: distance matches request, no reuse, right direction
    List<OsmPathElement> nodes = new ArrayList<>();
    int numPoints = 60;
    int radius = 100000;
    for (int i = 0; i < numPoints; i++) {
      double angle = 2.0 * Math.PI * i / numPoints;
      int ilon = BASE_ILON + (int) (radius * Math.cos(angle));
      int ilat = BASE_ILAT + (int) (radius * Math.sin(angle));
      nodes.add(OsmPathElement.create(ilon, ilat, (short) 0, null));
    }
    // Close the loop
    nodes.add(nodes.get(0));

    double circumference = 0;
    for (int i = 1; i < nodes.size(); i++) {
      circumference += nodes.get(i - 1).calcDistance(nodes.get(i));
    }

    OsmTrack track = new OsmTrack();
    track.nodes.addAll(nodes);
    track.distance = (int) circumference;
    track.cost = (int) (circumference * 2); // moderate cost

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, (int) circumference, 0);
    double composite = m.compositeScore();
    Assert.assertTrue("good circular loop should score > 0.5, got " + composite, composite > 0.5);
  }

  // --- Closure distance test ---

  @Test
  public void closureDistanceClosed() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.distance = 1400;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 1400, 0);
    Assert.assertTrue("closed loop should have closure < 10m, got " + m.getClosureDistanceMeters(),
      m.getClosureDistanceMeters() < 10);
  }

  @Test
  public void closureDistanceOpen() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 1000000, BASE_ILAT, (short) 0, null));
    track.distance = 70000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 70000, 90);
    Assert.assertTrue("open track should have closure > 50m, got " + m.getClosureDistanceMeters(),
      m.getClosureDistanceMeters() > 50);
  }

  // --- Average cost per meter test ---

  @Test
  public void averageCostPerMeter() {
    OsmTrack track = new OsmTrack();
    track.nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    track.nodes.add(OsmPathElement.create(BASE_ILON + 10000, BASE_ILAT, (short) 0, null));
    track.distance = 1000;
    track.cost = 3000;

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, 1000, 0);
    Assert.assertEquals("cost/m should be 3.0", 3.0, m.getAverageCostPerMeter(), 0.01);
  }
}
