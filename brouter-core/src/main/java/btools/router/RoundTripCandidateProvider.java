package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

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
 *   <li>{@link RadialCandidateProvider} — purely geometric ring around the current
 *       position. Legacy/debug fallback only; production AUTO does not use this for
 *       greedy placement.</li>
 * </ul>
 *
 * <p>Experimental, off-by-default implementations (each gated on a
 * {@link RoutingContext} flag and never built on the default path) wrap the
 * graph-native provider and only annotate its candidates with extra scoring hints:
 * <ul>
 *   <li>{@link DesirabilityCandidateProvider} — {@code roundTripDesirability}; adds
 *       high-profile-desirability cells and tags each candidate's {@link CandidatePoint#desirability}.</li>
 *   <li>{@link CapsuleCandidateProvider} — {@code roundTripCapsule}; tags
 *       {@link CandidatePoint#capsuleReward}/{@link CandidatePoint#elevationReward}
 *       to steer waypoints out of dense urban interiors and toward higher ground.</li>
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

  /** Sentinel for "iso cost-from-start not available" (radial candidates). */
  double NO_ISO_COST = -1;
  /** Sentinel for "iso bucket-density not available" (radial candidates). */
  int NO_ISO_DENSITY = -1;
  /** Sentinel for "iso source contour not available" (radial candidates). */
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
    /**
     * Normalized [0,1] profile-desirability of this candidate's cell (issue #15).
     * Only {@link DesirabilityCandidateProvider} sets it; all other providers leave
     * it at 0, so the planner's desirability reward is a no-op for them.
     */
    public double desirability;
    /**
     * Capsule-steering reward [0,1] (urban-capsule loop prototype): 0 for dense
     * capsule interiors, {@code OPEN_REWARD} for open countryside, 1 for boundary
     * "portal" cells. Only {@link CapsuleCandidateProvider} sets it; every other
     * provider leaves it at 0, so the planner's capsule reward is a no-op for them.
     */
    public double capsuleReward;
    /**
     * Elevation reward [0,1] (urban-capsule loop prototype): higher ground scores
     * higher, to counter the greedy planner's flat-terrain bias. Only
     * {@link CapsuleCandidateProvider} sets it; 0 for every other provider.
     */
    public double elevationReward;
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

  /**
   * Default: place {@code count} candidates on a ring at {@code airRadius} from
   * the current position. Identical to the legacy in-planner generation.
   */
  final class RadialCandidateProvider implements RoundTripCandidateProvider {

    private static final int DEFAULT_DIRECTIONS = 12;

    private final int directions;

    public RadialCandidateProvider() {
      this(DEFAULT_DIRECTIONS);
    }

    public RadialCandidateProvider(int directions) {
      this.directions = directions;
    }

    @Override
    public List<CandidatePoint> candidatesForStep(
      int fromIlon, int fromIlat, double airRadius,
      int step, int totalSteps,
      int startIlon, int startIlat,
      double startDirection,
      OsmTrack refTrack) {
      // baseAngle: align the first ring slot with the user's direction on the
      // first two steps so the loop heads where the user asked; thereafter let
      // the scorer's loop/direction terms decide.
      double baseAngle = (step <= 2 && startDirection >= 0) ? startDirection : 0;
      double angleStep = 360.0 / directions;
      List<CandidatePoint> points = new ArrayList<>(directions);
      for (int i = 0; i < directions; i++) {
        double bearing = CheapAngleMeter.normalize(baseAngle + i * angleStep);
        int[] dest = CheapRuler.destination(fromIlon, fromIlat, airRadius, bearing);
        CandidatePoint cp = new CandidatePoint();
        cp.ilon = dest[0];
        cp.ilat = dest[1];
        cp.bearing = bearing;
        points.add(cp);
      }
      return points;
    }
  }
}
