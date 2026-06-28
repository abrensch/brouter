package btools.router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.util.CheapRuler;

/**
 * Spatial corridor-overlap detector for round-trip routes.
 *
 * <p>The reuse signals in {@link ReuseClassifier} and {@link LoopQualityMetrics}
 * are keyed on <em>edge identity</em> (the undirected node-pair hash). That is
 * blind to a return path that runs <em>alongside</em> the outbound on a
 * parallel/different way a few tens of metres over — the route never re-uses
 * the same edge, yet a cyclist perceives it as same-corridor-back. This class
 * adds the missing signal: it flags edges whose ground track passes within
 * {@link #CORRIDOR_RADIUS_M} of an earlier part of the same track that is far
 * away in <em>path</em> distance (more than {@link #OVERLAP_PATHDIST_WINDOW_M}).
 *
 * <p>Only the <em>later</em> traversal of a co-located pair is flagged (the
 * outbound is the "first visit", the parallel return is the "reuse") — this
 * mirrors edge-identity semantics so the two signals union cleanly.
 *
 * <p><b>Fixed-scale invariant.</b> The integer ilon/ilat → metre conversion
 * uses one lat-scale, computed once from the track's start latitude. CheapRuler
 * caches its scale in ~11 km latitude bands, so recomputing the scale per
 * segment could map one physical point into two different grid cells across a
 * loop and silently corrupt occupancy. The scale is therefore captured once and
 * reused for every sample.
 *
 * <p>Post-hoc only (one pass over a finished track); the per-candidate
 * incremental occupancy used by the planner lives in a separate store.
 */
final class CorridorOverlapIndex {

  /**
   * Corridor half-width: two ground tracks closer than this are treated as the
   * same corridor. Also the spatial-hash cell size. 40 m catches a tight
   * parallel return while leaving 100–200 m-separated parallel streets (a
   * legitimate loop-through, not a same-way-back) un-flagged. Load-bearing —
   * pinned by {@code CorridorOverlapIndexTest}'s lower and upper guards.
   */
  static final double CORRIDOR_RADIUS_M = 40.0;

  /** Sampling step along each segment (≤ radius/2 so no cell is skipped). */
  static final double SAMPLE_STEP_M = 20.0;

  /**
   * Minimum path-distance separation for two co-located samples to count as a
   * corridor overlap. Below this they are trivial along-path neighbours (the
   * point just before/after, or a tight local switchback) — never a retrace.
   */
  static final double OVERLAP_PATHDIST_WINDOW_M = 600.0;

  /**
   * Narrow closure exclusion: a closed loop legitimately shares ground at its
   * start/end pin. Samples whose <em>both</em> ends lie within this radius of
   * the start pin are exempt from overlap, so a clean loop closing at a point
   * is not mistaken for a corridor. Kept small so it does not erase a genuine
   * parallel corridor that merely begins near the start.
   */
  static final double CLOSURE_EXCLUDE_M = 100.0;

  private CorridorOverlapIndex() { /* static-only */ }

  /**
   * Flag, per edge, whether that edge's ground track parallels an earlier and
   * path-distant part of the same track.
   *
   * @return {@code boolean[track.nodes.size() - 1]}; {@code [i]} is the edge
   *         from node {@code i} to node {@code i + 1}. Empty array for tracks
   *         with fewer than two nodes.
   */
  static boolean[] computeEdgeOverlap(OsmTrack track) {
    return computeEdgeOverlap(track == null ? null : track.nodes);
  }

  /**
   * Node-list overload — shared by the gate ({@link ReuseClassifier}) and the
   * reuse metric ({@link LoopQualityMetrics}) so they cannot drift.
   */
  static boolean[] computeEdgeOverlap(List<OsmPathElement> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return new boolean[0];
    }
    int edgeCount = nodes.size() - 1;

    // Fixed scale + origin (start node).
    OsmPathElement start = nodes.get(0);
    double[] kxky = CheapRuler.getLonLatToMeterScales(start.getILat());
    double kx = kxky[0];
    double ky = kxky[1];
    int ilon0 = start.getILon();
    int ilat0 = start.getILat();

    // Sample every segment; record (mx, my, cum, edgeIndex). Each edge is
    // sampled every SAMPLE_STEP_M so a long edge is judged by the fraction of
    // its length that overlaps — not flagged wholesale because one tip touches
    // an earlier corridor (real routed edges are ~tens of metres, so this only
    // matters for sparse synthetic geometry, but the fraction rule is correct
    // either way).
    List<double[]> samples = new ArrayList<>(edgeCount * 2 + 4);
    int[] edgeSampleCount = new int[edgeCount];
    int[] edgeFlaggedCount = new int[edgeCount];
    double cum = 0;
    for (int i = 0; i < edgeCount; i++) {
      OsmPathElement a = nodes.get(i);
      OsmPathElement b = nodes.get(i + 1);
      double segLen = a.calcDistance(b);
      int n = Math.max(1, (int) Math.ceil(segLen / SAMPLE_STEP_M));
      double amx = (a.getILon() - ilon0) * kx, amy = (a.getILat() - ilat0) * ky;
      double bmx = (b.getILon() - ilon0) * kx, bmy = (b.getILat() - ilat0) * ky;
      // Sample interior + end of the segment (k=1..n); the segment start (k=0)
      // is the previous segment's end, already sampled (k=0 only for the very
      // first segment, added once below).
      if (i == 0) {
        samples.add(sample(amx, amy, cum, 0));
        edgeSampleCount[0]++;
      }
      for (int k = 1; k <= n; k++) {
        double s = (double) k / n;
        double mx = amx + (bmx - amx) * s;
        double my = amy + (bmy - amy) * s;
        samples.add(sample(mx, my, cum + segLen * s, i));
        edgeSampleCount[i]++;
      }
      cum += segLen;
    }

    // Spatial hash: cell -> sample indices. Cell size = corridor radius.
    Map<Long, List<Integer>> grid = new HashMap<>(samples.size() * 2);
    for (int idx = 0; idx < samples.size(); idx++) {
      double[] sm = samples.get(idx);
      grid.computeIfAbsent(cellKey(sm[0], sm[1]), k -> new ArrayList<>()).add(idx);
    }

    boolean[] overlap = new boolean[edgeCount];
    double r2 = CORRIDOR_RADIUS_M * CORRIDOR_RADIUS_M;
    double closure2 = CLOSURE_EXCLUDE_M * CLOSURE_EXCLUDE_M;
    for (int idx = 0; idx < samples.size(); idx++) {
      double[] si = samples.get(idx);
      double mx = si[0], my = si[1], cumI = si[2];
      int edge = (int) si[3];
      if (edge >= edgeCount) continue; // the lone k=0 start sample carries edge 0
      double pinDistI2 = mx * mx + my * my; // start pin is the origin
      boolean flagged = false;
      int cx = floorDiv(mx), cy = floorDiv(my);
      for (int dx = -1; dx <= 1 && !flagged; dx++) {
        for (int dy = -1; dy <= 1 && !flagged; dy++) {
          List<Integer> bucket = grid.get(packCell(cx + dx, cy + dy));
          if (bucket == null) continue;
          for (int j : bucket) {
            double[] sj = samples.get(j);
            // Only earlier-by-more-than-window samples count (flag the return,
            // not the outbound).
            if (sj[2] >= cumI - OVERLAP_PATHDIST_WINDOW_M) continue;
            double ex = sj[0] - mx, ey = sj[1] - my;
            if (ex * ex + ey * ey >= r2) continue;
            // Narrow closure exclusion: both ends near the start pin.
            double pinDistJ2 = sj[0] * sj[0] + sj[1] * sj[1];
            if (pinDistI2 < closure2 && pinDistJ2 < closure2) continue;
            flagged = true;
            break;
          }
        }
      }
      if (flagged) edgeFlaggedCount[edge]++;
    }
    // An edge is a corridor overlap only when the majority of its sampled
    // length parallels an earlier path — so a long edge that merely touches an
    // earlier corridor at one tip is not flagged wholesale.
    for (int e = 0; e < edgeCount; e++) {
      overlap[e] = edgeSampleCount[e] > 0
        && edgeFlaggedCount[e] * 2 >= edgeSampleCount[e];
    }
    return overlap;
  }

  private static double[] sample(double mx, double my, double cum, int edgeIndex) {
    return new double[]{mx, my, cum, edgeIndex};
  }

  private static int floorDiv(double meters) {
    return (int) Math.floor(meters / CORRIDOR_RADIUS_M);
  }

  private static long cellKey(double mx, double my) {
    return packCell(floorDiv(mx), floorDiv(my));
  }

  private static long packCell(int cx, int cy) {
    return ((cx & 0xFFFFFFFFL) << 32) | (cy & 0xFFFFFFFFL);
  }
}
