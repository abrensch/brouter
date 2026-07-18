package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

import btools.router.roundtrip.IsochroneExpansionResult;

/**
 * The measured-scale isochrone budget for motorized profiles: the probe's
 * median cost-per-air-meter and the budget derived from it. The in-flight
 * calibration cannot work at motor cost scales — its sampling band
 * ([0.7, 1.0] x searchRadius in cost units) sits ~100 m from the start at
 * ~126 cost/m, and its cap (12 x searchRadius) is ~20x too small — so fast-motor
 * requests probe the scale first and size the budget from the measurement.
 */
public class IsoFastMotorBudgetTest {

  private static double[] row(double dist, double cost) {
    // frontier row contract: [bearing, airDist_m, cost, hits, ilon, ilat]
    return new double[]{0, dist, cost, 5, 0, 0};
  }

  @Test
  public void medianSkipsNearNoiseAndNeedsMinimumSamples() {
    double[][] frontier = {
      row(40, 100000), // below the 50 m noise floor - ignored
      row(1000, 126000), // 126/m
      row(800, 100000),  // 125/m
      row(600, 90000),   // 150/m
      row(500, 50000),   // 100/m
      row(400, 60000),   // 150/m
      row(300, 30000),   // 100/m
      row(200, 25000),   // 125/m
      row(100, 13000),   // 130/m
    };
    IsochroneExpansionResult r = new IsochroneExpansionResult(frontier, Collections.emptyList());
    // sorted: 100,100,125,125,126,130,150,150 -> band[8/2] = 126 (same
    // upper-median convention as calibratedIsoBudget)
    assertEquals(126.0, RoutingEngine.medianFrontierCostPerMeter(r, 8), 0.01);
    assertTrue("8 valid samples must fail a min of 9",
      Double.isNaN(RoutingEngine.medianFrontierCostPerMeter(r, 9)));
    assertTrue(Double.isNaN(RoutingEngine.medianFrontierCostPerMeter(null, 1)));
    assertTrue(Double.isNaN(RoutingEngine.medianFrontierCostPerMeter(
      new IsochroneExpansionResult(new double[0][], Collections.emptyList()), 1)));
  }

  @Test
  public void fastMotorBudgetUsesReachFormulaAtMeasuredScale() {
    // Basel car-vario 100 km case: searchRadius ~15915 m, ~126 cost/m.
    // Reach target = 2.0 x radius x scale; the bike-unit cap does not apply.
    assertEquals((int) (2.0 * 15915 * 126), RoutingEngine.fastMotorIsoBudget(15915, 126));
  }

  @Test
  public void fastMotorBudgetKeepsBikeFloorForLowScales() {
    // A fast-motorized profile with bike-like costs keeps at least the historical
    // floor (4 x searchRadius) - the budget can only grow, never shrink.
    assertEquals((int) (4.0 * 15915), RoutingEngine.fastMotorIsoBudget(15915, 1.5));
  }

  @Test
  public void fastMotorBudgetClampsPathologicalScales() {
    // Insane measured scale must not overflow int; maxNodes and the geographic
    // cutoff stay the operational guards.
    assertEquals(Integer.MAX_VALUE / 2, RoutingEngine.fastMotorIsoBudget(32000, 1e7));
  }
}
