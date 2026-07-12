package btools.router;

/**
 * Internal candidate-comparison record for AUTO mode round-trip routing.
 *
 * <p>When AUTO runs its candidate algorithms in sequence (ISO_GREEDY first,
 * then GREEDY if ISO_GREEDY was weak, then the legacy WAYPOINT/probe and
 * ISOCHRONE paths only as last-resort fallbacks), each attempt produces one
 * {@link RoundTripCandidateResult}. The comparison loop in
 * {@code RoutingEngine.runAutoCandidateCompetition} examines all accepted
 * candidates and picks the highest {@link RouteChoiceScore.Verdict#score()}.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@link #algorithm} — which algorithm produced this candidate.</li>
 *   <li>{@link #track} — the actually-routed {@link OsmTrack}, or null if
 *       routing failed.</li>
 *   <li>{@link #gateVerdict} — the {@link RoundTripQualityGate} hard-safety
 *       verdict. A candidate with {@code gateVerdict.isAccepted() == false}
 *       is ineligible regardless of its score.</li>
 *   <li>{@link #score} — the {@link RouteChoiceScore.Verdict} for ranking;
 *       null if the candidate failed before scoring was attempted.</li>
 *   <li>{@link #runtimeMillis} — wall-clock of this candidate's attempt.</li>
 *   <li>{@link #routedIsoCandidates}/{@link #routedNonIsoCandidates} —
 *       telemetry: how many candidates of each provenance the greedy
 *       planner actually routed (not just generated).</li>
 *   <li>{@link #acceptedIsoLegs}/{@link #acceptedNonIsoLegs} — how many of
 *       those routed candidates were accepted into the final loop. "Low
 *       iso usage" should be measured by accepted, not routed.</li>
 *   <li>{@link #errorMessage} — set when the candidate failed (engine error,
 *       gate rejection, planner aborted).</li>
 * </ul>
 *
 * <p>Internal-only; do not expose on the public API.
 */
final class RoundTripCandidateResult {
  final RoundTripAlgorithm algorithm;
  OsmTrack track;
  RoundTripQualityResult gateVerdict;
  RouteChoiceScore.Verdict score;
  long runtimeMillis;
  int routedIsoCandidates;
  int routedNonIsoCandidates;
  int acceptedIsoLegs;
  int acceptedNonIsoLegs;
  String errorMessage;
  /**
   * Keep-when-forced marker from the child planner
   * ({@link RoundTripResult#isForcedCorridorAccepted()}): the loop is a rideable
   * same-way-back corridor and the planner proved no clean alternative exists.
   * The parent's re-gate must honor it (allow same-way-back) exactly like the
   * direct routing path does — otherwise AUTO rejects a route the child engine
   * accepted by design.
   */
  boolean forcedCorridorAccepted;

  RoundTripCandidateResult(RoundTripAlgorithm algorithm) {
    this.algorithm = algorithm;
  }

  boolean accepted() {
    return errorMessage == null && track != null
      && gateVerdict != null && gateVerdict.isAccepted();
  }

  double scoreValue() {
    return (score == null) ? 0.0 : score.score();
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
      scoreValue(), runtimeMillis, routedIsoCandidates, routedNonIsoCandidates);
  }
}
