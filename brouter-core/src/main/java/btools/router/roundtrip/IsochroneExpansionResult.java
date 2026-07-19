package btools.router.roundtrip;

import java.util.Collections;
import java.util.List;

/**
 * Result of {@code RoutingEngine#runIsochroneExpansion}: the per-bucket frontier
 * table (legacy debug-only placement) and the road-native candidate pool (used
 * by ISO_GREEDY via {@link IsochroneCandidateProvider}). Returning both as one
 * value keeps callers off a stale side-channel field.
 */
public final class IsochroneExpansionResult {

  /**
   * Per-bucket frontier table. Each entry is {@code [direction_deg, airDist_m,
   * cost, hits, ilon, ilat]}; the first four are the legacy contract, and the
   * trailing {@code ilon}/{@code ilat} give the frontier node's road-native
   * coordinate (so ISOCHRONE placement need not synthesize positions). Probe-only
   * entries from {@code RoutingEngine#mergeIsochroneWithProbe} stay 4-element, so
   * guard with {@code entry.length >= 6} before reading {@code ilon}/{@code ilat}.
   */
  public final double[][] frontier;

  /** Road-native candidates extracted from intermediate cost contours + the frontier max. */
  public final List<IsoCandidate> candidates;

  /**
   * Downsampled reachability cloud: popped nodes bucketed into
   * ~{@code RoutingEngine#REACHABILITY_CELL_M}-sized cells (divisors below),
   * mapped to the minimum Dijkstra cost at which each cell was first reached
   * (pops arrive in cost order, so first touch is the minimum). Key presence
   * answers "reachable" (pocket-avoiding placement); the cost feeds
   * {@link ReturnDistanceOracle}. Empty for legacy constructions.
   */
  final java.util.Map<Long, Integer> cellMinCost;
  /** ilon units per reachability cell (longitude divisor), 0 when no cloud. */
  final int cellDivLon;
  /** ilat units per reachability cell (latitude divisor), 0 when no cloud. */
  final int cellDivLat;

  /**
   * Fast-motor pocket verdict: the start match landed on a small road island
   * (the expansion exhausted its component without reaching the geographic
   * cutoff) and the pocket pair was recorded for the matcher to avoid. Always
   * {@code false} for bike/foot profiles.
   */
  public final boolean startPocket;

  public IsochroneExpansionResult(double[][] frontier, List<IsoCandidate> candidates) {
    this(frontier, candidates, Collections.emptyMap(), 0, 0, false);
  }

  public IsochroneExpansionResult(double[][] frontier, List<IsoCandidate> candidates,
                           java.util.Map<Long, Integer> cellMinCost, int cellDivLon, int cellDivLat) {
    this(frontier, candidates, cellMinCost, cellDivLon, cellDivLat, false);
  }

  public IsochroneExpansionResult(double[][] frontier, List<IsoCandidate> candidates,
                           java.util.Map<Long, Integer> cellMinCost, int cellDivLon, int cellDivLat,
                           boolean startPocket) {
    this.frontier = frontier;
    this.candidates = (candidates != null) ? candidates : Collections.emptyList();
    this.cellMinCost = (cellMinCost != null) ? cellMinCost : Collections.emptyMap();
    this.cellDivLon = cellDivLon;
    this.cellDivLat = cellDivLat;
    this.startPocket = startPocket;
  }

  /**
   * Occupied reachability cells in the 5×5 neighborhood (~750×750m) around the
   * position — 0..25, or -1 when no cloud was collected. A half-disk at the
   * expansion edge still scores ~12, so a penalty threshold near 10 does not
   * punish well-connected edge candidates.
   */
  int reachableCellsAround(int ilon, int ilat) {
    if (cellMinCost.isEmpty() || cellDivLon <= 0 || cellDivLat <= 0) return -1;
    int cx = ilon / cellDivLon;
    int cy = ilat / cellDivLat;
    int count = 0;
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        if (cellMinCost.containsKey((((long) (cx + dx)) << 32) | ((cy + dy) & 0xFFFFFFFFL))) {
          count++;
        }
      }
    }
    return count;
  }
}
