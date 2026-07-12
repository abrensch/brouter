package btools.router;

import java.util.Arrays;
import java.util.Map;

import btools.util.CheapRuler;

/**
 * Sector-resolved return-distance estimation for round-trip planning (the
 * "return-distance oracle" from the greedy performance investigation, F6).
 *
 * <p>The planner needs "how far is it back to the start from here?" at three
 * decision points (candidate scoring, routed re-scoring, the return-check
 * skip). Historically the answer was {@code airDist × indirectnessEst} with a
 * single global EMA — blind to terrain anisotropy, which is exactly where
 * loops fail: a via behind a ridge or across a valley mouth has a return
 * factor far above the EMA, one on an open plain sits below it. Mis-estimates
 * surface as too-long undo cycles and premature/late closure.
 *
 * <p>This oracle derives a <b>per-cell</b> indirectness factor from a
 * start-anchored isochrone expansion the plan has already paid for: the
 * expansion records the minimum Dijkstra cost per ~150m grid cell
 * ({@link IsochroneExpansionResult#cellMinCost}). BRouter's cost is a
 * modified distance (costfactor ≥ 1 × meters + penalties), so the ratio
 * {@code cost/airDist} measures how hard the graph fights the straight line
 * toward that cell. Normalizing by the plan's baseline ratio κ (a low
 * percentile over far cells — the best corridor this terrain and profile
 * offer) cancels the profile's cost scale entirely:
 *
 * <pre>  returnMeters(cell) ≈ airDist × clamp( (cost/air) / κ , 1, MAX_DETOUR )</pre>
 *
 * <p>Semantics and limits, deliberately accepted:
 * <ul>
 *   <li>The expansion is outbound (start → cell); the return leg travels
 *       cell → start. One-ways and elevation make cost asymmetric, but the
 *       DISTANCE asymmetry is small at loop scale, and κ-normalization
 *       absorbs the profile's scale.</li>
 *   <li>The anti-reuse penalty pushes the real return off the outbound
 *       corridor, so the estimate stays optimistic-leaning — same bias
 *       direction as the legacy EMA estimate it replaces, but sector-true
 *       instead of globally smeared.</li>
 *   <li>Cells outside the expansion's reach return {@code -1}; callers fall
 *       back to the legacy EMA estimate. Coverage is partial by design —
 *       the oracle costs zero extra Dijkstras.</li>
 * </ul>
 */
final class ReturnDistanceOracle {

  /** Detour factors above this are clamped — beyond it the loop is broken anyway. */
  static final double MAX_DETOUR = 3.0;
  /** Cells closer than this to the start are ratio-noise; excluded from κ. */
  static final double MIN_KAPPA_SAMPLE_AIR_M = 1000.0;
  /** Minimum qualifying cells for a trustworthy κ; below → no oracle. */
  static final int MIN_KAPPA_SAMPLES = 30;
  /** κ percentile: low enough to find the best corridor, robust to outlier cells. */
  static final double KAPPA_PERCENTILE = 0.10;

  private final Map<Long, Integer> cellMinCost;
  private final int cellDivLon;
  private final int cellDivLat;
  private final int startIlon;
  private final int startIlat;
  private final double kappa;

  private ReturnDistanceOracle(Map<Long, Integer> cellMinCost, int cellDivLon, int cellDivLat,
                               int startIlon, int startIlat, double kappa) {
    this.cellMinCost = cellMinCost;
    this.cellDivLon = cellDivLon;
    this.cellDivLat = cellDivLat;
    this.startIlon = startIlon;
    this.startIlat = startIlat;
    this.kappa = kappa;
  }

  /**
   * Build from a start-anchored expansion, or return {@code null} when the
   * expansion is missing, cloud-less, or too small to calibrate κ — callers
   * treat a null oracle as "always fall back".
   */
  static ReturnDistanceOracle build(IsochroneExpansionResult iso, int startIlon, int startIlat) {
    if (iso == null || iso.cellMinCost.isEmpty() || iso.cellDivLon <= 0 || iso.cellDivLat <= 0) {
      return null;
    }
    // κ = low percentile of cost-per-airmeter over far-enough cells. Air
    // distance is measured to the cell center; at ~150m cells the center
    // error is ≤ ~106m, negligible beyond the 1km sample floor.
    double[] ratios = new double[iso.cellMinCost.size()];
    int n = 0;
    for (Map.Entry<Long, Integer> e : iso.cellMinCost.entrySet()) {
      double air = cellCenterAirDist(e.getKey(), iso.cellDivLon, iso.cellDivLat, startIlon, startIlat);
      if (air < MIN_KAPPA_SAMPLE_AIR_M) continue;
      ratios[n++] = e.getValue() / air;
    }
    if (n < MIN_KAPPA_SAMPLES) return null;
    Arrays.sort(ratios, 0, n);
    double kappa = ratios[(int) (n * KAPPA_PERCENTILE)];
    if (kappa <= 0) return null;
    return new ReturnDistanceOracle(iso.cellMinCost, iso.cellDivLon, iso.cellDivLat,
      startIlon, startIlat, kappa);
  }

  private static double cellCenterAirDist(long key, int cellDivLon, int cellDivLat,
                                          int startIlon, int startIlat) {
    int cx = (int) (key >> 32);
    int cy = (int) (key & 0xFFFFFFFFL);
    int centerIlon = cx * cellDivLon + cellDivLon / 2;
    int centerIlat = cy * cellDivLat + cellDivLat / 2;
    return CheapRuler.distance(centerIlon, centerIlat, startIlon, startIlat);
  }

  /** Whether the position falls in a covered cell (3×3 neighborhood). */
  boolean covers(int ilon, int ilat) {
    return minCostAround(ilon, ilat) >= 0;
  }

  /**
   * Sector-resolved return estimate in meters, or {@code -1} when the position
   * is outside the expansion's coverage (caller falls back to the EMA
   * estimate). {@code airDistToStart} is passed in because every caller has
   * already computed it.
   */
  double estimateReturnMeters(int ilon, int ilat, double airDistToStart) {
    int cost = minCostAround(ilon, ilat);
    if (cost < 0) return -1;
    if (airDistToStart < MIN_KAPPA_SAMPLE_AIR_M) {
      // Ratio noise dominates near the start; air distance is accurate enough.
      return airDistToStart;
    }
    double detour = (cost / airDistToStart) / kappa;
    if (detour < 1.0) detour = 1.0;
    if (detour > MAX_DETOUR) detour = MAX_DETOUR;
    return airDistToStart * detour;
  }

  /** Min recorded cost over the 3×3 cell neighborhood, or -1 if none. */
  private int minCostAround(int ilon, int ilat) {
    int cx = ilon / cellDivLon;
    int cy = ilat / cellDivLat;
    int best = -1;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        Integer c = cellMinCost.get((((long) (cx + dx)) << 32) | ((cy + dy) & 0xFFFFFFFFL));
        if (c != null && (best < 0 || c < best)) best = c;
      }
    }
    return best;
  }

  /** Calibrated baseline cost-per-airmeter (diagnostics). */
  double kappa() {
    return kappa;
  }
}
