package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Experimental round-trip candidate provider (issue #15): proposes waypoint
 * candidates at high profile-desirability cells instead of (only) a fixed
 * geometric ring.
 *
 * <p>Fed the coarse profile-cost-density grid accumulated during the GREEDY
 * round-trip's isochrone expansion ({@code RoutingEngine.desirabilityGrid}:
 * cell key -&gt; {@code [nodeCount, prefSum, eleSum, eleCount]}). This provider reads
 * only the first two slots — {@code nodeCount} and {@code prefSum}, where
 * {@code prefSum} rewards nodes reached cheaply per air-meter, i.e. on
 * profile-preferred roads.
 * For each step it returns the top-K high-desirability cells whose air-distance
 * from the current position falls in the step's distance window, then appends a
 * fallback provider's candidates so the planner keeps its normal geometric
 * options. Each returned candidate carries its cell's mean desirability in
 * {@link CandidatePoint#desirability} ([0,1]), which the planner rewards.
 *
 * <p>Activated only when {@link RoutingContext#roundTripDesirability} is set; the
 * default routing path never constructs this class.
 */
public class DesirabilityCandidateProvider implements RoundTripCandidateProvider {

  /** Cells must have at least this many reachable nodes to be a candidate (skip sparse one-road cells). */
  private static final int MIN_CELL_NODES = 5;
  /** Step distance window as a fraction of the geometric air-radius: accept cells in [0.6, 1.6] × radius. */
  private static final double WINDOW_LO = 0.6;
  private static final double WINDOW_HI = 1.6;
  /** On the first two steps, only accept cells within this bearing (degrees) of the start direction. */
  private static final double DIRECTION_TOLERANCE_DEG = 70.0;

  private final RoundTripCandidateProvider fallback;
  private final int topK;
  private final int[] cellIlon;
  private final int[] cellIlat;
  private final double[] cellDesir;   // prefSum (density × quality) — used to RANK/select cells
  private final double[] cellQuality; // meanPref = prefSum/count in [0,1] — exposed as the SCORING reward
  private final int n;

  /**
   * @param grid     the accumulated desirability grid (cell key -&gt; [nodeCount, prefSum, eleSum, eleCount]; only the first two slots are read)
   * @param fallback provider whose candidates are appended after the desirability cells (may be null)
   * @param cellSize grid cell edge in microdegrees (must match the accumulation cell size)
   * @param topK     max number of desirability cells to offer per step
   */
  public DesirabilityCandidateProvider(Map<Long, double[]> grid, RoundTripCandidateProvider fallback,
                                       int cellSize, int topK) {
    this.fallback = fallback;
    this.topK = topK;
    List<int[]> centers = new ArrayList<>();
    List<double[]> des = new ArrayList<>();
    for (Map.Entry<Long, double[]> e : grid.entrySet()) {
      double[] v = e.getValue();
      if (v[0] < MIN_CELL_NODES) continue;
      long key = e.getKey();
      long cx = key / 1_000_000L, cy = key % 1_000_000L;
      centers.add(new int[]{(int) (cx * cellSize + cellSize / 2), (int) (cy * cellSize + cellSize / 2)});
      des.add(new double[]{v[1], v[1] / v[0]}); // [prefSum, meanPref]
    }
    n = centers.size();
    cellIlon = new int[n];
    cellIlat = new int[n];
    cellDesir = new double[n];
    cellQuality = new double[n];
    for (int i = 0; i < n; i++) {
      cellIlon[i] = centers.get(i)[0];
      cellIlat[i] = centers.get(i)[1];
      cellDesir[i] = des.get(i)[0];
      cellQuality[i] = des.get(i)[1];
    }
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
      int fromIlon, int fromIlat, double airRadius,
      int step, int totalSteps,
      int startIlon, int startIlat,
      double startDirection,
      OsmTrack refTrack) {
    double lo = airRadius * WINDOW_LO, hi = airRadius * WINDOW_HI;
    boolean dirBias = step <= 2 && startDirection >= 0;
    List<double[]> cand = new ArrayList<>(); // [prefSum, ilon, ilat, bearing, meanPref]
    for (int i = 0; i < n; i++) {
      double d = CheapRuler.distance(fromIlon, fromIlat, cellIlon[i], cellIlat[i]);
      if (d < lo || d > hi) continue;
      // cos(lat)-scaled bearing to match the convention of prevLegBearing and the
      // graph-native/isochrone providers (raw getDirection distorts ~10-15° off the equator).
      double bearing = CheapRuler.getScaledBearing(fromIlon, fromIlat, cellIlon[i], cellIlat[i]);
      if (dirBias && CheapAngleMeter.getDifferenceFromDirection(startDirection, bearing) > DIRECTION_TOLERANCE_DEG) {
        continue;
      }
      cand.add(new double[]{cellDesir[i], cellIlon[i], cellIlat[i], bearing, cellQuality[i]});
    }
    cand.sort((a, b) -> Double.compare(b[0], a[0]));
    List<CandidatePoint> out = new ArrayList<>();
    for (int i = 0; i < Math.min(topK, cand.size()); i++) {
      CandidatePoint cp = new CandidatePoint();
      cp.ilon = (int) cand.get(i)[1];
      cp.ilat = (int) cand.get(i)[2];
      cp.bearing = cand.get(i)[3];
      cp.desirability = cand.get(i)[4]; // meanPref in [0,1] for the planner's scoring reward
      out.add(cp);
    }
    // Keep the fallback (graph-native) candidates so the planner's normal geometric
    // options remain available; the desirability cells are offered on top.
    if (fallback != null) {
      out.addAll(fallback.candidatesForStep(fromIlon, fromIlat, airRadius, step, totalSteps,
        startIlon, startIlat, startDirection, refTrack));
    }
    return out;
  }
}
