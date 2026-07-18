package btools.router.roundtrip;

import btools.router.OsmTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * QUALITY-tier candidate provider blending an {@link IsochroneCandidateProvider}
 * with per-step graph-native candidates.
 *
 * <p>Pure ISO_GREEDY regressed in rural/coastal cells where the bounded
 * isochrone's road pool is sparse or pulled toward one corridor. Blending fixes
 * that: start-centered iso candidates win where useful, and the graph-native
 * provider adds candidates from a fresh Dijkstra around the current step. Iso
 * results are concatenated first, so the planner's score-sort favours road-native
 * picks at the same heuristic tier.
 */
public final class BlendedCandidateProvider implements RoundTripCandidateProvider {

  private final IsochroneCandidateProvider iso;
  private final RoundTripCandidateProvider graphNative;

  public BlendedCandidateProvider(IsochroneCandidateProvider iso,
                           RoundTripCandidateProvider graphNative) {
    this.iso = iso;
    this.graphNative = graphNative;
  }

  /** The start-centered iso half of the blend ({@link IsoPoolHealth} shape source). */
  public IsochroneCandidateProvider isoProvider() {
    return iso;
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
