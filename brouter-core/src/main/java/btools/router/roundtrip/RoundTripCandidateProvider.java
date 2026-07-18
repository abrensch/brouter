package btools.router.roundtrip;

import btools.router.OsmTrack;

import java.util.List;

/**
 * Generates candidate next-step points for {@link GreedyRoundTripPlanner}.
 *
 * <p>Production implementations:
 * <ul>
 *   <li>{@link GraphNativeCandidateProvider} — bounded Dijkstra from the current
 *       graph position; the default GREEDY source.</li>
 *   <li>{@link BlendedCandidateProvider} — the ISO_GREEDY source: a start-centered
 *       {@link IsochroneCandidateProvider} pool plus per-step graph-native
 *       candidates. {@link IsochroneCandidateProvider} is only ever used through
 *       this blend, never on its own.</li>
 * </ul>
 *
 * <p>The planner routes a small subset and picks the best by actual routed
 * distance, cost, reuse and shape, so candidates only need to rank well.
 */
public interface RoundTripCandidateProvider {

  /**
   * Return candidate next-step points from the current position.
   *
   * @param fromIlon       current position longitude (1e6 ilon units)
   * @param fromIlat       current position latitude  (1e6 ilat units)
   * @param airRadius      target air-distance to the next waypoint (meters);
   *                       providers center output here but may spread within a window
   * @param step           1-based current step (1 = first hop from start)
   * @param startIlon      loop start longitude (the loop must close near here)
   * @param startDirection user-requested initial bearing in [0, 360), or &lt;0 for ANY
   * @return ordered candidates (any size; planner routes up to a small cap)
   */
  List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack);

  /** Sentinel for "iso cost-from-start not available" (non-iso providers). */
  double NO_ISO_COST = -1;
  /** Sentinel for "iso bucket-density not available" (non-iso providers). */
  int NO_ISO_DENSITY = -1;
  /** Sentinel for "iso source contour not available" (non-iso providers). */
  int NO_ISO_CONTOUR = -1;

  /**
   * A candidate next-step point. {@link #ilon}/{@link #ilat}/{@link #bearing} are
   * always present. The remaining fields are optional metadata from
   * {@link IsochroneCandidateProvider} — sentinel values ({@link #NO_ISO_COST},
   * {@link #NO_ISO_DENSITY}, {@link #NO_ISO_CONTOUR}) mean "this came from a
   * non-iso provider".
   *
   * <p>The planner sets {@link #score} during ranking; providers may leave it at 0.
   */
  final class CandidatePoint {
    int ilon;
    int ilat;
    double bearing;
    double score; // heuristic score — set by the planner during ranking
    /** Dijkstra cost-units from the loop start to this candidate; {@link #NO_ISO_COST} = unavailable. */
    double costFromStart = NO_ISO_COST;
    /** Population of this candidate's angular bucket in the isochrone; {@link #NO_ISO_DENSITY} = unavailable. */
    int bucketHits = NO_ISO_DENSITY;
    /** Source contour (25/50/75/100) the iso candidate was sampled from; {@link #NO_ISO_CONTOUR} = unavailable. */
    int sourceContour = NO_ISO_CONTOUR;
    /**
     * Optional graph-native leg from the current position to this candidate.
     * When present, the planner scores and accepts this exact Dijkstra leg
     * instead of routing to the coordinate again.
     */
    OsmTrack routedTrack;
    /**
     * Occupied reachability-cloud cells in the candidate's 5×5 neighborhood
     * (0..25; see {@link IsochroneExpansionResult#reachableCellsAround}), or -1
     * when no cloud is available. Low = dead-end pocket / thin corridor (the
     * teardrop/stub signature).
     */
    int reachableCells = -1;
    /**
     * True when this candidate held its routed slot only via source-quota
     * injection (not on heuristic score + angular spread). Such a candidate
     * WINNING the routed comparison is direct evidence the iso pool outranked a
     * better local alternative. Candidates are built fresh per provider call, so
     * the flag never leaks across steps or attempts.
     */
    boolean quotaInjected;
  }
}
