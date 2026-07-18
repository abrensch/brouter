package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;

/**
 * Per-direction reachability summary from {@code RoutingEngine#probeReachableDirections}.
 * Each compass direction is tested at {0.7R, 1.0R, 1.3R}; the FAST tier uses
 * {@link #successfulProbeCount} (via
 * {@link PlacementGeometry#filterByProbeConfidence}) to drop one-shot directions
 * when strong alternatives exist, avoiding fragile sea/dead-end picks. Optimized
 * FAST placement reuses {@link #bestMatch} directly as a via.
 */
public final class ProbeDirection {

  /** Compass bearing of this probe in {@code [0, 360)}. */
  final double direction;
  /** Number of probe distances where a road snapped within tolerance (1–3). */
  public final int successfulProbeCount;
  /** Best (closest) road match for this direction, or {@code null} if not retained. */
  public final MatchedWaypoint bestMatch;

  public ProbeDirection(double direction, int successfulProbeCount, MatchedWaypoint bestMatch) {
    this.direction = direction;
    this.successfulProbeCount = successfulProbeCount;
    this.bestMatch = bestMatch;
  }
}
