package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;

/**
 * Mutable result of a round-trip planning attempt, produced by the greedy
 * planner (GREEDY / ISO_GREEDY) and read by {@code RoutingEngine}.
 *
 * <p>Carries the chosen {@link OsmTrack}, the loop waypoints/matched waypoints,
 * a few summary metrics (distance, reuse ratio, tolerance flag), and a large
 * block of diagnostic telemetry. The telemetry fields are write-once by the
 * planner and read-only afterwards; they are sentinel-valued (NaN / -1 / false)
 * when the producing path did not run, so a reader can tell "not applied" from
 * a real measurement. Setters exist only so the planner and engine can populate
 * the fields in stages — callers downstream treat the result as read-only.
 */
public class RoundTripResult {

  private OsmTrack track;
  private List<OsmNodeNamed> loopWaypoints;
  private List<MatchedWaypoint> matchedWaypoints;
  private int totalDistanceMeters;
  private double reusedEdgeRatio;
  private boolean withinTolerance;
  private int attemptsUsed;
  private int subRoutesChosen;
  private final List<String> diagnostics = new ArrayList<>();
  private String fallbackReason;
  private List<OsmTrack> legTracks; // per-leg sub-route tracks from greedy planner
  // Spec §10 telemetry — compute-budget audit signals.
  private int candidatesGenerated;
  private int candidatesRouted;
  private int returnChecksPerformed;
  private long runtimeMillis;
  // Auto-quality-redesign §132 telemetry: routed candidates broken down by
  // candidate source (iso-derived vs non-iso, i.e. graph-native in production).
  // The greedy planner identifies source via the existing
  // `costFromStart != NO_ISO_COST` sentinel.
  // "Routed" counts every candidate that the planner ran through Dijkstra;
  // "accepted" counts only those that became part of the final loop.
  // Low-iso-usage classification should use ACCEPTED legs, not routed.
  private int routedIsoCandidates;
  private int routedNonIsoCandidates;
  private int acceptedIsoLegs;
  private int acceptedNonIsoLegs;

  // Phase 2.0 telemetry — isochrone-asymmetry initial bearing.
  // Populated by RoutingEngine.doGreedyRoundTrip after running the
  // best-reaching-bearing computation. Sentinels (NaN / -1) when the
  // bias was not applied (explicit user direction, GREEDY mode, or
  // no bucket satisfied the frontier-quality thresholds).
  private boolean isoAsymmetryBearingApplied = false;
  private double  isoAsymmetryBearingDegrees = Double.NaN;
  private double  isoAsymmetryBestBucketIndirectness = Double.NaN;
  private int     isoAsymmetryBestBucketHits = -1;
  private int     isoAsymmetryBestBucketAirDistMeters = -1;

  // Phase 2.1 telemetry — frontier-axis retry. Populated when the planner
  // detected a strong terrain axis perpendicular to the user's direction
  // and retried with an axis-aligned bearing. Sentinels (NaN / 0 / false)
  // when 2.1 did not trigger.
  private boolean phase21AxisRetryTriggered = false;
  private boolean phase21AxisRetrySucceeded = false;
  private double  phase21AxisBearingDegrees = Double.NaN;
  private double  phase21AxisStrength = 0.0;
  private double  phase21RetryDirectionDegrees = Double.NaN;

  public OsmTrack getTrack() {
    return track;
  }

  public void setTrack(OsmTrack track) {
    this.track = track;
  }

  public List<OsmNodeNamed> getLoopWaypoints() {
    return loopWaypoints;
  }

  public void setLoopWaypoints(List<OsmNodeNamed> loopWaypoints) {
    this.loopWaypoints = loopWaypoints;
  }

  public List<MatchedWaypoint> getMatchedWaypoints() {
    return matchedWaypoints;
  }

  public void setMatchedWaypoints(List<MatchedWaypoint> matchedWaypoints) {
    this.matchedWaypoints = matchedWaypoints;
  }

  public int getTotalDistanceMeters() {
    return totalDistanceMeters;
  }

  public void setTotalDistanceMeters(int totalDistanceMeters) {
    this.totalDistanceMeters = totalDistanceMeters;
  }

  public double getReusedEdgeRatio() {
    return reusedEdgeRatio;
  }

  public void setReusedEdgeRatio(double reusedEdgeRatio) {
    this.reusedEdgeRatio = reusedEdgeRatio;
  }

  public boolean isWithinTolerance() {
    return withinTolerance;
  }

  public void setWithinTolerance(boolean withinTolerance) {
    this.withinTolerance = withinTolerance;
  }

  public int getAttemptsUsed() {
    return attemptsUsed;
  }

  public void setAttemptsUsed(int attemptsUsed) {
    this.attemptsUsed = attemptsUsed;
  }

  public int getSubRoutesChosen() {
    return subRoutesChosen;
  }

  public void setSubRoutesChosen(int subRoutesChosen) {
    this.subRoutesChosen = subRoutesChosen;
  }

  public List<String> getDiagnostics() {
    return diagnostics;
  }

  public void addDiagnostic(String message) {
    diagnostics.add(message);
  }

  public String getFallbackReason() {
    return fallbackReason;
  }

  public void setFallbackReason(String fallbackReason) {
    this.fallbackReason = fallbackReason;
  }

  /**
   * True when the returned loop is a rideable same-way-back corridor that the
   * planner kept because no clean alternative exists in this (constrained)
   * terrain. The request gate should accept it (disclosed) rather than reject
   * it as a plain corridor. See {@code GreedyRoundTripPlanner} keep-when-forced.
   */
  private boolean forcedCorridorAccepted = false;

  public boolean isForcedCorridorAccepted() {
    return forcedCorridorAccepted;
  }

  public void setForcedCorridorAccepted(boolean forcedCorridorAccepted) {
    this.forcedCorridorAccepted = forcedCorridorAccepted;
  }

  public List<OsmTrack> getLegTracks() {
    return legTracks;
  }

  public void setLegTracks(List<OsmTrack> legTracks) {
    this.legTracks = legTracks;
  }

  /** Number of candidate points produced by the candidate provider across all steps. */
  public int getCandidatesGenerated() {
    return candidatesGenerated;
  }

  public void setCandidatesGenerated(int candidatesGenerated) {
    this.candidatesGenerated = candidatesGenerated;
  }

  /** Number of candidate-leg sub-routes actually computed by Dijkstra. */
  public int getCandidatesRouted() {
    return candidatesRouted;
  }

  public void setCandidatesRouted(int candidatesRouted) {
    this.candidatesRouted = candidatesRouted;
  }

  /** Number of return-to-start feasibility Dijkstras performed. */
  public int getReturnChecksPerformed() {
    return returnChecksPerformed;
  }

  public void setReturnChecksPerformed(int returnChecksPerformed) {
    this.returnChecksPerformed = returnChecksPerformed;
  }

  /** Wall-clock duration of the planning attempt, milliseconds. */
  public long getRuntimeMillis() {
    return runtimeMillis;
  }

  public void setRuntimeMillis(long runtimeMillis) {
    this.runtimeMillis = runtimeMillis;
  }

  /** Number of iso-derived candidates the planner Dijkstra-routed. */
  public int getRoutedIsoCandidates() { return routedIsoCandidates; }
  public void setRoutedIsoCandidates(int v) { this.routedIsoCandidates = v; }

  /** Number of non-iso (graph-native) candidates the planner Dijkstra-routed. */
  public int getRoutedNonIsoCandidates() { return routedNonIsoCandidates; }
  public void setRoutedNonIsoCandidates(int v) { this.routedNonIsoCandidates = v; }

  /** Number of iso-derived candidates that became legs in the final loop. */
  public int getAcceptedIsoLegs() { return acceptedIsoLegs; }
  public void setAcceptedIsoLegs(int v) { this.acceptedIsoLegs = v; }

  /** Number of non-iso candidates that became legs in the final loop. */
  public int getAcceptedNonIsoLegs() { return acceptedNonIsoLegs; }
  public void setAcceptedNonIsoLegs(int v) { this.acceptedNonIsoLegs = v; }

  /** Whether Phase 2.0 isochrone-asymmetry bearing bias fired for this loop.
   *  False when the algorithm wasn't ISO_GREEDY, when the user provided an
   *  explicit direction, or when no frontier bucket met the quality
   *  thresholds (airDist &gt;= 0.6 * searchRadius AND hits &gt;= 3). */
  public boolean isIsoAsymmetryBearingApplied() { return isoAsymmetryBearingApplied; }
  public void setIsoAsymmetryBearingApplied(boolean v) { this.isoAsymmetryBearingApplied = v; }

  /** Bearing (degrees) the iso-asymmetry bias selected as the most-reaching
   *  sector; {@code NaN} when not applied. */
  public double getIsoAsymmetryBearingDegrees() { return isoAsymmetryBearingDegrees; }
  public void setIsoAsymmetryBearingDegrees(double v) { this.isoAsymmetryBearingDegrees = v; }

  /** {@code cost / airDist} of the bucket that won the bias; {@code NaN}
   *  when not applied. Lower = more direct reach. */
  public double getIsoAsymmetryBestBucketIndirectness() { return isoAsymmetryBestBucketIndirectness; }
  public void setIsoAsymmetryBestBucketIndirectness(double v) { this.isoAsymmetryBestBucketIndirectness = v; }

  /** Hit count of the bucket that won the bias; {@code -1} when not applied. */
  public int getIsoAsymmetryBestBucketHits() { return isoAsymmetryBestBucketHits; }
  public void setIsoAsymmetryBestBucketHits(int v) { this.isoAsymmetryBestBucketHits = v; }

  /** Air distance (meters) at the frontier of the winning bucket;
   *  {@code -1} when not applied. */
  public int getIsoAsymmetryBestBucketAirDistMeters() { return isoAsymmetryBestBucketAirDistMeters; }
  public void setIsoAsymmetryBestBucketAirDistMeters(int v) { this.isoAsymmetryBestBucketAirDistMeters = v; }

  /** Whether the Phase 2.1 axis-retry path fired. True when the first
   *  attempt (with user direction) produced a degraded result AND the
   *  frontier showed a strong terrain axis perpendicular to user direction. */
  public boolean isPhase21AxisRetryTriggered() { return phase21AxisRetryTriggered; }
  public void setPhase21AxisRetryTriggered(boolean v) { this.phase21AxisRetryTriggered = v; }

  /** Whether the Phase 2.1 axis retry produced an acceptable (non-degraded)
   *  loop. False either when retry was not triggered, or when retry also
   *  produced a degraded result (geographic infeasibility). */
  public boolean isPhase21AxisRetrySucceeded() { return phase21AxisRetrySucceeded; }
  public void setPhase21AxisRetrySucceeded(boolean v) { this.phase21AxisRetrySucceeded = v; }

  /** Bearing of the principal frontier axis (in [0, 180); axis is
   *  bidirectional). {@code NaN} when 2.1 did not trigger. */
  public double getPhase21AxisBearingDegrees() { return phase21AxisBearingDegrees; }
  public void setPhase21AxisBearingDegrees(double v) { this.phase21AxisBearingDegrees = v; }

  /** Eigenvalue ratio of the displacement covariance; higher = more
   *  elongated reachable region. {@code 0.0} when 2.1 did not trigger. */
  public double getPhase21AxisStrength() { return phase21AxisStrength; }
  public void setPhase21AxisStrength(double v) { this.phase21AxisStrength = v; }

  /** Direction used on the axis-retry attempt (axis-aligned bearing
   *  closest to the user's original direction). {@code NaN} when 2.1
   *  did not trigger. */
  public double getPhase21RetryDirectionDegrees() { return phase21RetryDirectionDegrees; }
  public void setPhase21RetryDirectionDegrees(double v) { this.phase21RetryDirectionDegrees = v; }
}
