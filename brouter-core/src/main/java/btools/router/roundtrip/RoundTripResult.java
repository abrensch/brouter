package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a round-trip planning attempt (GREEDY / ISO_GREEDY), read by
 * {@code RoutingEngine}. Carries the chosen {@link OsmTrack}, loop waypoints,
 * summary metrics, and diagnostic telemetry. Every setter is package-private —
 * staged population is confined to {@code btools.router.roundtrip} (the greedy
 * planner and its strategies); outside the package (confirmed: only
 * {@code RoutingEngine#getLastRoundTripResult} and its readers) this is
 * read-only, and the compiler enforces it rather than a comment convention.
 *
 * <p>List getters return unmodifiable views ({@link #getMatchedWaypoints()} a
 * defensive copy — the engine takes that list as mutable working state), so a
 * caller cannot alter the recorded plan through them. The contained
 * {@link OsmTrack}/waypoint OBJECTS stay mutable by engine design (tracks are
 * decorated after planning); deep-copying them per read would be
 * disproportionate — treat them as read-only unless you own the request.
 *
 * <p>Telemetry fields are sentinel-valued (NaN / -1 / false) when their
 * producing path did not run, so a reader can tell "not applied" from a real
 * measurement.
 */
public class RoundTripResult {

  private OsmTrack track;
  private List<OsmNodeNamed> loopWaypoints;
  private List<MatchedWaypoint> matchedWaypoints;
  private int totalDistanceMeters;
  private boolean withinTolerance;
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
  // Source-attribution telemetry: sentinel-valued when the plan ran
  // without an iso pool (plain GREEDY / graph-native-only provider).
  private int acceptedQuotaInjectedLegs;
  private int poolDemotedAtStep = -1;
  private double isoPoolHealthScore = Double.NaN;
  private boolean internalGraphNativeCompared;
  private boolean graphNativeOnlyStart;

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

  void setTrack(OsmTrack track) {
    this.track = track;
  }

  public List<OsmNodeNamed> getLoopWaypoints() {
    return loopWaypoints == null ? null : Collections.unmodifiableList(loopWaypoints);
  }

  void setLoopWaypoints(List<OsmNodeNamed> loopWaypoints) {
    this.loopWaypoints = loopWaypoints;
  }

  /** Defensive copy — the engine adopts the returned list as mutable working
   *  state (matched-waypoint stack), so an unmodifiable view would throw at
   *  runtime mid-request; a copy keeps the recorded plan intact instead. */
  public List<MatchedWaypoint> getMatchedWaypoints() {
    return matchedWaypoints == null ? null : new ArrayList<>(matchedWaypoints);
  }

  void setMatchedWaypoints(List<MatchedWaypoint> matchedWaypoints) {
    this.matchedWaypoints = matchedWaypoints;
  }

  public int getTotalDistanceMeters() {
    return totalDistanceMeters;
  }

  void setTotalDistanceMeters(int totalDistanceMeters) {
    this.totalDistanceMeters = totalDistanceMeters;
  }

  public boolean isWithinTolerance() {
    return withinTolerance;
  }

  void setWithinTolerance(boolean withinTolerance) {
    this.withinTolerance = withinTolerance;
  }

  public List<String> getDiagnostics() {
    return Collections.unmodifiableList(diagnostics);
  }

  void addDiagnostic(String message) {
    diagnostics.add(message);
  }

  public String getFallbackReason() {
    return fallbackReason;
  }

  void setFallbackReason(String fallbackReason) {
    this.fallbackReason = fallbackReason;
  }

  /**
   * True when the loop is a rideable same-way-back corridor the planner kept
   * because no clean alternative exists in this terrain. The gate should accept
   * it (disclosed), not reject it as a plain corridor.
   */
  private boolean forcedCorridorAccepted = false;

  public boolean isForcedCorridorAccepted() {
    return forcedCorridorAccepted;
  }

  void setForcedCorridorAccepted(boolean forcedCorridorAccepted) {
    this.forcedCorridorAccepted = forcedCorridorAccepted;
  }

  public List<OsmTrack> getLegTracks() {
    return legTracks == null ? null : Collections.unmodifiableList(legTracks);
  }

  void setLegTracks(List<OsmTrack> legTracks) {
    this.legTracks = legTracks;
  }

  /** Number of candidate points produced by the candidate provider across all steps. */
  public int getCandidatesGenerated() {
    return candidatesGenerated;
  }

  void setCandidatesGenerated(int candidatesGenerated) {
    this.candidatesGenerated = candidatesGenerated;
  }

  /** Number of candidate-leg sub-routes actually computed by Dijkstra. */
  public int getCandidatesRouted() {
    return candidatesRouted;
  }

  void setCandidatesRouted(int candidatesRouted) {
    this.candidatesRouted = candidatesRouted;
  }

  /** Number of return-to-start feasibility Dijkstras performed. */
  public int getReturnChecksPerformed() {
    return returnChecksPerformed;
  }

  void setReturnChecksPerformed(int returnChecksPerformed) {
    this.returnChecksPerformed = returnChecksPerformed;
  }

  /** Wall-clock duration of the planning attempt, milliseconds. */
  public long getRuntimeMillis() {
    return runtimeMillis;
  }

  void setRuntimeMillis(long runtimeMillis) {
    this.runtimeMillis = runtimeMillis;
  }

  /** Number of iso-derived candidates the planner Dijkstra-routed. */
  public int getRoutedIsoCandidates() { return routedIsoCandidates; }
  void setRoutedIsoCandidates(int v) { this.routedIsoCandidates = v; }

  /** Number of non-iso (graph-native) candidates the planner Dijkstra-routed. */
  public int getRoutedNonIsoCandidates() { return routedNonIsoCandidates; }
  void setRoutedNonIsoCandidates(int v) { this.routedNonIsoCandidates = v; }

  /** Number of iso-derived candidates that became legs in the final loop. */
  public int getAcceptedIsoLegs() { return acceptedIsoLegs; }
  void setAcceptedIsoLegs(int v) { this.acceptedIsoLegs = v; }

  /** Number of non-iso candidates that became legs in the final loop. */
  public int getAcceptedNonIsoLegs() { return acceptedNonIsoLegs; }
  void setAcceptedNonIsoLegs(int v) { this.acceptedNonIsoLegs = v; }

  /** Accepted legs whose candidate held its routed slot only via source-quota
   *  injection. High = the iso pool kept outranking local alternatives that then
   *  won on routed truth — the plain-GREEDY-win signature the health tracker
   *  demotes on. */
  public int getAcceptedQuotaInjectedLegs() { return acceptedQuotaInjectedLegs; }
  void setAcceptedQuotaInjectedLegs(int v) { this.acceptedQuotaInjectedLegs = v; }

  /** First step at which iso-pool influence was reduced ({@code IsoPoolHealth}
   *  DEGRADED or worse); {@code -1} = never demoted (or no iso pool). */
  public int getPoolDemotedAtStep() { return poolDemotedAtStep; }
  void setPoolDemotedAtStep(int v) { this.poolDemotedAtStep = v; }

  /** Final iso-pool health score in [0,1]; {@code NaN} when the plan ran
   *  without an iso pool (plain GREEDY / graph-native-only provider). */
  public double getIsoPoolHealthScore() { return isoPoolHealthScore; }
  void setIsoPoolHealthScore(double v) { this.isoPoolHealthScore = v; }

  /** True when ISO_GREEDY already compared an internal graph-native-only branch,
   *  so AUTO can skip a duplicate plain-GREEDY child for this request. */
  public boolean isInternalGraphNativeCompared() { return internalGraphNativeCompared; }
  void setInternalGraphNativeCompared(boolean v) { this.internalGraphNativeCompared = v; }

  /** The engine's explicit start-policy decision: this plan ran on graph-native
   *  candidates only (iso pool unadmitted or statically unhealthy). AUTO's
   *  plain-GREEDY absorption reads THIS, not inferred telemetry sentinels. */
  boolean isGraphNativeOnlyStart() { return graphNativeOnlyStart; }
  void setGraphNativeOnlyStart(boolean v) { this.graphNativeOnlyStart = v; }

  /** Whether the Phase 2.0 iso-asymmetry bearing bias fired. False for non-ISO_GREEDY,
   *  an explicit user direction, or no bucket meeting the frontier-quality
   *  thresholds (airDist &gt;= 0.6 * searchRadius AND hits &gt;= 3). */
  boolean isIsoAsymmetryBearingApplied() { return isoAsymmetryBearingApplied; }
  void setIsoAsymmetryBearingApplied(boolean v) { this.isoAsymmetryBearingApplied = v; }

  /** Bearing (degrees) the iso-asymmetry bias selected as the most-reaching
   *  sector; {@code NaN} when not applied. */
  double getIsoAsymmetryBearingDegrees() { return isoAsymmetryBearingDegrees; }
  void setIsoAsymmetryBearingDegrees(double v) { this.isoAsymmetryBearingDegrees = v; }

  /** {@code cost / airDist} of the bucket that won the bias; {@code NaN}
   *  when not applied. Lower = more direct reach. */
  double getIsoAsymmetryBestBucketIndirectness() { return isoAsymmetryBestBucketIndirectness; }
  void setIsoAsymmetryBestBucketIndirectness(double v) { this.isoAsymmetryBestBucketIndirectness = v; }

  /** Hit count of the bucket that won the bias; {@code -1} when not applied. */
  int getIsoAsymmetryBestBucketHits() { return isoAsymmetryBestBucketHits; }
  void setIsoAsymmetryBestBucketHits(int v) { this.isoAsymmetryBestBucketHits = v; }

  /** Air distance (meters) at the frontier of the winning bucket;
   *  {@code -1} when not applied. */
  int getIsoAsymmetryBestBucketAirDistMeters() { return isoAsymmetryBestBucketAirDistMeters; }
  void setIsoAsymmetryBestBucketAirDistMeters(int v) { this.isoAsymmetryBestBucketAirDistMeters = v; }

  /** Whether the Phase 2.1 axis-retry path fired: the first attempt (user
   *  direction) degraded AND the frontier showed a strong terrain axis
   *  perpendicular to that direction. */
  boolean isPhase21AxisRetryTriggered() { return phase21AxisRetryTriggered; }
  void setPhase21AxisRetryTriggered(boolean v) { this.phase21AxisRetryTriggered = v; }

  /** Whether the Phase 2.1 axis retry produced a non-degraded loop. False when
   *  retry did not trigger, or retry also degraded (geographic infeasibility). */
  boolean isPhase21AxisRetrySucceeded() { return phase21AxisRetrySucceeded; }
  void setPhase21AxisRetrySucceeded(boolean v) { this.phase21AxisRetrySucceeded = v; }

  /** Bearing of the principal frontier axis (in [0, 180); axis is
   *  bidirectional). {@code NaN} when 2.1 did not trigger. */
  double getPhase21AxisBearingDegrees() { return phase21AxisBearingDegrees; }
  void setPhase21AxisBearingDegrees(double v) { this.phase21AxisBearingDegrees = v; }

  /** Eigenvalue ratio of the displacement covariance; higher = more
   *  elongated reachable region. {@code 0.0} when 2.1 did not trigger. */
  double getPhase21AxisStrength() { return phase21AxisStrength; }
  void setPhase21AxisStrength(double v) { this.phase21AxisStrength = v; }

  /** Direction used on the axis-retry attempt (axis-aligned bearing
   *  closest to the user's original direction). {@code NaN} when 2.1
   *  did not trigger. */
  double getPhase21RetryDirectionDegrees() { return phase21RetryDirectionDegrees; }
  void setPhase21RetryDirectionDegrees(double v) { this.phase21RetryDirectionDegrees = v; }
}
