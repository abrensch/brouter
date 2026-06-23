package btools.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capsule-aware round-trip candidate provider — the runtime "urban-capsule"
 * loop-planning prototype (see {@code brouter-loop-planning-sidecar-prototype-spec.md},
 * Appendix R / option R.4).
 *
 * <p>Instead of the offline sidecar the spec proposed, this reuses the coarse
 * profile-cost-density grid the GREEDY round-trip already accumulates during its
 * isochrone expansion ({@code RoutingEngine.desirabilityGrid}: cell key &rarr;
 * {@code [nodeCount, prefSum, eleSum, eleCount]}). The grid's {@code nodeCount}
 * per ~500m cell is a road-density signal: high in city/town street meshes, low
 * on rural tracks. From it this provider steers waypoint placement:
 *
 * <ul>
 *   <li><b>Dense capsule interiors</b> (many graph nodes) get zero capsule reward,
 *       so the planner stops landing waypoints inside the urban spaghetti that
 *       causes small city detours.</li>
 *   <li><b>Boundary "portal" cells</b> (sparse cells adjacent to a dense cell — the
 *       clean exits out of a capsule) get the strongest reward.</li>
 *   <li><b>Open countryside</b> gets a steady mild reward.</li>
 * </ul>
 *
 * <p>It also exposes a per-cell <b>elevation reward</b> (higher ground scores
 * higher) to counter the greedy planner's flat-terrain bias. The two rewards are
 * carried on {@link CandidatePoint#capsuleReward} / {@link CandidatePoint#elevationReward}
 * and applied by {@link GreedyRoundTripPlanner} with independent, system-property
 * tunable weights ({@code loop.capsule.weight} / {@code loop.capsule.elevweight}),
 * so an A/B can isolate either lever by zeroing the other.
 *
 * <p>This provider does <b>not</b> invent new candidate coordinates — it only
 * annotates the fallback (graph-native) candidates. So it is a soft steer over
 * real graph nodes, never a hard mask: if every candidate this step is dense, the
 * planner still falls back to its base scoring terms rather than starving.
 *
 * <p>Density classification is hybrid absolute + percentile (cf. spec &sect;9.5): a
 * cell is dense iff its node count clears BOTH an absolute floor AND a configurable
 * percentile of the grid. The absolute floor keeps a rural start (sparse
 * everywhere) from classifying anything as a capsule.
 *
 * <p><b>Known prototype limitation:</b> the grid is start-centered, so density is
 * measured most reliably near the start (well-suited to the urban-start use case)
 * and thins out toward the isochrone frontier; distant towns may be under-counted.
 * The offline sidecar gives unbiased density everywhere — that is the trade the
 * review (Appendix R.8) flags.
 *
 * <p>Activated only when {@link RoutingContext#roundTripCapsule} is set; the
 * default routing path never constructs this class.
 */
public class CapsuleCandidateProvider implements RoundTripCandidateProvider {

  /** A cell is "dense" only if its node count is at least this percentile of the grid. */
  private static final double DENSE_PERCENTILE =
    Double.parseDouble(System.getProperty("loop.capsule.densepercentile", "0.80"));
  /**
   * Absolute floor on a dense cell's node count. Combined with the percentile via
   * AND, so a sparse rural start (where p80 is still tiny) classifies nothing as a
   * capsule — no masking where there is no city.
   */
  private static final int MIN_DENSE_NODES =
    Integer.parseInt(System.getProperty("loop.capsule.mindensenodes", "10"));
  /** Capsule reward for an open-countryside cell (between dense=0 and portal=1). */
  private static final double OPEN_REWARD =
    Double.parseDouble(System.getProperty("loop.capsule.openreward", "0.5"));

  /** Elevation reward saturates below this percentile of cell elevations (flat valley = 0). */
  private static final double ELE_LO_PCT = 0.20;
  /** Elevation reward saturates above this percentile (high ground = 1). */
  private static final double ELE_HI_PCT = 0.90;
  /** Need at least this many ele-bearing cells, and >1m spread, to reward elevation at all. */
  private static final int ELE_MIN_CELLS = 4;

  private final RoundTripCandidateProvider fallback;
  private final int cellSize;
  private final Set<Long> denseCells;
  private final Set<Long> boundaryCells;
  private final Map<Long, Double> cellEle; // cell key -> mean elevation (m), ele-bearing cells only
  private final double eleLo;
  private final double eleSpan;
  private final boolean hasEle;

  /**
   * @param grid     the accumulated density/elevation grid (cell key -&gt;
   *                 {@code [nodeCount, prefSum, eleSum, eleCount]})
   * @param fallback provider whose candidates this annotates (graph-native; may be null)
   * @param cellSize grid cell edge in microdegrees (must match the accumulation cell size)
   */
  public CapsuleCandidateProvider(Map<Long, double[]> grid,
                                  RoundTripCandidateProvider fallback, int cellSize) {
    this.fallback = fallback;
    this.cellSize = cellSize;

    double[] counts = new double[grid.size()];
    List<Double> eles = new ArrayList<>();
    this.cellEle = new HashMap<>();
    int i = 0;
    for (Map.Entry<Long, double[]> e : grid.entrySet()) {
      double[] v = e.getValue();
      counts[i++] = v[0];
      if (v.length >= 4 && v[3] > 0) {
        double meanEle = v[2] / v[3];
        cellEle.put(e.getKey(), meanEle);
        eles.add(meanEle);
      }
    }

    double denseThreshold = Math.max(MIN_DENSE_NODES, percentile(counts, DENSE_PERCENTILE));
    this.denseCells = new HashSet<>();
    for (Map.Entry<Long, double[]> e : grid.entrySet()) {
      if (e.getValue()[0] >= denseThreshold) denseCells.add(e.getKey());
    }

    // Boundary "portal" cells: grid-present, non-dense, 8-neighbour-adjacent to a dense cell.
    this.boundaryCells = new HashSet<>();
    for (long key : denseCells) {
      long cx = key / 1_000_000L, cy = key % 1_000_000L;
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          if (dx == 0 && dy == 0) continue;
          long nk = (cx + dx) * 1_000_000L + (cy + dy);
          if (grid.containsKey(nk) && !denseCells.contains(nk)) boundaryCells.add(nk);
        }
      }
    }

    if (eles.size() >= ELE_MIN_CELLS) {
      double[] ea = new double[eles.size()];
      for (int j = 0; j < ea.length; j++) ea[j] = eles.get(j);
      this.eleLo = percentile(ea, ELE_LO_PCT);
      this.eleSpan = percentile(ea, ELE_HI_PCT) - eleLo;
      this.hasEle = eleSpan > 1.0;
    } else {
      this.eleLo = 0;
      this.eleSpan = 0;
      this.hasEle = false;
    }
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
      int fromIlon, int fromIlat, double airRadius,
      int step, int totalSteps,
      int startIlon, int startIlat,
      double startDirection,
      OsmTrack refTrack) {
    List<CandidatePoint> candidates = (fallback != null)
      ? fallback.candidatesForStep(fromIlon, fromIlat, airRadius, step, totalSteps,
          startIlon, startIlat, startDirection, refTrack)
      : new ArrayList<>();
    for (CandidatePoint cp : candidates) {
      long key = cellKey(cp.ilon, cp.ilat);
      if (denseCells.contains(key)) {
        cp.capsuleReward = 0.0;          // capsule interior: no pull → ranks below its peers
      } else if (boundaryCells.contains(key)) {
        cp.capsuleReward = 1.0;          // portal: strongest pull out of the capsule
      } else {
        cp.capsuleReward = OPEN_REWARD;  // open countryside
      }
      cp.elevationReward = elevationReward(key);
    }
    if (Boolean.getBoolean("loop.capsule.debug")) {
      int dense = 0, portal = 0, open = 0, eleNz = 0;
      for (CandidatePoint cp : candidates) {
        if (cp.capsuleReward <= 0.0) dense++;
        else if (cp.capsuleReward >= 1.0) portal++;
        else open++;
        if (cp.elevationReward > 0) eleNz++;
      }
      System.out.println("CAPSULE-DBG step=" + step + "/" + totalSteps + " airRadius="
        + (int) airRadius + " cands=" + candidates.size() + " dense=" + dense
        + " portal=" + portal + " open=" + open + " eleNonZero=" + eleNz);
    }
    return candidates;
  }

  private long cellKey(int ilon, int ilat) {
    return (long) (ilon / cellSize) * 1_000_000L + (ilat / cellSize);
  }

  private double elevationReward(long key) {
    if (!hasEle) return 0.0;
    Double me = cellEle.get(key);
    if (me == null) return 0.0;
    double r = (me - eleLo) / eleSpan;
    if (r < 0) return 0.0;
    if (r > 1) return 1.0;
    return r;
  }

  /** Nearest-rank percentile of a value array (p in [0,1]). */
  private static double percentile(double[] vals, double p) {
    if (vals.length == 0) return 0;
    double[] s = vals.clone();
    Arrays.sort(s);
    int idx = (int) Math.floor(p * (s.length - 1));
    if (idx < 0) idx = 0;
    if (idx >= s.length) idx = s.length - 1;
    return s[idx];
  }

  // --- test/diagnostic accessors -------------------------------------------------

  int denseCellCount() { return denseCells.size(); }

  int boundaryCellCount() { return boundaryCells.size(); }

  boolean elevationActive() { return hasEle; }
}
