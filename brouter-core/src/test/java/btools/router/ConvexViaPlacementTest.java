package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    // prevPrev due North (bearing 0), current NE (bearing 45) -> established sweep +45
    int ppLon = LON0,     ppLat = LAT0 + R; // N
    int curLon = LON0 + R, curLat = LAT0 + R; // NE (45)

    double advance  = sweep(sub, ppLon, ppLat, curLon, curLat, LON0 + R, LAT0);       // E (90): +45 step
    double stall    = sweep(sub, ppLon, ppLat, curLon, curLat, LON0 + R, LAT0 + R);   // NE (45): no progress
    double backtrack= sweep(sub, ppLon, ppLat, curLon, curLat, LON0,     LAT0 + R);   // N (0): reverse
    double overshoot= sweep(sub, ppLon, ppLat, curLon, curLat, LON0 + R, LAT0 - R);   // SE (135): double step

    assertEquals("on-target advance is free", 0.0, advance, 1e-6);
    assertEquals("stall (lobe) costs (0-1)^2 = 1", 1.0, stall, 1e-6);
    assertEquals("backtrack hits the 4.0 cap", 4.0, backtrack, 1e-6);
    assertEquals("overshoot costs (1)^2 = 1", 1.0, overshoot, 1e-6);
    assertTrue("the lobe must score strictly worse than the convex advance", stall > advance);
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
    return GreedyRoundTripPlanner.loopSweepPenalty(
      LON0, LAT0, ppLon, ppLat, curLon, curLat, cpLon, cpLat, sub);
  }
}
