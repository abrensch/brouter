package btools.router;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Faithful capsule mechanism (urban-capsule loop planning) — turns the runtime density
 * grid into soft NO-GO polygons over dense interiors, so that ROUTED LEGS avoid the
 * urban spaghetti while still crossing at least-cost gaps (implicit portals) and taking
 * the least-cost path through when a crossing is unavoidable (implicit corridors).
 *
 * <p>Unlike {@link CapsuleCandidateProvider} (waypoint steering only), this masks the
 * interior during leg routing. The soft weight keeps the start capsule
 * penalised-but-exitable ("leave town fast").
 *
 * <p>Polygons follow the spec's morphology so they hug the actual dense shape instead of
 * over-covering: classify dense cells (hybrid absolute floor + percentile) → CLOSING
 * (dilate then erode, fills 1-cell gaps/notches) → 4-connected components → EXACT
 * rectilinear boundary trace of the cell union (outer ring) → collinear simplify. A
 * convex hull (the earlier version) swallowed non-dense road gaps and forced detours;
 * the rectilinear outline leaves the real road gaps open, so the router's crossings land
 * on genuine portals.
 *
 * <p>Two entry points, two callers:
 * <ul>
 *   <li>{@link #build} — the full pipeline that emits soft no-go polygons. Wired from
 *       {@code RoutingEngine} only when {@code roundTripCapsule} is set (experimental
 *       leg-masking).</li>
 *   <li>The public geometry helpers ({@link #classifyDense}, {@link #components},
 *       {@link #splitOversized}, {@link #polygonsFromCells}) — reused by the production
 *       {@link DenseAreaMap} via-steering path ({@code roundTripSteerVias}) to build
 *       town-sized boxes from the same grid.</li>
 * </ul>
 */
public final class CapsuleNogoBuilder {

  private CapsuleNogoBuilder() {}

  public static List<OsmNodeNamed> build(Map<Long, double[]> grid, int cellSize, double nogoWeight,
                                         double densePercentile, int minDenseNodes, int minComponentCells) {
    if (grid == null || grid.isEmpty()) return new ArrayList<>();

    double[] counts = new double[grid.size()];
    int idx = 0;
    for (double[] v : grid.values()) counts[idx++] = v[0];
    double denseThreshold = Math.max(minDenseNodes, percentile(counts, densePercentile));

    Set<Long> dense = new HashSet<>();
    for (Map.Entry<Long, double[]> e : grid.entrySet()) {
      if (e.getValue()[0] >= denseThreshold) dense.add(e.getKey());
    }
    Set<Long> closed = erode(dilate(dense)); // morphological closing: fill 1-cell gaps/notches
    return polygonsFromCells(closed, cellSize, nogoWeight, minComponentCells);
  }

  /**
   * Build soft no-go polygons from an explicit cell set (4-connected components →
   * exact rectilinear boundary trace). Unlike {@link #build}, this applies no
   * morphological closing, so the caller's exact cell shape is preserved.
   * {@code nogoWeight} makes each polygon a soft (penalised-but-passable) no-go; both
   * callers ({@link #build} and {@link DenseAreaMap}) pass finite weights.
   */
  public static List<OsmNodeNamed> polygonsFromCells(Set<Long> cells, int cellSize,
                                                     double nogoWeight, int minComponentCells) {
    List<OsmNodeNamed> nogos = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (long startKey : cells) {
      if (!seen.add(startKey)) continue;
      List<long[]> comp = new ArrayList<>();
      Deque<Long> q = new ArrayDeque<>();
      q.add(startKey);
      while (!q.isEmpty()) {
        long k = q.poll();
        long cx = k / 1_000_000L, cy = k % 1_000_000L;
        comp.add(new long[]{cx, cy});
        for (long nk : neighbors4(cx, cy)) {
          if (cells.contains(nk) && seen.add(nk)) q.add(nk);
        }
      }
      if (comp.size() < minComponentCells) continue; // small settlement → leave routable

      List<int[]> ring = outerRing(comp); // cell-unit corner coords, CCW
      if (ring.size() < 3) continue;
      ring = simplifyCollinear(ring);

      OsmNogoPolygon poly = new OsmNogoPolygon(true);
      for (int[] c : ring) poly.addVertex(c[0] * cellSize, c[1] * cellSize);
      poly.calcBoundingCircle(); // sets center + radius used by RoutingContext.calcDistance
      poly.nogoWeight = nogoWeight;
      poly.name = "capsule_" + comp.size() + "cells";
      nogos.add(poly);
    }
    return nogos;
  }

  // ---- data-driven detection (the signal swap-point: today road-node density; a future OSM
  //      landuse layer would supply the same cell→score map without touching the rest) ---------

  /**
   * Cells whose density signal clears the hybrid absolute-floor + percentile threshold. Today the
   * signal is grid value[0] = reachable road-node count per cell (a residential/service-road density
   * proxy that catches suburbs like Münchenstein/Riehen). A landuse-based detector would build the
   * same {@code Set<Long>} of dense cells from landuse polygons and feed the rest of the pipeline.
   */
  public static Set<Long> classifyDense(Map<Long, double[]> grid, double densePercentile, int minDenseNodes) {
    double[] counts = new double[grid.size()];
    int i = 0;
    for (double[] v : grid.values()) counts[i++] = v[0];
    double thr = Math.max(minDenseNodes, percentile(counts, densePercentile));
    Set<Long> dense = new HashSet<>();
    for (Map.Entry<Long, double[]> e : grid.entrySet()) if (e.getValue()[0] >= thr) dense.add(e.getKey());
    return dense;
  }

  /**
   * Split any component larger than {@code maxCells} into ~{@code tileCells}×{@code tileCells} grid
   * tiles, so a big contiguous built-up area (e.g. greater Basel, one 338-cell blob) becomes several
   * town-sized boxes instead of one 9 km mega-box. A grid tiling, not true watershed, but enough to
   * keep boxes town-sized for via-steering. Components ≤ maxCells pass through unchanged.
   */
  public static List<Set<Long>> splitOversized(List<Set<Long>> comps, int maxCells, int tileCells) {
    List<Set<Long>> out = new ArrayList<>();
    for (Set<Long> c : comps) {
      if (c.size() <= maxCells) { out.add(c); continue; }
      Map<Long, Set<Long>> tiles = new HashMap<>();
      for (long k : c) {
        long cx = k / 1_000_000L, cy = k % 1_000_000L;
        long tk = (cx / tileCells) * 1_000_000L + (cy / tileCells);
        tiles.computeIfAbsent(tk, x -> new HashSet<>()).add(k);
      }
      out.addAll(tiles.values());
    }
    return out;
  }

  /** 4-connected components of {@code cells} with at least {@code minComponentCells} cells. */
  public static List<Set<Long>> components(Set<Long> cells, int minComponentCells) {
    List<Set<Long>> out = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (long s : cells) {
      if (!seen.add(s)) continue;
      Set<Long> comp = new HashSet<>();
      Deque<Long> q = new ArrayDeque<>();
      q.add(s);
      comp.add(s);
      while (!q.isEmpty()) {
        long k = q.poll();
        long cx = k / 1_000_000L, cy = k % 1_000_000L;
        for (long nk : neighbors4(cx, cy)) {
          if (cells.contains(nk) && seen.add(nk)) { comp.add(nk); q.add(nk); }
        }
      }
      if (comp.size() >= minComponentCells) out.add(comp);
    }
    return out;
  }

  // ---- morphology -------------------------------------------------------------

  private static long[] neighbors4(long cx, long cy) {
    return new long[]{
      (cx + 1) * 1_000_000L + cy, (cx - 1) * 1_000_000L + cy,
      cx * 1_000_000L + (cy + 1), cx * 1_000_000L + (cy - 1)};
  }

  /** 4-neighbour dilation: add every cell adjacent to a dense cell. */
  private static Set<Long> dilate(Set<Long> cells) {
    Set<Long> out = new HashSet<>(cells);
    for (long k : cells) {
      long cx = k / 1_000_000L, cy = k % 1_000_000L;
      for (long nk : neighbors4(cx, cy)) out.add(nk);
    }
    return out;
  }

  /** 4-neighbour erosion: keep a cell only if all 4 neighbours are also set. */
  private static Set<Long> erode(Set<Long> cells) {
    Set<Long> out = new HashSet<>();
    for (long k : cells) {
      long cx = k / 1_000_000L, cy = k % 1_000_000L;
      boolean keep = true;
      for (long nk : neighbors4(cx, cy)) {
        if (!cells.contains(nk)) { keep = false; break; }
      }
      if (keep) out.add(k);
    }
    return out;
  }

  // ---- exact rectilinear boundary trace --------------------------------------

  /**
   * Trace the outer boundary of a 4-connected cell union as a CCW ring of cell-unit
   * corner coordinates. Boundary edges are emitted with interior-on-left winding, then
   * stitched into closed rings; the ring with the largest bounding box is the outer one.
   */
  private static List<int[]> outerRing(List<long[]> comp) {
    Set<Long> cells = new HashSet<>();
    for (long[] c : comp) cells.add(c[0] * 1_000_000L + c[1]);

    // startCornerKey -> list of end corners (each a directed boundary edge)
    Map<Long, Deque<int[]>> edges = new HashMap<>();
    for (long[] c : comp) {
      int cx = (int) c[0], cy = (int) c[1];
      if (!cells.contains((long) (cx) * 1_000_000L + (cy - 1))) addEdge(edges, cx, cy, cx + 1, cy);       // bottom →
      if (!cells.contains((long) (cx + 1) * 1_000_000L + cy)) addEdge(edges, cx + 1, cy, cx + 1, cy + 1); // right ↑
      if (!cells.contains((long) (cx) * 1_000_000L + (cy + 1))) addEdge(edges, cx + 1, cy + 1, cx, cy + 1); // top ←
      if (!cells.contains((long) (cx - 1) * 1_000_000L + cy)) addEdge(edges, cx, cy + 1, cx, cy);          // left ↓
    }

    List<List<int[]>> rings = new ArrayList<>();
    for (Map.Entry<Long, Deque<int[]>> e : new ArrayList<>(edges.entrySet())) {
      while (!e.getValue().isEmpty()) {
        List<int[]> ring = new ArrayList<>();
        long startKey = e.getKey();
        int[] start = e.getValue().peek();
        long curKey = startKey;
        int[] cur = new int[]{(int) (startKey >> 32), (int) (startKey & 0xffffffffL)};
        ring.add(cur);
        while (true) {
          Deque<int[]> outE = edges.get(curKey);
          if (outE == null || outE.isEmpty()) break;
          int[] next = outE.poll();
          ring.add(next);
          curKey = key(next[0], next[1]);
          if (curKey == startKey) break;
        }
        if (ring.size() >= 4) rings.add(ring);
      }
    }
    if (rings.isEmpty()) return new ArrayList<>();
    // pick ring with the largest bounding-box area = outer boundary
    List<int[]> best = rings.get(0);
    long bestArea = bboxArea(best);
    for (List<int[]> r : rings) {
      long a = bboxArea(r);
      if (a > bestArea) { bestArea = a; best = r; }
    }
    // drop the duplicated closing vertex
    if (best.size() > 1 && best.get(0)[0] == best.get(best.size() - 1)[0]
      && best.get(0)[1] == best.get(best.size() - 1)[1]) {
      best = best.subList(0, best.size() - 1);
    }
    return new ArrayList<>(best);
  }

  private static void addEdge(Map<Long, Deque<int[]>> edges, int sx, int sy, int ex, int ey) {
    edges.computeIfAbsent(key(sx, sy), k -> new ArrayDeque<>()).add(new int[]{ex, ey});
  }

  private static long key(int x, int y) {
    return ((long) x << 32) ^ (y & 0xffffffffL);
  }

  private static long bboxArea(List<int[]> ring) {
    int minx = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
    for (int[] p : ring) {
      minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]);
      miny = Math.min(miny, p[1]); maxy = Math.max(maxy, p[1]);
    }
    return (long) (maxx - minx) * (maxy - miny);
  }

  /** Drop vertices collinear with their neighbours (axis-aligned runs). */
  private static List<int[]> simplifyCollinear(List<int[]> ring) {
    int n = ring.size();
    if (n < 3) return ring;
    List<int[]> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      int[] a = ring.get((i - 1 + n) % n), b = ring.get(i), c = ring.get((i + 1) % n);
      long cross = (long) (b[0] - a[0]) * (c[1] - a[1]) - (long) (b[1] - a[1]) * (c[0] - a[0]);
      if (cross != 0) out.add(b); // keep only corners (turns)
    }
    return out.size() >= 3 ? out : ring;
  }

  private static double percentile(double[] vals, double p) {
    if (vals.length == 0) return 0;
    double[] s = vals.clone();
    Arrays.sort(s);
    int i = (int) Math.floor(p * (s.length - 1));
    if (i < 0) i = 0;
    if (i >= s.length) i = s.length - 1;
    return s[i];
  }
}
