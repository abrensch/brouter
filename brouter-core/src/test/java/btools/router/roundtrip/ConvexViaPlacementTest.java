package btools.router.roundtrip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import btools.util.CheapRuler;

/**
 * Lightweight unit test for the convex via-placement terms in
 * {@link GreedyRoundTripPlanner} (the angular-sweep + unimodal-radius fix).
 *
 * <p>This replaces the segment-data-dependent {@code LorrachDetourReproTest}: the
 * regression it guarded — basel_80km_gravel_E placing a via at the SAME
 * bearing-from-start as the prior via (no angular progress) with the radius
 * collapsed, then climbing back out (the "via4 bump") — is fully captured by the
 * scoring functions, which are pure geometry. No {@code segments4} tiles, no
 * routing engine: it runs anywhere, including upstream CI.
 *
 * <p>Bearing convention (CheapAngleMeter.getDirection): {@code atan2(dLon, dLat)}
 * → compass degrees, 0 = North, 90 = East, clockwise. Points are placed with
 * integer micro-degree offsets so the bearings are exact.
 */
public class ConvexViaPlacementTest {

  private static final double EPS = 1e-9;

  /** ~11 km in micro-degrees — comfortably past the 500 m "prevPrev ≈ start" guard. */
  private static final int R = 100_000;

  /** True-geometry radius (m) for the latitude-correctness guard. */
  private static final double R_METERS = 10_000;
  private static final int LON0 = (int) ((7.59 + 180.0) * 1e6);
  private static final int LAT0 = (int) ((47.56 + 90.0) * 1e6);

  @Test
  public void signedAngleDeltaWrapsToHalfOpenInterval() {
    assertEquals(90.0, GreedyRoundTripPlanner.signedAngleDelta(0.0, 90.0), EPS);
    assertEquals(-90.0, GreedyRoundTripPlanner.signedAngleDelta(90.0, 0.0), EPS);
    // crosses the 0/360 seam in both directions
    assertEquals(20.0, GreedyRoundTripPlanner.signedAngleDelta(350.0, 10.0), EPS);
    assertEquals(-20.0, GreedyRoundTripPlanner.signedAngleDelta(10.0, 350.0), EPS);
  }

  /**
   * With rotation established (prevPrev=N, current=NE, so a +45° CCW... clockwise
   * sweep with target step 45°), a candidate that keeps advancing scores 0, a
   * stall (same bearing as current — the Lörrach lobe) scores 1, and a backtrack
   * scores the 4.0 cap. Overshoot is penalised symmetrically.
   */
  @Test
  public void loopSweepPenalisesStallAndBacktrackNotAdvance() {
    int sub = 8; // target step = 360/8 = 45°
    // Equator start: at lat 0 the cos(lat)-scaled bearings equal the raw
    // integer geometry, so the hand-placed 45° steps stay exactly on target.
    int lonE = LON0;
    int latE = 90_000_000;
    // prevPrev due North (bearing 0), current NE (bearing 45) -> established sweep +45
    int ppLon = lonE,     ppLat = latE + R; // N
    int curLon = lonE + R, curLat = latE + R; // NE (45)

    double advance  = sweepAt(lonE, latE, sub, ppLon, ppLat, curLon, curLat, lonE + R, latE);       // E (90): +45 step
    double stall    = sweepAt(lonE, latE, sub, ppLon, ppLat, curLon, curLat, lonE + R, latE + R);   // NE (45): no progress
    double backtrack= sweepAt(lonE, latE, sub, ppLon, ppLat, curLon, curLat, lonE,     latE + R);   // N (0): reverse
    double overshoot= sweepAt(lonE, latE, sub, ppLon, ppLat, curLon, curLat, lonE + R, latE - R);   // SE (135): double step

    // Tolerances: CheapRuler models the WGS84 ellipsoid, so even at the
    // equator a meter-true 45° differs from the integer-grid 45° by ~0.4°.
    assertEquals("on-target advance is free", 0.0, advance, 1e-3);
    assertEquals("stall (lobe) costs (0-1)^2 = 1", 1.0, stall, 0.02);
    assertEquals("backtrack hits the 4.0 cap", 4.0, backtrack, 1e-3);
    assertEquals("overshoot costs (1)^2 = 1", 1.0, overshoot, 0.02);
    assertTrue("the lobe must score strictly worse than the convex advance", stall > advance);
  }

  /**
   * The latitude-correctness regression guard: an even TRUE-geometry sweep at
   * 47.5°N must be near-free. With raw integer bearings it read ~±0.3 penalty
   * units depending on where the sweep sits (the Codex-review C6.2 defect).
   */
  @Test
  public void loopSweepIsLatitudeCorrect() {
    int sub = 8;
    int[] pp = CheapRuler.destination(LON0, LAT0, R_METERS, 0);   // true N
    int[] cur = CheapRuler.destination(LON0, LAT0, R_METERS, 45); // true NE
    int[] cand = CheapRuler.destination(LON0, LAT0, R_METERS, 90); // true E
    double advance = sweep(sub, pp[0], pp[1], cur[0], cur[1], cand[0], cand[1]);
    assertEquals("an even true-geometry 45° step at 47.5N is on target", 0.0, advance, 0.02);
  }

  /** No penalty until the rotation sense is established (prevPrev too close to start). */
  @Test
  public void loopSweepIsInertBeforeRotationEstablished() {
    int sub = 8;
    // prevPrev only ~110 m from start -> the <500 m guard returns 0 regardless of candidate
    int ppNearLon = LON0, ppNearLat = LAT0 + 1000;
    double p = sweep(sub, ppNearLon, ppNearLat, LON0 + R, LAT0 + R, LON0, LAT0 + R);
    assertEquals(0.0, p, EPS);

    // prevPrev and current at the same bearing -> established < 5° -> 0
    double q = sweep(sub, LON0 + R, LAT0 + R, LON0 + 2 * R, LAT0 + 2 * R, LON0, LAT0 + R);
    assertEquals(0.0, q, EPS);
  }

  /**
   * Past the apogee (phase ≥ 0.5) a unimodal loop only contracts; a candidate whose
   * radius grows back out (the via4 bump) is penalised by the squared growth, capped
   * at 4.0. Before the apogee, or while contracting, there is no penalty.
   */
  @Test
  public void unimodalRadiusPenalisesPostApogeeGrowthOnly() {
    int sub = 8;
    // before apogee: growth is allowed (still climbing toward the turn-around)
    assertEquals(0.0, GreedyRoundTripPlanner.unimodalRadiusPenalty(10_000, 5_000, 1, sub), EPS);
    // after apogee, contracting: free
    assertEquals(0.0, GreedyRoundTripPlanner.unimodalRadiusPenalty(5_000, 8_000, 6, sub), EPS);
    // after apogee, the 8km->9km bump: growth 0.125 -> 0.125^2
    assertEquals(0.015625, GreedyRoundTripPlanner.unimodalRadiusPenalty(9_000, 8_000, 6, sub), EPS);
    // runaway growth is capped at 4.0
    assertEquals(4.0, GreedyRoundTripPlanner.unimodalRadiusPenalty(5_000, 1_000, 7, sub), EPS);
    // degenerate previous radius -> no penalty
    assertEquals(0.0, GreedyRoundTripPlanner.unimodalRadiusPenalty(5_000, 0, 6, sub), EPS);
  }

  private static double sweep(int sub, int ppLon, int ppLat, int curLon, int curLat,
                              int cpLon, int cpLat) {
    return sweepAt(LON0, LAT0, sub, ppLon, ppLat, curLon, curLat, cpLon, cpLat);
  }

  private static double sweepAt(int sLon, int sLat, int sub, int ppLon, int ppLat,
                                int curLon, int curLat, int cpLon, int cpLat) {
    return GreedyRoundTripPlanner.loopSweepPenalty(
      sLon, sLat, ppLon, ppLat, curLon, curLat, cpLon, cpLat, sub);
  }
}
