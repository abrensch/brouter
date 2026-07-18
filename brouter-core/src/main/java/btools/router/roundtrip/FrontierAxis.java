package btools.router.roundtrip;

import btools.router.RoutingEngine;

/**
 * Result of {@link RoutingEngine}'s Phase 2.1 frontier-axis analysis: the
 * principal axis of the reachable-frontier displacements with eigenvalue-ratio
 * strength. When a user-requested direction is perpendicular to a strong terrain
 * axis (the Inn-Valley pattern), the engine retries the loop along the axis.
 */
public final class FrontierAxis {
  public static final FrontierAxis NONE = new FrontierAxis(false, Double.NaN, 0.0);
  public final boolean hasStrongAxis;
  /** Axis bearing in [0, 180) — axis is direction-agnostic. */
  public final double axisBearingDegrees;
  /** Eigenvalue ratio λ1 / λ2 of the displacement covariance. Strong axis
   *  iff this is at least {@code RoutingEngine#PHASE_2_1_STRONG_AXIS_RATIO}. */
  public final double strength;

  public FrontierAxis(boolean hasStrongAxis, double axisBearingDegrees, double strength) {
    this.hasStrongAxis = hasStrongAxis;
    this.axisBearingDegrees = axisBearingDegrees;
    this.strength = strength;
  }
}
