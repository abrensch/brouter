package btools.router.roundtrip;

import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;

/**
 * Engine seam role: the engine's side of the request-state handoff. The
 * mutable request state itself lives on the orchestrator-owned
 * {@code RoundTripRequest}; this interface carries only the request-entry
 * seeds (deadline, routing budget, effort policy), the runtime-hints
 * publication for the engine's search loops, the engine-shared output lists
 * (waypoints, matched waypoints), the result seed/publication (track/error),
 * and the end-of-request telemetry publications.
 */
public interface RoundTripRequestState {

  /** Live request waypoint list; the orchestrator appends generated vias. */
  List<OsmNodeNamed> waypoints();

  /**
   * The engine's result track; null while unset. Exactly one of foundTrack
   * and errorMessage is set on a finished request.
   */
  OsmTrack foundTrack();

  void setFoundTrack(OsmTrack track);

  /** The engine's error message; see {@link #foundTrack()} for the contract. */
  String errorMessage();

  void setErrorMessage(String message);

  /** Matched waypoints of the final track. */
  List<MatchedWaypoint> matchedWaypoints();

  void setMatchedWaypoints(List<MatchedWaypoint> waypoints);

  /** Engine start wall-clock millis. */
  long startTime();

  /** Engine run budget in millis; 0 = untimed. */
  long maxRunningTime();

  void setMaxRunningTime(long maxRunningTimeMillis);

  /** Wall-clock deadline set by doRun for the whole request; request-entry seed. */
  long roundTripRequestDeadline();

  /**
   * The single publication point for the request fields the engine's search
   * loops read mid-search (radius, deadline, explicit-via, guide tracks).
   */
  void setRoundTripRuntimeHints(RoundTripRuntimeHints hints);

  /** Per-leg routing budget (ms) computed by doRun; request-entry seed. */
  long roundTripRoutingBudgetMs();

  /** Effort preset request-entry seed / child-engine handoff. */
  RoundTripEffortPolicy roundTripEffortPolicy();

  void setRoundTripEffortPolicy(RoundTripEffortPolicy policy);

  /** End-of-request publication for {@code getLastRejectedTrack()}. */
  void setLastRejectedTrack(OsmTrack track);

  /** Publish the planner result for telemetry. */
  void setLastRoundTripResult(RoundTripResult result);

  /**
   * Publish the request's final quality-gate verdict (with its stamped
   * {@code LoopAnalysis}). Null when the request never reached the gate
   * (early rejects: floors, budget, no loop). The AUTO competition reads a
   * child's published verdict instead of re-gating the same track in the
   * parent — the child's gate run is the single evaluation per candidate.
   */
  void setLastRoundTripQuality(RoundTripQualityResult quality);

  /** Add a child engine's link expansions to this engine's work counter. */
  void addLinksProcessed(long links);
}
