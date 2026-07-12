package btools.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import btools.util.CheapRuler;

/**
 * Unit tests for the sector-resolved return-distance oracle: κ calibration
 * from the cell cloud, detour clamping, coverage fallback, and the
 * too-few-cells refusal. Cells are synthesized directly (no routing).
 */
public class ReturnDistanceOracleTest {

  private static final int START_ILON = 187_852_000; // lon 7.852 (Freiburg-ish)
  private static final int START_ILAT = 138_000_000; // lat 48.0
  private static final int CELL_DIV_LON = 2000;      // ~150m at this latitude
  private static final int CELL_DIV_LAT = 1350;

  private static long cellKeyFor(int ilon, int ilat) {
    return (((long) (ilon / CELL_DIV_LON)) << 32) | ((ilat / CELL_DIV_LAT) & 0xFFFFFFFFL);
  }

  /**
   * Cell cloud in a ring around the start: {@code count} cells at ~{@code airM}
   * meters east-ish of the start, each with cost = air × ratio (cost measured
   * to the CELL CENTER so the oracle's own air computation matches).
   */
  private static void addRing(Map<Long, Integer> cells, double airM, int count, double ratio) {
    for (int i = 0; i < count; i++) {
      double bearing = (i * 360.0 / count);
      // place a point at ~airM meters from start
      double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
      int ilon = START_ILON + (int) (Math.sin(Math.toRadians(bearing)) * airM / kxky[0]);
      int ilat = START_ILAT + (int) (Math.cos(Math.toRadians(bearing)) * airM / kxky[1]);
      long key = cellKeyFor(ilon, ilat);
      int cx = (int) (key >> 32);
      int cy = (int) (key & 0xFFFFFFFFL);
      int centerIlon = cx * CELL_DIV_LON + CELL_DIV_LON / 2;
      int centerIlat = cy * CELL_DIV_LAT + CELL_DIV_LAT / 2;
      double centerAir = CheapRuler.distance(centerIlon, centerIlat, START_ILON, START_ILAT);
      cells.putIfAbsent(key, (int) (centerAir * ratio));
    }
  }

  private static IsochroneExpansionResult resultWith(Map<Long, Integer> cells) {
    return new IsochroneExpansionResult(new double[][]{{0, 0, 0, 0}}, null,
      cells, CELL_DIV_LON, CELL_DIV_LAT);
  }

  @Test
  public void buildRefusesWithoutCloud() {
    Assert.assertNull(ReturnDistanceOracle.build(null, START_ILON, START_ILAT));
    Assert.assertNull(ReturnDistanceOracle.build(
      new IsochroneExpansionResult(new double[][]{{0, 0, 0, 0}}, null),
      START_ILON, START_ILAT));
  }

  @Test
  public void buildRefusesOnTooFewCells() {
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, ReturnDistanceOracle.MIN_KAPPA_SAMPLES - 5, 1.3);
    Assert.assertNull(ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT));
  }

  @Test
  public void baselineSectorEstimatesNearAirTimesOne() {
    // Uniform terrain: every cell reachable at ratio 1.3 → κ ≈ 1.3, so the
    // detour factor is ~1 everywhere and the estimate ≈ air distance.
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, 40, 1.3);
    addRing(cells, 4000, 60, 1.3);
    ReturnDistanceOracle o = ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT);
    Assert.assertNotNull(o);
    Assert.assertEquals(1.3, o.kappa(), 0.05);

    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int ilon = START_ILON + (int) (4000 / kxky[0]); // due east, on the 4km ring
    double air = CheapRuler.distance(ilon, START_ILAT, START_ILON, START_ILAT);
    double est = o.estimateReturnMeters(ilon, START_ILAT, air);
    Assert.assertTrue("covered", est >= 0);
    Assert.assertEquals("uniform terrain → detour ~1", air, est, air * 0.1);
  }

  @Test
  public void walledSectorGetsHigherEstimateThanOpenSector() {
    // Open ring at ratio 1.3 everywhere at 2km (calibrates κ), then at 4km:
    // half the cells cheap (1.3) — the other half expensive (3.0 = behind a
    // ridge). Estimates must differ by sector even at identical air distance.
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, 40, 1.3);
    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int eastIlon = START_ILON + (int) (4000 / kxky[0]);
    int westIlon = START_ILON - (int) (4000 / kxky[0]);
    // east cell cheap, west cell expensive
    for (int[] spec : new int[][]{{eastIlon, 13}, {westIlon, 30}}) {
      long key = cellKeyFor(spec[0], START_ILAT);
      int cx = (int) (key >> 32);
      int cy = (int) (key & 0xFFFFFFFFL);
      double centerAir = CheapRuler.distance(cx * CELL_DIV_LON + CELL_DIV_LON / 2,
        cy * CELL_DIV_LAT + CELL_DIV_LAT / 2, START_ILON, START_ILAT);
      cells.put(key, (int) (centerAir * spec[1] / 10.0));
    }
    ReturnDistanceOracle o = ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT);
    Assert.assertNotNull(o);

    double airE = CheapRuler.distance(eastIlon, START_ILAT, START_ILON, START_ILAT);
    double airW = CheapRuler.distance(westIlon, START_ILAT, START_ILON, START_ILAT);
    double estE = o.estimateReturnMeters(eastIlon, START_ILAT, airE);
    double estW = o.estimateReturnMeters(westIlon, START_ILAT, airW);
    Assert.assertTrue(estE >= 0 && estW >= 0);
    Assert.assertEquals("open sector ≈ air", airE, estE, airE * 0.15);
    Assert.assertTrue("walled sector clearly higher: " + estW + " vs air " + airW,
      estW > airW * 1.8);
  }

  @Test
  public void detourIsClampedAtMax() {
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, 40, 1.0);
    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int farIlon = START_ILON + (int) (5000 / kxky[0]);
    long key = cellKeyFor(farIlon, START_ILAT);
    int cx = (int) (key >> 32);
    int cy = (int) (key & 0xFFFFFFFFL);
    double centerAir = CheapRuler.distance(cx * CELL_DIV_LON + CELL_DIV_LON / 2,
      cy * CELL_DIV_LAT + CELL_DIV_LAT / 2, START_ILON, START_ILAT);
    cells.put(key, (int) (centerAir * 50)); // absurd cost → detour clamps
    ReturnDistanceOracle o = ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT);
    Assert.assertNotNull(o);
    double air = CheapRuler.distance(farIlon, START_ILAT, START_ILON, START_ILAT);
    double est = o.estimateReturnMeters(farIlon, START_ILAT, air);
    Assert.assertEquals(air * ReturnDistanceOracle.MAX_DETOUR, est, air * 0.05);
  }

  @Test
  public void uncoveredPositionFallsBack() {
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, 40, 1.3);
    ReturnDistanceOracle o = ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT);
    Assert.assertNotNull(o);
    // 30km east — far outside the cloud.
    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int farIlon = START_ILON + (int) (30000 / kxky[0]);
    Assert.assertFalse(o.covers(farIlon, START_ILAT));
    Assert.assertEquals(-1,
      o.estimateReturnMeters(farIlon, START_ILAT, 30000), 1e-9);
  }

  @Test
  public void nearStartReturnsAirDistance() {
    Map<Long, Integer> cells = new HashMap<>();
    addRing(cells, 2000, 40, 1.3);
    addRing(cells, 500, 20, 4.0); // noisy near-start ratios must not distort
    ReturnDistanceOracle o = ReturnDistanceOracle.build(resultWith(cells), START_ILON, START_ILAT);
    Assert.assertNotNull(o);
    double[] kxky = CheapRuler.getLonLatToMeterScales(START_ILAT);
    int nearIlon = START_ILON + (int) (500 / kxky[0]);
    double air = CheapRuler.distance(nearIlon, START_ILAT, START_ILON, START_ILAT);
    double est = o.estimateReturnMeters(nearIlon, START_ILAT, air);
    Assert.assertEquals("below the ratio-noise floor the estimate is plain air",
      air, est, 1e-9);
  }
}
