package btools.router;

/**
 * Result of {@link RoutingEngine}'s Phase 2.0 iso-asymmetry bearing draw for
 * round trips: which bucket of the isochrone frontier the bias selected when
 * the request carried no explicit direction, plus the metadata surfaced as
 * telemetry on the round-trip result.
 */
final class IsoAsymmetryBias {
  static final IsoAsymmetryBias NONE =
    new IsoAsymmetryBias(false, Double.NaN, Double.NaN, -1, -1);
  final boolean applied;
  final double bearingDegrees;
  final double indirectness;
  final int hits;
  final int airDistMeters;

  IsoAsymmetryBias(boolean applied, double bearingDegrees, double indirectness,
                   int hits, int airDistMeters) {
    this.applied = applied;
    this.bearingDegrees = bearingDegrees;
    this.indirectness = indirectness;
    this.hits = hits;
    this.airDistMeters = airDistMeters;
  }
}
