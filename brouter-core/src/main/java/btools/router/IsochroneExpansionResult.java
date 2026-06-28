package btools.router;

import java.util.Collections;
import java.util.List;

/**
 * Result of {@link RoutingEngine#runIsochroneExpansion}.
 *
 * <p>Holds both the per-bucket frontier table (used by the legacy {@link
 * RoutingEngine#placeWaypointsFromIsochrone} debug-only path) and the road-native
 * candidate pool (used by ISO_GREEDY via {@link IsochroneCandidateProvider}).
 * Returning both as one value keeps callers from reading stale data via a
 * side-channel field.
 */
final class IsochroneExpansionResult {

  /**
   * Per-bucket frontier table. Each entry is {@code [direction_deg, airDist_m,
   * cost, hits, ilon, ilat]} — the first four fields are the legacy contract
   * (existing readers keep working); the trailing {@code ilon}/{@code ilat}
   * expose the road-native coordinate of the selected frontier node so direct
   * ISOCHRONE placement can avoid synthesizing waypoint positions via
   * {@link btools.util.CheapRuler#destination CheapRuler.destination}.
   *
   * <p>Probe-only entries injected by {@link RoutingEngine#mergeIsochroneWithProbe}
   * remain 4-element (no road-native data available); callers must guard with
   * {@code entry.length >= 6} before reading {@code ilon}/{@code ilat}.
   */
  final double[][] frontier;

  /** Road-native candidates extracted from intermediate cost contours + the frontier max. */
  final List<IsoCandidate> candidates;

  /**
   * Downsampled reachability cloud: every node the expansion popped, bucketed
   * into ~{@link RoutingEngine#REACHABILITY_CELL_M}-sized grid cells (fixed
   * per-expansion scale; divisors below). A candidate surrounded by many
   * occupied cells sits in a well-connected neighborhood; a dead-end pocket
   * tip sits in a thin corridor of cells. Used for pocket-avoiding waypoint
   * placement; empty for legacy constructions.
   */
  final java.util.Set<Long> visitedCells;
  /** ilon units per reachability cell (longitude divisor), 0 when no cloud. */
  final int cellDivLon;
  /** ilat units per reachability cell (latitude divisor), 0 when no cloud. */
  final int cellDivLat;

  IsochroneExpansionResult(double[][] frontier, List<IsoCandidate> candidates) {
    this(frontier, candidates, Collections.emptySet(), 0, 0);
  }

  IsochroneExpansionResult(double[][] frontier, List<IsoCandidate> candidates,
                           java.util.Set<Long> visitedCells, int cellDivLon, int cellDivLat) {
    this.frontier = frontier;
    this.candidates = (candidates != null) ? candidates : Collections.emptyList();
    this.visitedCells = (visitedCells != null) ? visitedCells : Collections.emptySet();
    this.cellDivLon = cellDivLon;
    this.cellDivLat = cellDivLat;
  }

  /**
   * Count occupied reachability cells in the 5×5 neighborhood (~750×750m)
   * around the given position — 0..25, or -1 when no cloud was collected.
   * A half-disk at the expansion's geographic edge still scores ~12, so a
   * penalty threshold around 10 does not punish well-connected candidates
   * near the edge.
   */
  int reachableCellsAround(int ilon, int ilat) {
    if (visitedCells.isEmpty() || cellDivLon <= 0 || cellDivLat <= 0) return -1;
    int cx = ilon / cellDivLon;
    int cy = ilat / cellDivLat;
    int count = 0;
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        if (visitedCells.contains((((long) (cx + dx)) << 32) | ((cy + dy) & 0xFFFFFFFFL))) {
          count++;
        }
      }
    }
    return count;
  }
}
