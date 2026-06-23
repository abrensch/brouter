package btools.router;

import java.util.Collections;
import java.util.List;

/**
 * Result of {@link RoutingEngine#probeReachableDirections}.
 *
 * <p>Holds both the bare {@code double[]} of viable bearings (the legacy contract used by
 * downstream placement code) and the per-direction scored records (used by the FAST tier
 * to drop weak one-shot directions when alternatives exist). Returning both as one value
 * keeps callers from reading stale data via a side-channel field.
 */
final class ProbeResult {

  /** Compass bearings where at least one of the three probe distances snapped. */
  final double[] viableDirections;

  /** Per-direction scoring records, one per element of {@link #viableDirections}. */
  final List<ProbeDirection> scored;

  ProbeResult(double[] viableDirections, List<ProbeDirection> scored) {
    this.viableDirections = viableDirections;
    this.scored = (scored != null) ? scored : Collections.emptyList();
  }
}
