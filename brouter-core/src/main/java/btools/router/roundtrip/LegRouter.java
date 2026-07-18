package btools.router.roundtrip;

import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;

/**
 * Engine seam role: leg searches, waypoint matching, isochrone expansion,
 * and the kill switch.
 */
public interface LegRouter {

  /** One leg search — the engine's findTrack primitive. */
  OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                     OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc);

  /**
   * Leg search with the engine's live guide track suspended — a local repair
   * search must not be steered by the track it is repairing.
   */
  OsmTrack findTrackUnguided(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp);

  /**
   * Leg search under its own time budget: the engine saves/restores its clock
   * and runs goal-directed at the profile's pass-1 coefficient.
   */
  OsmTrack findTrackTimed(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                          OsmTrack refTrack, long budgetMs);

  /** Re-run a raw track at full detail between its endpoints. */
  OsmTrack retrackForDetail(OsmTrack rawTrack, MatchedWaypoint startWp, MatchedWaypoint endWp,
                            OsmTrack refTrack);

  /** Profile-aware road snap for a single point. */
  MatchedWaypoint profileAwareMatchPoint(int ilon, int ilat, String name, double maxSnapDist);

  /** Batch waypoint matching through the engine's node cache. */
  void matchWaypointsToNodes(List<MatchedWaypoint> waypoints, double maxDistance);

  void resetCache(boolean detailed);

  /** Wall-clock bound for the next isochrone expansion; 0 clears it. */
  void setTransientExpansionDeadline(long deadlineMillis);

  /** Start-centered isochrone expansion (placement variant). */
  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius);

  /** Start-centered isochrone expansion. */
  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius,
                                                 OsmTrack refTrack, boolean includeCandidateTracks);

  /** Terminate the engine's current search (volatile kill flag). */
  void terminate();

  boolean isTerminated();

  /**
   * Register a hook run when THIS engine is terminated — the cascade that
   * forwards a server pre-emption to child engines (typically
   * {@code child::terminate}). Registering after termination runs the hook
   * immediately.
   */
  void addTerminationHook(Runnable hook);
}
