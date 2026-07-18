package btools.router.roundtrip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the {@link IsoPoolHealth} pool-trust model (issue #26).
 * Pure-function tests — synthetic {@link IsoPoolHealth.PoolShape}s and
 * hand-driven dynamic evidence, no engine or segment data.
 */
public class IsoPoolHealthTest {

  /** A rich pool: full circle, many sectors, all contours, oracle calibrated. */
  private static IsoPoolHealth.PoolShape richShape() {
    return new IsoPoolHealth.PoolShape(24, 12, 360.0, 4, true);
  }

  /** The weakest pool buildCandidateProvider still admits to the blend. */
  private static IsoPoolHealth.PoolShape minimalShape() {
    return new IsoPoolHealth.PoolShape(6, 4, 180.0, 1, false);
  }

  @Test
  public void richPoolStartsHealthyWithFullScore() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    assertEquals(1.0, h.score(), 1e-9);
    assertEquals(IsoPoolHealth.State.HEALTHY, h.state());
    assertFalse(h.influenceReduced());
    assertFalse(h.graphNativeOnly());
  }

  @Test
  public void minimalAdmittedPoolStartsDegradedButNotUnhealthy() {
    // 4 sectors / 180° span / one contour / no oracle is exactly the corridor-
    // adjacent pool the influence reduction is for: it must start DEGRADED
    // (prior terms stripped from step 1) yet stay above the graph-native-only
    // bar — demoting to UNHEALTHY requires actual in-plan evidence.
    IsoPoolHealth h = new IsoPoolHealth(minimalShape());
    assertEquals("calibration anchor: weakest admitted shape scores 0.50",
      0.50, h.score(), 1e-9);
    assertEquals(IsoPoolHealth.State.DEGRADED, h.state());
    assertTrue(h.influenceReduced());
    assertFalse("static shape alone must never reach UNHEALTHY", h.graphNativeOnly());
  }

  @Test
  public void staticDeductionsScaleBetweenPoorAndGoodShape() {
    // Mid-shape pool: sectors 7 of [4..10], span 270 of [180..360], multi-
    // contour, oracle present → partial deductions only.
    IsoPoolHealth h = new IsoPoolHealth(new IsoPoolHealth.PoolShape(12, 7, 270.0, 3, true));
    double expected = 1.0
      - IsoPoolHealth.W_SECTORS * (10 - 7) / 6.0
      - IsoPoolHealth.W_SPAN * (360.0 - 270.0) / 180.0;
    assertEquals(expected, h.score(), 1e-9);
    assertEquals(IsoPoolHealth.State.HEALTHY, h.state());
  }

  @Test
  public void isoWinsNeverDeduct() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    for (int i = 0; i < 20; i++) {
      h.recordRoutedComparison(true, false);
    }
    assertEquals("iso wins are not evidence against the pool", 1.0, h.score(), 1e-9);
  }

  @Test
  public void repeatedGraphNativeWinsDegradeTheRichPool() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    h.recordRoutedComparison(false, false);
    assertEquals(1.0 - IsoPoolHealth.W_GRAPH_NATIVE_WIN, h.score(), 1e-9);
    assertEquals("one honest loss is not a demotion",
      IsoPoolHealth.State.HEALTHY, h.state());

    h.recordRoutedComparison(false, false);
    h.recordRoutedComparison(false, false);
    assertEquals("three repeated graph-native wins are enough evidence to demote",
      IsoPoolHealth.State.DEGRADED, h.state());
    assertTrue(h.influenceReduced());
    assertFalse("graph-native wins alone still must not force graph-native-only",
      h.graphNativeOnly());
  }

  @Test
  public void quotaInjectedWinsDeductExtraAndReachDegraded() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    // Three quota-injected graph-native wins: the pool actively outranked
    // candidates that then won on routed truth.
    h.recordRoutedComparison(false, true);
    h.recordRoutedComparison(false, true);
    h.recordRoutedComparison(false, true);
    double expected = 1.0
      - 3 * IsoPoolHealth.W_GRAPH_NATIVE_WIN
      - Math.min(IsoPoolHealth.CAP_QUOTA_INJECTED_WIN, 3 * IsoPoolHealth.W_QUOTA_INJECTED_WIN);
    assertEquals(expected, h.score(), 1e-9);
    assertEquals(IsoPoolHealth.State.DEGRADED, h.state());
    assertTrue(h.influenceReduced());
  }

  @Test
  public void accumulatedEvidenceReachesUnhealthy() {
    // A plan that keeps proving the pool wrong on every axis must cross the
    // graph-native-only bar: capped wins (0.48) + capped quota extras (0.12)
    // + capped iso rejections (0.12) + capped closure rejections (0.10)
    // = 0.82 deduction, clearly below the 0.30 boundary once the sector
    // repeat is added.
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    for (int i = 0; i < 4; i++) h.recordRoutedComparison(false, true);
    for (int i = 0; i < 3; i++) h.recordIsoLegRejection();
    h.recordClosureRejection();
    h.recordClosureRejection();
    h.recordAcceptedLegBearing(10.0);
    h.recordAcceptedLegBearing(15.0); // same 45° sector → repeat
    assertTrue("score must fall below the UNHEALTHY bar, got " + h.score(),
      h.score() < IsoPoolHealth.UNHEALTHY_BELOW);
    assertEquals(IsoPoolHealth.State.UNHEALTHY, h.state());
    assertTrue(h.graphNativeOnly());
    assertTrue(h.influenceReduced());
  }

  @Test
  public void sectorRepeatsCountOnlyRevisits() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    // A clean sweep around the start: eight distinct 45° sectors, no repeats.
    for (int s = 0; s < 8; s++) {
      h.recordAcceptedLegBearing(s * 45.0 + 20.0);
    }
    assertEquals("distinct sectors carry no deduction", 1.0, h.score(), 1e-9);

    h.recordAcceptedLegBearing(22.0); // revisits sector 0
    assertEquals(1.0 - IsoPoolHealth.W_SECTOR_REPEAT, h.score(), 1e-9);
  }

  @Test
  public void sectorOfMapsBearingsToEightSectors() {
    assertEquals(0, IsoPoolHealth.sectorOf(0.0));
    assertEquals(0, IsoPoolHealth.sectorOf(44.9));
    assertEquals(1, IsoPoolHealth.sectorOf(45.0));
    assertEquals(7, IsoPoolHealth.sectorOf(359.9));
    assertEquals("wraps normalized", 0, IsoPoolHealth.sectorOf(360.0));
    assertEquals("negative bearings normalize", 7, IsoPoolHealth.sectorOf(-10.0));
  }

  @Test
  public void emaShareDeductsOnlyAfterMinimumSampleAndAboveHalf() {
    IsoPoolHealth h = new IsoPoolHealth(richShape());
    // Below the sample floor: no deduction even at 100% EMA share.
    for (int i = 0; i < IsoPoolHealth.MIN_RETURN_ESTIMATES - 1; i++) {
      h.recordReturnEstimate(false);
    }
    assertEquals(1.0, h.score(), 1e-9);
    // Crossing the floor with all-EMA estimates: full W_EMA_SHARE deduction.
    h.recordReturnEstimate(false);
    assertEquals(1.0 - IsoPoolHealth.W_EMA_SHARE, h.score(), 1e-9);

    // A fresh tracker with 50/50 coverage: share at the 0.5 knee → no deduction.
    IsoPoolHealth balanced = new IsoPoolHealth(richShape());
    for (int i = 0; i < 8; i++) {
      balanced.recordReturnEstimate(i % 2 == 0);
    }
    assertEquals(1.0, balanced.score(), 1e-9);
  }

  @Test
  public void emaShareDemotionIsStickyWhenOracleCoverageRecovers() {
    IsoPoolHealth h = new IsoPoolHealth(minimalShape()); // static score 0.50
    for (int i = 0; i < IsoPoolHealth.MIN_RETURN_ESTIMATES; i++) {
      h.recordReturnEstimate(false); // all-EMA share → full deduction → 0.40
    }
    double demoted = h.score();
    assertEquals(IsoPoolHealth.State.DEGRADED, h.state());
    // Later estimates land inside oracle coverage: the live share recovers,
    // the demotion must not (sticky-score contract).
    for (int i = 0; i < 32; i++) {
      h.recordReturnEstimate(true);
    }
    assertTrue("demotion must not revert when the EMA share recovers",
      h.score() <= demoted + 1e-12);
    assertEquals(IsoPoolHealth.State.DEGRADED, h.state());
  }

  @Test
  public void scoreIsMonotonicallyNonIncreasingAndClamped() {
    IsoPoolHealth h = new IsoPoolHealth(minimalShape());
    double prev = h.score();
    for (int i = 0; i < 30; i++) {
      h.recordRoutedComparison(false, true);
      h.recordIsoLegRejection();
      h.recordClosureRejection();
      h.recordAcceptedLegBearing(0.0);
      h.recordReturnEstimate(false);
      double s = h.score();
      assertTrue("score must never increase (" + prev + " -> " + s + ")", s <= prev + 1e-12);
      assertTrue("score stays clamped to [0,1]", s >= 0.0 && s <= 1.0);
      prev = s;
    }
    assertEquals(IsoPoolHealth.State.UNHEALTHY, h.state());
  }

  @Test
  public void describeCarriesTheAttributionFields() {
    IsoPoolHealth h = new IsoPoolHealth(minimalShape());
    h.recordRoutedComparison(false, true);
    h.recordIsoLegRejection();
    h.recordReturnEstimate(true);
    String d = h.describe();
    assertTrue(d.contains("score="));
    assertTrue(d.contains("sectors=4"));
    assertTrue(d.contains("span=180"));
    assertTrue(d.contains("oracle=no"));
    assertTrue(d.contains("graphWins=1(quota=1)"));
    assertTrue(d.contains("isoRejects=1"));
  }

  @Test
  public void poolShapeDescribeIsCompact() {
    assertEquals("pool=6 sectors=4 span=180deg contours=1 oracle=no",
      minimalShape().describe());
  }
}
