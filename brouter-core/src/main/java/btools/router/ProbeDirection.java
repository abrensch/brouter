package btools.router;

import btools.mapaccess.MatchedWaypoint;

/**
 * Per-direction reachability summary produced by {@link RoutingEngine#probeReachableDirections}.
 *
 * <p>Each compass direction is tested at distances {0.7R, 1.0R, 1.3R}. This record
 * carries the direction and how many of those probes snapped to a road. The FAST
 * tier uses {@link #successfulProbeCount} (via
 * {@link RoutingEngine#filterByProbeConfidence}) to drop one-shot directions when
 * enough strong alternatives exist, so the chosen waypoint ring avoids fragile
 * sea/dead-end picks.
 *
 * <p>{@link #bestMatch} carries the closest road match found for this direction
 * (smallest snap distance across the probed radii), or {@code null} for the legacy
 * probe. The optimized FAST placement reuses it directly as a via instead of
 * re-matching a fresh candidate grid — see
 * {@link RoutingEngine#probeReachableDirectionsFast}.
 */
final class ProbeDirection {

  /** Compass bearing of this probe in {@code [0, 360)}. */
  final double direction;
  /** Number of probe distances where a road snapped within tolerance (1–3). */
  final int successfulProbeCount;
  /** Best (closest) road match for this direction, or {@code null} if not retained. */
  final MatchedWaypoint bestMatch;

  ProbeDirection(double direction, int successfulProbeCount, MatchedWaypoint bestMatch) {
    this.direction = direction;
    this.successfulProbeCount = successfulProbeCount;
    this.bestMatch = bestMatch;
  }
}
