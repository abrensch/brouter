package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;


/**
 * Mirrored tests for {@link PlacementGeometry} — moved out of RoutingEngineTest
 * when the round-trip placement statics moved into this package.
 */
public class PlacementGeometryTest {
  @Test
  public void selectSpreadDirectionsFullCircle() {
    // All 24 directions viable — should pick evenly spaced subset
    double[] viable = new double[24];
    for (int i = 0; i < 24; i++) viable[i] = i * 15;

    double[] selected = PlacementGeometry.selectSpreadDirections(viable, 4, 0);
    Assert.assertEquals(4, selected.length);

    // Should be roughly 90° apart
    for (int i = 0; i < selected.length; i++) {
      for (int j = i + 1; j < selected.length; j++) {
        double diff = PlacementGeometry.angleDiff(selected[i], selected[j]);
        Assert.assertTrue("selected directions should be well-spread, got diff=" + diff,
          diff >= 60);
      }
    }
  }

  @Test
  public void selectSpreadDirectionsValley() {
    // Only E-W directions viable (simulating an E-W valley)
    double[] viable = {60, 75, 90, 105, 120, 240, 255, 270, 285, 300};

    double[] selected = PlacementGeometry.selectSpreadDirections(viable, 5, 90);
    Assert.assertEquals(5, selected.length);

    // First selected should be close to startDirection (90)
    Assert.assertTrue("first should be near 90°, got " + selected[0],
      PlacementGeometry.angleDiff(selected[0], 90) <= 15);
  }

  @Test
  public void selectSpreadDirectionsFewerThanNeeded() {
    // Callers (placeWaypointsFromEnvelope etc.) cap 'needed' to viable.length
    // before calling selectSpreadDirections. When called with count <= viable.length,
    // it correctly selects all of them.
    double[] viable = {0, 90, 180};
    double[] selected = PlacementGeometry.selectSpreadDirections(viable, 3, 0);
    Assert.assertEquals(3, selected.length);
    java.util.Set<Double> selectedSet = new java.util.HashSet<>();
    for (double d : selected) selectedSet.add(d);
    Assert.assertTrue("should contain 0", selectedSet.contains(0.0));
    Assert.assertTrue("should contain 90", selectedSet.contains(90.0));
    Assert.assertTrue("should contain 180", selectedSet.contains(180.0));
  }

  @Test
  public void sortDirectionsForLoop() {
    double[] dirs = {270, 90, 180, 0};
    double[] sorted = PlacementGeometry.sortDirectionsForLoop(dirs, 0);
    // Should be ordered: 0, 90, 180, 270 (clockwise from north)
    Assert.assertEquals(0, sorted[0], 0.01);
    Assert.assertEquals(90, sorted[1], 0.01);
    Assert.assertEquals(180, sorted[2], 0.01);
    Assert.assertEquals(270, sorted[3], 0.01);
  }

  @Test
  public void sortDirectionsForLoopStartingSouth() {
    double[] dirs = {270, 90, 180, 0};
    double[] sorted = PlacementGeometry.sortDirectionsForLoop(dirs, 180);
    // Starting from south (180): 180, 270, 0, 90
    Assert.assertEquals(180, sorted[0], 0.01);
    Assert.assertEquals(270, sorted[1], 0.01);
    Assert.assertEquals(0, sorted[2], 0.01);
    Assert.assertEquals(90, sorted[3], 0.01);
  }

  @Test
  public void computeLoopPerimeterFactorFullCircle() {
    // 4 waypoints at 0, 90, 180, 270 — a full circle
    double[] sorted = PlacementGeometry.sortDirectionsForLoop(new double[]{0, 90, 180, 270}, 0);
    double factor = PlacementGeometry.computeLoopPerimeterFactor(sorted);
    // 3 chords of 90° each: 3 × 2×sin(45°) = 3 × 1.414 = 4.243, plus 2 radial = 6.243
    Assert.assertEquals(6.243, factor, 0.01);
  }

  @Test
  public void computeReferencePerimeterFactorFivePoints() {
    // 5 targetPoints → 4 intermediates, arc span = 2×(90-36) = 108°, 3 gaps of 36°
    double factor = PlacementGeometry.computeReferencePerimeterFactor(5);
    // 3 × 2×sin(18°) + 2 = 3 × 0.618 + 2 = 3.854
    Assert.assertEquals(3.854, factor, 0.02);
  }

  @Test
  public void computeRadiusScaleFullCircle() {
    // Wide loop should get a scale < 1.0 to match v1.7.8's narrow arc
    double[] dirs = {0, 90, 180, 270};
    double[] sorted = PlacementGeometry.sortDirectionsForLoop(dirs, 0);
    double scale = PlacementGeometry.computeRadiusScale(sorted, 5);
    // refPerim(5) / actualPerim(360°) = 3.854 / 6.243 ≈ 0.617
    Assert.assertTrue("scale should be < 1.0", scale < 1.0);
    Assert.assertTrue("scale should be > 0.5", scale > 0.5);
    Assert.assertEquals(0.617, scale, 0.02);
  }

  @Test
  public void computeRadiusScaleNarrowArc() {
    // Narrow arc similar to v1.7.8 should give scale ≈ 1.0
    double[] dirs = {340, 350, 10, 20};
    double[] sorted = PlacementGeometry.sortDirectionsForLoop(dirs, 0);
    double scale = PlacementGeometry.computeRadiusScale(sorted, 5);
    Assert.assertTrue("narrow arc should give scale close to 1.0", scale >= 0.9);
  }

  @Test
  public void filterByProbeConfidenceDropsWeakWhenEnoughStrong() {
    // 8 strong (2 snaps) + 4 weak (1 snap), threshold max(5, 8) = 8 -> drop weak.
    ProbeResult probe = probeWithStrongCounts(12, 8);
    double[] kept = PlacementGeometry.filterByProbeConfidence(probe, 5);
    Assert.assertEquals(8, kept.length);
    for (double d : kept) {
      Assert.assertTrue("weak bearing " + d + " kept", d < 8 * 30);
    }
  }

  @Test
  public void filterByProbeConfidenceKeepsWeakWhenFewStrong() {
    // threshold max(9, 8) = 9 > 8 strong -> constrained terrain, keep all.
    ProbeResult probe = probeWithStrongCounts(12, 8);
    double[] kept = PlacementGeometry.filterByProbeConfidence(probe, 9);
    Assert.assertEquals(12, kept.length);
  }

  /** Probe whose first {@code strong} bearings (i*30°) have 2 snaps, the rest 1. */
  private static ProbeResult probeWithStrongCounts(int total, int strong) {
    double[] viable = new double[total];
    List<ProbeDirection> scored = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      viable[i] = i * 30;
      scored.add(new ProbeDirection(viable[i], i < strong ? 2 : 1, null));
    }
    return new ProbeResult(viable, scored);
  }

}
