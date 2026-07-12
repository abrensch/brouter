package btools.router;

/**
 * Per-direction reachability summary produced by {@link RoutingEngine#probeReachableDirections}.
 *
 * <p>Each compass direction is tested at distances {0.7R, 1.0R, 1.3R}. This record
 * carries the direction and how many of those probes snapped to a road. The FAST
 * tier uses {@link #successfulProbeCount} (via
 * {@link RoutingEngine#filterByProbeConfidence}) to drop one-shot directions when
 * enough strong alternatives exist, so the chosen waypoint ring avoids fragile
 * sea/dead-end picks.
 */
final class ProbeDirection {

  /** Compass bearing of this probe in {@code [0, 360)}. */
  final double direction;
  /** Number of probe distances where a road snapped within tolerance (1–3). */
  final int successfulProbeCount;

  ProbeDirection(double direction, int successfulProbeCount) {
    this.direction = direction;
    this.successfulProbeCount = successfulProbeCount;
  }
}
