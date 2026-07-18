package btools.router.roundtrip;

import btools.router.OsmTrack;

/**
 * Mutable state of one round-trip request, owned by the
 * {@link RoundTripOrchestrator} (one instance per {@code doRoundTrip} call).
 * Replaces the shared engine fields the orchestrator used to mutate through
 * the seam: the engine only receives what its search loops read (published as
 * runtime hints) plus the final telemetry values at request end.
 *
 * <p>The four hint-backed fields (search radius, request deadline,
 * explicit-via, guide tracks) are private with publishing setters — every
 * mutation reaches the engine's search loops immediately, so "mutated but
 * unpublished" is unrepresentable.
 */
final class RoundTripRequest {

  private final RoundTripRequestState engine;

  RoundTripRequest(RoundTripRequestState engine) {
    this.engine = engine;
  }

  /**
   * The request's working result track; published to the engine once at
   * request end. Exactly one of track and error is meaningful on a finished
   * request (track-XOR-error).
   */
  OsmTrack track;

  /** The request's working error message; see {@link #track}. */
  String error;

  /** Active effort preset, seeded from the engine's request-entry value. */
  RoundTripEffortPolicy effortPolicy = RoundTripEffortPolicy.STANDARD_PRESET;

  /** Active search radius in meters (published to the engine as a hint). */
  private double searchRadius;

  /** Wall-clock request deadline (epoch ms), seeded from doRun; 0 = unbounded. */
  private long requestDeadline;

  /** True in explicit-via mode (published to the engine as a hint). */
  private boolean explicitVia;

  /** Per-leg guide tracks from a greedy adoption (published as a hint). */
  private OsmTrack[] greedyLegTracks;

  void setSearchRadius(double searchRadius) {
    this.searchRadius = searchRadius;
    publishHints();
  }

  long requestDeadline() {
    return requestDeadline;
  }

  void setRequestDeadline(long requestDeadline) {
    this.requestDeadline = requestDeadline;
    publishHints();
  }

  void setExplicitVia(boolean explicitVia) {
    this.explicitVia = explicitVia;
    publishHints();
  }

  void setGreedyLegTracks(OsmTrack[] greedyLegTracks) {
    this.greedyLegTracks = greedyLegTracks;
    publishHints();
  }

  /** Publish the engine-read slice of this request to the engine's search loops. */
  private void publishHints() {
    engine.setRoundTripRuntimeHints(new RoundTripRuntimeHints(
      searchRadius, requestDeadline, explicitVia, greedyLegTracks));
  }

  /** Request-owned paved/road-bike classification, probed once at request entry. */
  boolean pavedProfile;

  /** Per-leg routing budget (ms) for the round-trip fallthrough; 0 = untimed. */
  long routingBudgetMs;

  /** Bounded tier's gate verdict, consumed once by the shared gate. The AUTO
   *  competition stashes its winner's (parent-computed) verdict here too, so
   *  the shared finalization never re-gates an already-gated candidate. */
  RoundTripQualityResult boundedGateVerdict;

  /**
   * Set by the AUTO competition on adoption: the winner came from a child
   * engine that already enforced the waypoint/node/meter floors internally, so
   * the shared finalization skips them (the parent never placed waypoints for
   * this outcome; only the null-track safety net still applies).
   */
  boolean candidateFloorsEnforced;

  /**
   * Set by the AUTO competition on adoption: children run with output
   * suppressed (null outfileBase), so the parent writes the single output
   * artifact — AFTER the shared finalization has decorated the track, so the
   * written file carries the advisories.
   */
  boolean deferredOutputWrite;

  /** Forced same-way-back corridor latch from the greedy adoption. */
  boolean forcedCorridorAccepted;

  /** Last gate-rejected track; published to the engine at request end. */
  OsmTrack lastRejectedTrack;

  /** Final quality-gate verdict of this request (shipped or hard-rejected
   *  track); null when the gate was never reached. Published to the engine at
   *  request end so a parent AUTO competition can consume it instead of
   *  re-gating the child's track. */
  RoundTripQualityResult qualityVerdict;

  /** Planner result telemetry; published to the engine at request end. */
  RoundTripResult lastResult;
}
