package btools.router;

import java.util.List;

/**
 * Generates candidate next-step points for {@link GreedyRoundTripPlanner}.
 *
 * <p>Production implementations:
 * <ul>
 *   <li>{@link GraphNativeCandidateProvider} — bounded Dijkstra from the
 *       current graph position. This is the default GREEDY candidate source.</li>
 *   <li>{@link BlendedCandidateProvider} — the ISO_GREEDY source: it concatenates
 *       a start-centered {@link IsochroneCandidateProvider} pool with per-step
 *       graph-native candidates. {@link IsochroneCandidateProvider} is not wired
 *       on its own; it only feeds the blend.</li>
 * </ul>
 *
 * <p>The planner then routes a small number of these candidates and chooses the best
 * by actual routed distance, cost, reuse and shape — so candidate quality only has
 * to be good enough to rank well; the planner is the arbiter of final selection.
 */
public interface RoundTripCandidateProvider {

  /**
   * Return candidate next-step points from the current position. Caller is expected
   * to route a subset of the returned candidates and pick the best by actual metrics.
   *
   * @param fromIlon       current position longitude (1e6 ilon units)
   * @param fromIlat       current position latitude  (1e6 ilat units)
   * @param airRadius      target air-distance from the current position to the
   *                       next waypoint (meters). Providers should center their
   *                       output here but may return candidates inside a window.
   * @param step           1-based current step (1 = first hop from start)
   * @param totalSteps     total planned steps in the loop
   * @param startIlon      loop start longitude (the loop must close near here)
   * @param startIlat      loop start latitude
   * @param startDirection user-requested initial bearing in [0, 360), or &lt;0 for ANY
   * @return ordered candidates (any size; planner will route up to a small cap)
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
    public int ilon;
    public int ilat;
    public double bearing;
    public double score; // heuristic score — set by the planner during ranking
    /** Dijkstra cost-units from the loop start to this candidate; {@link #NO_ISO_COST} = unavailable. */
    public double costFromStart = NO_ISO_COST;
    /** Population of this candidate's angular bucket in the isochrone; {@link #NO_ISO_DENSITY} = unavailable. */
    public int bucketHits = NO_ISO_DENSITY;
    /** Source contour (25/50/75/100) the iso candidate was sampled from; {@link #NO_ISO_CONTOUR} = unavailable. */
    public int sourceContour = NO_ISO_CONTOUR;
    /**
     * Optional graph-native leg from the current position to this candidate.
     * When present, the greedy planner can score and accept this exact Dijkstra
     * leg instead of routing to the candidate coordinate a second time.
     */
    public OsmTrack routedTrack;
    /**
     * Reachability-cloud cells occupied in the candidate's 5×5 neighborhood
     * (0..25; see {@link IsochroneExpansionResult#reachableCellsAround}), or
     * -1 when no cloud is available. Low values mark dead-end pockets / thin
     * corridors — the placement signature behind teardrop and stub artifacts.
     */
    public int reachableCells = -1;
  }
}
