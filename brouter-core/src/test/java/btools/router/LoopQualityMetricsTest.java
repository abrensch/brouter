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

  // ~metre→ilon/ilat factors at this latitude (~50°N): 1 m ≈ 11.7 ilon, 9 ilat.
  private static OsmPathElement m(double xMeters, double yMeters) {
    return OsmPathElement.create(
      BASE_ILON + (int) Math.round(xMeters * 11.7),
      BASE_ILAT + (int) Math.round(yMeters * 9.0), (short) 0, null);
  }

  @Test
  public void parallelCorridorRaisesReuseAboveEdgeIdentity() {
    // Out 2km east at y=0, then back 2km at y=25 — a parallel return on a
    // DIFFERENT way (no shared node/edge). Edge identity sees ~0% reuse; the
    // spatial corridor union must report substantial reuse so RouteChoiceScore
    // ranks this below a clean loop.
    List<OsmPathElement> nodes = new ArrayList<>();
    double[][] corners = {{0, 0}, {2000, 0}, {2000, 25}, {0, 25}, {0, 0}};
    nodes.add(m(corners[0][0], corners[0][1]));
    for (int i = 1; i < corners.length; i++) {
      double[] a = corners[i - 1], b = corners[i];
      double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
      int steps = Math.max(1, (int) Math.ceil(len / 20.0));
      for (int k = 1; k <= steps; k++) {
        double s = (double) k / steps;
        nodes.add(m(a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s));
      }
    }
    double reuse = LoopQualityMetrics.computeRoadReusePercent(nodes);
    Assert.assertTrue("parallel corridor should read substantial reuse, got " + reuse + "%",
      reuse > 25.0);
  }

  // ---- beelineInSpurMeters: chord fingerprint + closure exclusion ----------

  private static MessageData tagged() {
    MessageData md = new MessageData();
    md.wayKeyValues = "highway=track surface=gravel";
    md.costfactor = 1.5f;
    return md;
  }

  /**
   * Loop with a dead-end spur at the start: out via one long (~480m) straight
   * edge, tip hop, back on a parallel arm rejoining within ~49m of the start
   * (arc ~985m — a near-revisit span), then a ~2km-sided square loop body.
   * Every edge is tagged except, when {@code longEdgeTagged} is false, the
   * long spur edge — the raw-fallback chord fingerprint.
   */
  private static OsmTrack spurLoop(boolean longEdgeTagged) {
    OsmTrack t = new OsmTrack();
    t.nodes.add(m(0, 0));
    t.nodes.add(m(480, 0));     // the long spur edge
    t.nodes.add(m(480, 45));    // tip hop
    t.nodes.add(m(20, 45));     // parallel return arm (~49m from start)
    t.nodes.add(m(2000, 0));
    t.nodes.add(m(2000, 2000));
    t.nodes.add(m(0, 2000));
    t.nodes.add(m(0, 0));
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = tagged();
    }
    if (!longEdgeTagged) {
      t.nodes.get(1).message = null; // edge node0→node1 becomes null-way
    }
    return t;
  }

  @Test
  public void beelineInSpurFlagsUntaggedChordInsideSpur() {
    int[] d = LoopQualityMetrics.beelineInSpurDetail(spurLoop(false));
    Assert.assertTrue("untagged ~480m chord inside the spur must be flagged, got " + d[0] + "m",
      d[0] > 400 && d[0] < 560);
    Assert.assertEquals("flagged edge start ilon", BASE_ILON, d[1]);
    Assert.assertEquals("flagged edge end ilon (480m east)",
      BASE_ILON + (int) Math.round(480 * 11.7), d[3]);
  }

  @Test
  public void beelineInSpurIgnoresTaggedLongEdge() {
    Assert.assertEquals("tagged long edge is a real road, not a chord",
      0, LoopQualityMetrics.beelineInSpurMeters(spurLoop(true)));
  }

  @Test
  public void beelineInSpurExcludesLoopOwnClosure() {
    // A plain sub-10km loop closes start≈end with the full perimeter as the
    // "arc" — that span is the loop's own closure, not a spur, and a long
    // null edge in the loop body must NOT be flagged (regression guard:
    // before the closure exclusion this false-fired on every loop under the
    // 10km arc cap).
    OsmTrack t = new OsmTrack();
    t.nodes.add(m(0, 0));
    t.nodes.add(m(2400, 0));    // one long edge, left untagged below
    t.nodes.add(m(2400, 2400));
    t.nodes.add(m(0, 2400));
    t.nodes.add(m(0, 0));
    for (int i = 2; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = tagged();
    }
    Assert.assertEquals("loop closure is not a spur; long null edge outside any spur not flagged",
      0, LoopQualityMetrics.beelineInSpurMeters(t));
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
    // Build a track with a long beeline gap in the middle. A real BRouter beeline
    // carries the direct_segment marker (set in OsmPath.addAddionalPenalty); the gap
    // detector keys off that marker, not the raw jump length.
    List<OsmPathElement> nodes = new ArrayList<>();
    // First segment: 10 points spaced ~7m apart
    for (int i = 0; i < 10; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + i * 100, BASE_ILAT, (short) 0, null));
    }
    // Gap: a long beeline jump tagged as a synthetic direct segment.
    nodes.add(nodeWithTags(BASE_ILON + 7000000, BASE_ILAT, "direct_segment=1"));
    // After gap: a few more points
    for (int i = 1; i <= 5; i++) {
      nodes.add(OsmPathElement.create(BASE_ILON + 7000000 + i * 100, BASE_ILAT, (short) 0, null));
    }

    int[] gapInfo = LoopQualityMetrics.computeGapInfo(nodes);
    Assert.assertTrue("maxGap should be > 100m", gapInfo[0] > 100);
    Assert.assertTrue("totalGap should be > 0", gapInfo[1] > 0);
  }

  /** Helper: a track node reached over a segment carrying the given way tags. */
  private static OsmPathElement nodeWithTags(int ilon, int ilat, String wayKeyValues) {
    OsmPathElement n = OsmPathElement.create(ilon, ilat, (short) 0, null);
    MessageData msg = new MessageData();
    msg.wayKeyValues = wayKeyValues;
    n.message = msg;
    return n;
  }

  @Test
  public void gapDetectionUsesBeelineMarkerNotLength() {
    // ~10000 ilon units ≈ 700m here, comfortably over the 500m beeline floor.
    int farIlon = BASE_ILON + 10000;

    // A long REAL road segment carries genuine way tags (no direct_segment marker)
    // and must NOT be flagged — this is the rural-straight false-positive we fixed.
    List<OsmPathElement> road = new ArrayList<>();
    road.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    road.add(nodeWithTags(farIlon, BASE_ILAT, "highway=tertiary"));
    int[] roadGap = LoopQualityMetrics.computeGapInfo(road);
    Assert.assertEquals("a long real-road segment must not be flagged as a gap", 0, roadGap[0]);
    Assert.assertEquals(0, roadGap[1]);

    // A real BRouter beeline carries the direct_segment marker (set in
    // OsmPath.addAddionalPenalty) and MUST be flagged when long enough.
    List<OsmPathElement> beeline = new ArrayList<>();
    beeline.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    beeline.add(nodeWithTags(farIlon, BASE_ILAT, "direct_segment=3"));
    int[] beelineGap = LoopQualityMetrics.computeGapInfo(beeline);
    Assert.assertTrue("a long direct_segment beeline must be flagged as a gap", beelineGap[0] > 0);
    Assert.assertEquals("the whole beeline span counts as gap", beelineGap[0], beelineGap[1]);
  }

  @Test
  public void shortBeelineConnectorIsNotAGap() {
    // A sub-threshold direct_segment (waypoint-snap connector, ~70m) is not a routing gap.
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(nodeWithTags(BASE_ILON + 1000, BASE_ILAT, "direct_segment=1"));
    int[] gap = LoopQualityMetrics.computeGapInfo(nodes);
    Assert.assertEquals("a short beeline connector must not be flagged", 0, gap[0]);
  }

  @Test
  public void ferrySegmentIsAGapAtAnyLength() {
    List<OsmPathElement> nodes = new ArrayList<>();
    nodes.add(OsmPathElement.create(BASE_ILON, BASE_ILAT, (short) 0, null));
    nodes.add(nodeWithTags(BASE_ILON + 1000, BASE_ILAT, "route=ferry"));
    int[] gap = LoopQualityMetrics.computeGapInfo(nodes);
    Assert.assertTrue("a ferry segment is a gap regardless of length", gap[0] > 0);
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

  // ---- computeCostMatchScore (pure, was untested) -------------------------

  @Test
  public void computeCostMatchScore_plateauFloorAndLinearMidpoint() {
    // ≤ 0 → 0.0 (missing cost data is NOT scored as a perfect match).
    Assert.assertEquals(0.0, LoopQualityMetrics.computeCostMatchScore(0.0), 1e-9);
    Assert.assertEquals(0.0, LoopQualityMetrics.computeCostMatchScore(-1.0), 1e-9);
    // Plateau at/below IDEAL (1.5) → 1.0.
    Assert.assertEquals(1.0, LoopQualityMetrics.computeCostMatchScore(1.0), 1e-9);
    Assert.assertEquals(1.0, LoopQualityMetrics.computeCostMatchScore(1.5), 1e-9);
    // Floor at/above ZERO (4.0) → 0.0.
    Assert.assertEquals(0.0, LoopQualityMetrics.computeCostMatchScore(4.0), 1e-9);
    Assert.assertEquals(0.0, LoopQualityMetrics.computeCostMatchScore(5.0), 1e-9);
    // Linear between: (4.0 - 2.75) / (4.0 - 1.5) = 0.5.
    Assert.assertEquals(0.5, LoopQualityMetrics.computeCostMatchScore(2.75), 1e-9);
  }

  // ---- lasso-crossing weighting in RouteChoiceScore (#9b) ------------------

  /**
   * A ~12km square loop; with {@code lasso} a small (~1.3km enclosed) detour
   * loop on the bottom edge transversely crosses the outbound path once —
   * the lasso classification target ({@code detectCrossings} → smallLoop).
   */
  private static OsmTrack lassoLoop(boolean lasso) {
    OsmTrack t = new OsmTrack();
    t.nodes.add(m(0, 0));
    t.nodes.add(m(1000, 0));
    if (lasso) {
      t.nodes.add(m(1400, 0));
      t.nodes.add(m(1400, 300));
      t.nodes.add(m(1100, 300));
      t.nodes.add(m(1100, -100)); // crosses the (1000,0)-(1400,0) segment at (1100,0)
      t.nodes.add(m(1600, -100));
      t.nodes.add(m(1600, 0));
    }
    t.nodes.add(m(3000, 0));
    t.nodes.add(m(3000, 3000));
    t.nodes.add(m(0, 3000));
    t.nodes.add(m(0, 0));
    int dist = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      dist += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    }
    t.distance = dist;
    t.cost = dist * 2;
    return t;
  }

  @Test
  public void crossings_bridgeOrTunnelEdgeIsExempt() {
    // User label (2026-06-11): a crossing where one pass rides a bridge or
    // tunnel is vertically separated — e.g. a ramp loop built to avoid
    // stairs, or re-crossing a river on a different bridge. Tag the edge
    // that ENDS at (1100,-100) — the second crossing segment — as a bridge.
    OsmTrack t = lassoLoop(true);
    MessageData md = new MessageData();
    md.wayKeyValues = "highway=secondary bridge=yes";
    t.nodes.get(5).message = md; // edge (1100,300)->(1100,-100)
    Assert.assertEquals("bridge crossing not counted by the metric",
      0, LoopQualityMetrics.detectCrossings(t.nodes)[0]);
    Assert.assertEquals("bridge crossing not counted by the gate",
      0, RoundTripQualityGate.countSelfIntersections(t));
    Assert.assertTrue("no marker either",
      LoopQualityMetrics.crossingPoints(t.nodes).isEmpty());
    // Tunnel works the same way.
    md.wayKeyValues = "highway=secondary tunnel=yes";
    Assert.assertEquals(0, RoundTripQualityGate.countSelfIntersections(t));
  }

  @Test
  public void crossings_startEndZoneIsExempt() {
    // User label (2026-06-11): crossings at the very beginning/end of the
    // route are the expected leave-and-return weave. Same lasso geometry,
    // but shifted so the crossing sits ~150m from the route start.
    OsmTrack t = new OsmTrack();
    t.nodes.add(m(0, 0));
    t.nodes.add(m(50, 0));
    t.nodes.add(m(450, 0));
    t.nodes.add(m(450, 300));
    t.nodes.add(m(150, 300));
    t.nodes.add(m(150, -100)); // crosses the (50,0)-(450,0) segment at (150,0), cum ~ 150m
    t.nodes.add(m(650, -100));
    t.nodes.add(m(650, 0));
    t.nodes.add(m(3000, 0));
    t.nodes.add(m(3000, 3000));
    t.nodes.add(m(0, 3000));
    t.nodes.add(m(0, 0));
    Assert.assertEquals("home-zone crossing not counted",
      0, LoopQualityMetrics.detectCrossings(t.nodes)[0]);
    Assert.assertEquals(0, RoundTripQualityGate.countSelfIntersections(t));
    // Control: the identical detour placed mid-edge (cum > 500m) IS counted.
    Assert.assertEquals(1, LoopQualityMetrics.detectCrossings(lassoLoop(true).nodes)[0]);
  }

  @Test
  public void detectCrossings_classifiesLassoBySmallEnclosedArc() {
    int[] lasso = LoopQualityMetrics.detectCrossings(lassoLoop(true).nodes);
    Assert.assertEquals("one crossing", 1, lasso[0]);
    Assert.assertEquals("classified as lasso (enclosed ~1.3km <= 4km)", 1, lasso[1]);
    int[] clean = LoopQualityMetrics.detectCrossings(lassoLoop(false).nodes);
    Assert.assertEquals(0, clean[0]);
  }

  @Test
  public void routeChoiceScore_lassoSurchargeIsRankingOnlyAndSizeFaded() {
    OsmTrack track = lassoLoop(true);
    RouteChoiceScore.Verdict v = RouteChoiceScore.score(track, track.distance, "gravel", null, -1);
    boolean hasLassoReason = false;
    for (RouteChoiceScore.Reason r : v.reasons()) {
      if (r.label.startsWith("lasso crossings sev")) {
        hasLassoReason = true;
      }
    }
    Assert.assertTrue("lasso surcharge reason present: " + v.reasons(), hasLassoReason);
    // Size-faded severity: the fixture's enclosed loop is ~1.3km of the 4km
    // cap, so the surcharge must be strictly between 0 and the full extra
    // (user label: a big-enough detour loop is fine — severity fades with size).
    double expectedSeverity = 0;
    for (double[] x : LoopQualityMetrics.crossingPoints(track.nodes)) {
      if (x[2] <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS) {
        expectedSeverity += 1.0 - x[2] / LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS;
      }
    }
    Assert.assertTrue("severity in (0,1): " + expectedSeverity,
      expectedSeverity > 0 && expectedSeverity < 1);
    // Ranking-only: the crossing + lasso penalties are excluded from the soft
    // quality floor (no teardrop in this geometry, so the gap is exactly #9 + #9b).
    Assert.assertEquals(
      RouteChoiceScore.SHAPE_PENALTY_PER_SELF_INTERSECTION
        + RouteChoiceScore.SHAPE_PENALTY_LASSO_EXTRA * expectedSeverity,
      v.qualityScore() - v.score(), 1e-9);
  }

  @Test
  public void lassoEffectiveWeightStaysInReviewBand() {
    // Loop-review item 3 calls for lassos at 2-3× a structural crossing. With
    // size fading this is the MAX effective weight (severity → 1 for a tiny
    // circle); a near-cap loop fades toward 1× (user label: big = fine).
    double maxEffective = (RouteChoiceScore.SHAPE_PENALTY_PER_SELF_INTERSECTION
      + RouteChoiceScore.SHAPE_PENALTY_LASSO_EXTRA) / RouteChoiceScore.SHAPE_PENALTY_PER_SELF_INTERSECTION;
    Assert.assertTrue("max effective lasso weight " + maxEffective + " in [2,3]",
      maxEffective >= 2.0 && maxEffective <= 3.0);
  }

  @Test
  public void crossingPoints_locatesTheLassoIntersection() {
    // The render-side highlighter must place the marker AT the crossing:
    // the fixture's arms cross at x=1100m, y=0 (see lassoLoop).
    List<double[]> pts = LoopQualityMetrics.crossingPoints(lassoLoop(true).nodes);
    Assert.assertEquals(1, pts.size());
    OsmPathElement expected = m(1100, 0);
    OsmPathElement actual = OsmPathElement.create(
      (int) Math.round((pts.get(0)[0] + 180.0) * 1e6),
      (int) Math.round((pts.get(0)[1] + 90.0) * 1e6), (short) 0, null);
    Assert.assertTrue("marker within 30m of the true crossing",
      expected.calcDistance(actual) <= 30);
    Assert.assertTrue("classified small (enclosed <= 4km)",
      pts.get(0)[2] <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS);
  }

  // ---- fromFields rehydration (field-order transposition guard) -----------

  @Test
  public void fromFields_roundTripsEveryGetter() {
    LoopQualityMetrics m = LoopQualityMetrics.fromFields(
      12.5,   // roadReusePercent
      1.05,   // distanceRatio
      33.0,   // directionDeltaDegrees
      21000,  // actualDistanceMeters
      20000,  // requestedDistanceMeters
      0.97,   // continuityScore
      250,    // maxGapMeters
      600,    // totalGapMeters
      0.8,    // compactnessScore
      1.4,    // averageCostPerMeter
      120,    // closureDistanceMeters
      2,      // spurCount
      3200,   // worstSpurMeters
      4,      // selfIntersections
      1);     // smallLoopCrossings
    Assert.assertEquals(12.5, m.getRoadReusePercent(), 1e-9);
    Assert.assertEquals(1.05, m.getDistanceRatio(), 1e-9);
    Assert.assertEquals(33.0, m.getDirectionDeltaDegrees(), 1e-9);
    Assert.assertEquals(21000, m.getActualDistanceMeters());
    Assert.assertEquals(20000, m.getRequestedDistanceMeters());
    Assert.assertEquals(0.97, m.getContinuityScore(), 1e-9);
    Assert.assertEquals(250, m.getMaxGapMeters());
    Assert.assertEquals(600, m.getTotalGapMeters());
    Assert.assertEquals(0.8, m.getCompactnessScore(), 1e-9);
    Assert.assertEquals(1.4, m.getAverageCostPerMeter(), 1e-9);
    Assert.assertEquals(120, m.getClosureDistanceMeters());
    Assert.assertEquals(2, m.getSpurCount());
    Assert.assertEquals(3200, m.getWorstSpurMeters());
    Assert.assertEquals(4, m.getSelfIntersections());
    Assert.assertEquals(1, m.getSmallLoopCrossings());
  }
}
