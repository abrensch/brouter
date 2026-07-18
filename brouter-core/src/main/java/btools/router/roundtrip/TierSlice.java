package btools.router.roundtrip;

/**
 * Parameters of one tier-ladder attempt, fixed at ladder resolution. The
 * slice is a superset: each strategy reads only the fields it needs (e.g.
 * only the bounded tier reads {@link #label} and {@link #greedyCapable}, and
 * the greedy tier takes its effort knobs from the request state, not from
 * {@link #effortPolicy}).
 */
final class TierSlice {

  final RoundTripAlgorithm algo;

  /** Effort preset for this attempt; null when the tier ignores it. */
  final RoundTripEffortPolicy effortPolicy;

  /** Loop search radius in meters (target loop length / 2π). */
  final double searchRadius;

  /** Start bearing in degrees, [0, 360). */
  final double direction;

  /** Whether the greedy planner supports this request (samewayback, vias). */
  final boolean greedyCapable;

  /** Tier label for logs and diagnostics (e.g. "BALANCED", "AUTO(bounded)"). */
  final String label;

  TierSlice(RoundTripAlgorithm algo, RoundTripEffortPolicy effortPolicy,
            double searchRadius, double direction, boolean greedyCapable, String label) {
    this.algo = algo;
    this.effortPolicy = effortPolicy;
    this.searchRadius = searchRadius;
    this.direction = direction;
    this.greedyCapable = greedyCapable;
    this.label = label;
  }
}
