package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * QUALITY-tier candidate provider that blends an {@link IsochroneCandidateProvider}
 * with per-step graph-native candidates.
 *
 * <p>Pure ISO_GREEDY (iso candidates only) regressed in rural/coastal cells where
 * the bounded isochrone's road-native pool happens to be sparse or pulled toward
 * one corridor. Blending fixes that without returning to geometric waypoint
 * placement: start-centered iso candidates win where they are useful, and the
 * graph-native provider adds candidates from a fresh Dijkstra expansion around
 * the current step position.
 *
 * <p>For each step we ask both providers, then concatenate iso results first
 * (so the planner's score-sort favours road-native picks at the same heuristic
 * tier) followed by per-step graph-native picks.
 */
final class BlendedCandidateProvider implements RoundTripCandidateProvider {

  private final IsochroneCandidateProvider iso;
  private final RoundTripCandidateProvider graphNative;

  BlendedCandidateProvider(IsochroneCandidateProvider iso,
                           RoundTripCandidateProvider graphNative) {
    this.iso = iso;
    this.graphNative = graphNative;
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack) {
    List<CandidatePoint> isoPicks = iso.candidatesForStep(
      fromIlon, fromIlat, airRadius, step, totalSteps,
      startIlon, startIlat, startDirection, refTrack);
    List<CandidatePoint> graphPicks = graphNative.candidatesForStep(
      fromIlon, fromIlat, airRadius, step, totalSteps,
      startIlon, startIlat, startDirection, refTrack);
    List<CandidatePoint> blended = new ArrayList<>(isoPicks.size() + graphPicks.size());
    blended.addAll(isoPicks);
    blended.addAll(graphPicks);
    return blended;
  }
}
