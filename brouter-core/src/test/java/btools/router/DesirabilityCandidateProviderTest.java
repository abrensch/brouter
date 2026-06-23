package btools.router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Deterministic unit tests for {@link DesirabilityCandidateProvider} (issue #15).
 *
 * <p>These drive the provider with a hand-built grid and a stub fallback — no
 * routing, no fixture — so every selection rule (distance window, sparse-cell
 * exclusion, direction filter, top-K cap, fallback append, desirability value)
 * is exercised in isolation. Distances are recomputed with the same
 * {@link CheapRuler}/{@link CheapAngleMeter} the provider uses, so the assertions
 * stay valid regardless of the exact geographic scale of a grid cell.
 */
public class DesirabilityCandidateProviderTest {

  private static final int CELL = 5000; // microdeg — matches RoutingEngine.DESIRABILITY_CELL
  // A start position near the Dreieich fixture origin; the exact spot is irrelevant.
  private static final int FROM_ILON = 188_720_000;
  private static final int FROM_ILAT = 140_000_000;

  /** Cell key as the accumulator builds it: (ilon/CELL) * 1e6 + (ilat/CELL). */
  private static long key(int cx, int cy) {
    return (long) cx * 1_000_000L + cy;
  }

  /** Center longitude of cell column cx (mirrors the provider's reconstruction). */
  private static int centerLon(int cx) {
    return cx * CELL + CELL / 2;
  }

  private static int centerLat(int cy) {
    return cy * CELL + CELL / 2;
  }

  /** A fallback that returns one recognisable marker candidate (desirability stays 0). */
  private static RoundTripCandidateProvider markerFallback() {
    return (fromIlon, fromIlat, airRadius, step, totalSteps, startIlon, startIlat, startDirection, refTrack) -> {
      List<RoundTripCandidateProvider.CandidatePoint> l = new ArrayList<>();
      RoundTripCandidateProvider.CandidatePoint cp = new RoundTripCandidateProvider.CandidatePoint();
      cp.ilon = 999;
      cp.ilat = 888;
      l.add(cp);
      return l;
    };
  }

  /** Returns only the desirability-sourced candidates (those the provider itself produced). */
  private static List<RoundTripCandidateProvider.CandidatePoint> desirabilityOnly(
      List<RoundTripCandidateProvider.CandidatePoint> all) {
    List<RoundTripCandidateProvider.CandidatePoint> out = new ArrayList<>();
    for (RoundTripCandidateProvider.CandidatePoint cp : all) {
      if (cp.desirability > 0) out.add(cp);
    }
    return out;
  }

  @Test
  public void selectsOnlyCellsInsideTheDistanceWindow() {
    // One cell at the column offsets below; airRadius is chosen so the "mid" cell
    // sits squarely inside [0.6, 1.6]×airRadius while "near" and "far" fall outside.
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    int midCx = cxFrom + 6;  // some distance east
    double midDist = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(midCx), centerLat(cyFrom));
    double airRadius = midDist; // mid is exactly at 1.0×airRadius → inside [0.6,1.6]

    Map<Long, double[]> grid = new HashMap<>();
    grid.put(key(cxFrom + 1, cyFrom), new double[]{10, 8});   // too close (~0.17×)
    grid.put(key(midCx, cyFrom), new double[]{10, 8});        // inside
    grid.put(key(cxFrom + 20, cyFrom), new double[]{10, 8});  // too far (~3.3×)

    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, null, CELL, 10);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, -1, null);

    Assert.assertEquals("only the in-window cell is offered", 1, got.size());
    double d = CheapRuler.distance(FROM_ILON, FROM_ILAT, got.get(0).ilon, got.get(0).ilat);
    Assert.assertTrue("offered candidate inside window", d >= airRadius * 0.6 && d <= airRadius * 1.6);
  }

  @Test
  public void excludesSparseCellsBelowMinNodes() {
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    int richCx = cxFrom + 6;
    int sparseCx = cxFrom + 5; // also inside the window, but under-populated
    double airRadius = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(richCx), centerLat(cyFrom));

    Map<Long, double[]> grid = new HashMap<>();
    grid.put(key(richCx, cyFrom), new double[]{5, 4});    // exactly MIN_CELL_NODES → kept
    grid.put(key(sparseCx, cyFrom), new double[]{4, 3});  // below MIN_CELL_NODES → dropped

    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, null, CELL, 10);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, -1, null);

    Assert.assertEquals("sparse cell excluded, rich cell kept", 1, got.size());
    Assert.assertEquals("kept cell is the rich one", centerLon(richCx), got.get(0).ilon);
  }

  @Test
  public void directionFilterAppliesOnEarlyStepsOnly() {
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    // A cell due west of start; start direction is due east (90°) → ~180° off.
    int westCx = cxFrom - 6;
    double airRadius = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(westCx), centerLat(cyFrom));
    double eastDirection = CheapAngleMeter.getDirection(FROM_ILON, FROM_ILAT,
      centerLon(cxFrom + 6), centerLat(cyFrom));

    Map<Long, double[]> grid = new HashMap<>();
    grid.put(key(westCx, cyFrom), new double[]{10, 8});

    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, null, CELL, 10);

    // Step 1 (early): the west cell is >70° off the east start direction → filtered out.
    List<RoundTripCandidateProvider.CandidatePoint> early =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 1, 4, FROM_ILON, FROM_ILAT, eastDirection, null);
    Assert.assertTrue("early-step direction filter drops the back-facing cell", early.isEmpty());

    // Step 3 (late): direction filter no longer applies → the same cell is offered.
    List<RoundTripCandidateProvider.CandidatePoint> late =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, eastDirection, null);
    Assert.assertEquals("late step ignores direction filter", 1, late.size());
  }

  @Test
  public void capsOutputAtTopK() {
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    int ringCx = cxFrom + 6;
    double airRadius = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(ringCx), centerLat(cyFrom));

    // Many cells spread across latitude rows around the ring distance.
    Map<Long, double[]> grid = new HashMap<>();
    for (int dcy = -8; dcy <= 8; dcy++) {
      grid.put(key(ringCx, cyFrom + dcy), new double[]{10, 5 + (dcy + 8)});
    }
    // Count how many actually fall inside the provider's distance window, mirroring
    // its predicate — so the cap assertion is only meaningful when this exceeds topK.
    int inWindow = 0;
    double lo = airRadius * 0.6, hi = airRadius * 1.6;
    for (int dcy = -8; dcy <= 8; dcy++) {
      double d = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(ringCx), centerLat(cyFrom + dcy));
      if (d >= lo && d <= hi) inWindow++;
    }

    int topK = 4;
    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, null, CELL, topK);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, -1, null);

    Assert.assertTrue("precondition: more in-window cells (" + inWindow + ") than topK", inWindow > topK);
    Assert.assertEquals("output is capped at exactly topK", topK, got.size());
  }

  @Test
  public void appendsFallbackCandidatesAfterDesirabilityCells() {
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    int midCx = cxFrom + 6;
    double airRadius = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(midCx), centerLat(cyFrom));

    Map<Long, double[]> grid = new HashMap<>();
    grid.put(key(midCx, cyFrom), new double[]{10, 8});

    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, markerFallback(), CELL, 10);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, -1, null);

    Assert.assertEquals("desirability cell + fallback marker", 2, got.size());
    // Desirability cells come first; the fallback marker is appended last.
    Assert.assertTrue("first candidate is the desirability cell", got.get(0).desirability > 0);
    RoundTripCandidateProvider.CandidatePoint last = got.get(got.size() - 1);
    Assert.assertEquals("fallback marker appended", 999, last.ilon);
    Assert.assertEquals("fallback candidate carries no desirability", 0.0, last.desirability, 0.0);
  }

  @Test
  public void desirabilityIsMeanPrefInUnitRange() {
    int cxFrom = FROM_ILON / CELL;
    int cyFrom = FROM_ILAT / CELL;
    int midCx = cxFrom + 6;
    double airRadius = CheapRuler.distance(FROM_ILON, FROM_ILAT, centerLon(midCx), centerLat(cyFrom));

    Map<Long, double[]> grid = new HashMap<>();
    grid.put(key(midCx, cyFrom), new double[]{10, 6}); // meanPref = 6/10 = 0.6

    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(grid, null, CELL, 10);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, airRadius, 3, 4, FROM_ILON, FROM_ILAT, -1, null);

    Assert.assertEquals(1, got.size());
    Assert.assertEquals("desirability is prefSum/count", 0.6, got.get(0).desirability, 1e-9);
    Assert.assertTrue("desirability in [0,1]", got.get(0).desirability >= 0 && got.get(0).desirability <= 1);
  }

  @Test
  public void emptyGridYieldsOnlyFallback() {
    DesirabilityCandidateProvider p = new DesirabilityCandidateProvider(
      new HashMap<>(), markerFallback(), CELL, 10);
    List<RoundTripCandidateProvider.CandidatePoint> got =
      p.candidatesForStep(FROM_ILON, FROM_ILAT, 1000, 3, 4, FROM_ILON, FROM_ILAT, -1, null);
    Assert.assertEquals("no desirability cells", 0, desirabilityOnly(got).size());
    Assert.assertEquals("fallback still offered", 1, got.size());
  }
}
