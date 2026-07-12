package btools.router;

/**
 * Result of {@link RoutingEngine}'s Phase 2.1 frontier-axis analysis for
 * round trips: the principal axis of the reachable-frontier displacements,
 * with eigenvalue-ratio strength. When a user-requested direction is
 * perpendicular to a strong terrain axis (the Inn-Valley pattern), the
 * engine retries the loop along the axis instead.
 */
final class FrontierAxis {
  static final FrontierAxis NONE = new FrontierAxis(false, Double.NaN, 0.0);
  final boolean hasStrongAxis;
  /** Axis bearing in [0, 180) — axis is direction-agnostic. */
  final double axisBearingDegrees;
  /** Eigenvalue ratio λ1 / λ2 of the displacement covariance. Strong axis
   *  iff this is at least {@link RoutingEngine#PHASE_2_1_STRONG_AXIS_RATIO}. */
  final double strength;

  FrontierAxis(boolean hasStrongAxis, double axisBearingDegrees, double strength) {
    this.hasStrongAxis = hasStrongAxis;
    this.axisBearingDegrees = axisBearingDegrees;
    this.strength = strength;
  }
}
