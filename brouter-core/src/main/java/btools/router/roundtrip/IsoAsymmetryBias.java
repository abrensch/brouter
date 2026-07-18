package btools.router.roundtrip;

import btools.router.RoutingEngine;

/**
 * Result of {@link RoutingEngine}'s Phase 2.0 iso-asymmetry bearing draw: which
 * isochrone-frontier bucket the bias selected when the request carried no
 * explicit direction, plus the metadata surfaced as round-trip telemetry.
 */
public final class IsoAsymmetryBias {
  public static final IsoAsymmetryBias NONE =
    new IsoAsymmetryBias(false, Double.NaN, Double.NaN, -1, -1);
  public final boolean applied;
  public final double bearingDegrees;
  public final double indirectness;
  public final int hits;
  public final int airDistMeters;

  public IsoAsymmetryBias(boolean applied, double bearingDegrees, double indirectness,
                   int hits, int airDistMeters) {
    this.applied = applied;
    this.bearingDegrees = bearingDegrees;
    this.indirectness = indirectness;
    this.hits = hits;
    this.airDistMeters = airDistMeters;
  }
}
