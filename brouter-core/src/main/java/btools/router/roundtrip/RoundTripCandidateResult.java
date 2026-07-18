package btools.router.roundtrip;

import btools.router.OsmTrack;

/**
 * Internal candidate-comparison record for AUTO mode round-trip routing. Each
 * attempt in AUTO's algorithm sequence produces one of these; the comparison
 * loop in {@link AutoCompetitionStrategy} picks the accepted candidate with
 * the highest {@link RouteChoiceScore.Verdict#score()}.
 *
 * <p>Notable fields: {@link #gateVerdict} is the {@link RoundTripQualityGate}
 * safety verdict — a candidate with {@code isAccepted() == false} is ineligible
 * whatever its score; {@link #planner} is the child's full
 * {@link RoundTripResult}, through which all winner-attribution telemetry reads.
 *
 * <p>Internal-only; package-private so the compiler enforces that this never
 * reaches the public API (confirmed unused outside {@code btools.router.roundtrip}).
 */
final class RoundTripCandidateResult {
  public final RoundTripAlgorithm algorithm;
  public OsmTrack track;
  public RoundTripQualityResult gateVerdict;
  public RouteChoiceScore.Verdict score;
  public long runtimeMillis;
  /**
   * The child planner's result, or {@code null} when the candidate never
   * produced one (legacy WAYPOINT/ISOCHRONE paths, engine error). The
   * attribution accessors below read THROUGH this reference; a field-by-field
   * mirror invited copy omissions whose sentinel defaults silently flipped
   * AUTO's plain-GREEDY absorption decision.
   */
  public RoundTripResult planner;
  public String errorMessage;

  public RoundTripCandidateResult(RoundTripAlgorithm algorithm) {
    this.algorithm = algorithm;
  }

  public boolean accepted() {
    return errorMessage == null && track != null
      && gateVerdict != null && gateVerdict.isAccepted();
  }

  public double scoreValue() {
    return (score == null) ? 0.0 : score.score();
  }

  int routedIsoCandidates() {
    return planner == null ? 0 : planner.getRoutedIsoCandidates();
  }

  int routedNonIsoCandidates() {
    return planner == null ? 0 : planner.getRoutedNonIsoCandidates();
  }

  int acceptedQuotaInjectedLegs() {
    return planner == null ? 0 : planner.getAcceptedQuotaInjectedLegs();
  }

  /** Final iso-pool health score; {@code NaN} = no iso pool. */
  double isoPoolHealthScore() {
    return planner == null ? Double.NaN : planner.getIsoPoolHealthScore();
  }

  /** Step at which the pool's influence was reduced; {@code -1} = never. */
  int poolDemotedAtStep() {
    return planner == null ? -1 : planner.getPoolDemotedAtStep();
  }

  public boolean internalGraphNativeCompared() {
    return planner != null && planner.isInternalGraphNativeCompared();
  }

  /** The child's explicit graph-native-only start decision. */
  public boolean graphNativeOnlyStart() {
    return planner != null && planner.isGraphNativeOnlyStart();
  }

  /**
   * Keep-when-forced marker from the child planner
   * ({@link RoundTripResult#isForcedCorridorAccepted()}): a rideable same-way-back
   * corridor with no clean alternative. The parent's re-gate must honor it, else
   * AUTO rejects a route the child accepted by design.
   */
  public boolean forcedCorridorAccepted() {
    return planner != null && planner.isForcedCorridorAccepted();
  }

  @Override
  public String toString() {
    if (errorMessage != null) {
      return algorithm + ": error \"" + errorMessage + "\"";
    }
    if (track == null) {
      return algorithm + ": no track";
    }
    return String.format(java.util.Locale.US,
      "%s: accepted=%s, track=%dm, gateShape=%s, score=%.3f, runtime=%dms, isoRouted=%d, nonIsoRouted=%d",
      algorithm, accepted(), track.distance,
      gateVerdict == null ? "?" : gateVerdict.getShape(),
      scoreValue(), runtimeMillis, routedIsoCandidates(), routedNonIsoCandidates())
      // Winner-attribution suffix, only for candidates that ran an iso pool —
      // this is what the "plain GREEDY fallback wins are gone" acceptance
      // check greps from AUTO competition logs.
      + (Double.isNaN(isoPoolHealthScore()) ? "" : String.format(java.util.Locale.US,
        ", quotaAccepted=%d, poolHealth=%.2f, demotedAtStep=%d",
        acceptedQuotaInjectedLegs(), isoPoolHealthScore(), poolDemotedAtStep()))
      + (internalGraphNativeCompared() ? ", internalGraphNativeCompared=true" : "");
  }
}
