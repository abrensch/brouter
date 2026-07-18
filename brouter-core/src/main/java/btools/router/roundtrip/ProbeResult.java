package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;

import java.util.Collections;
import java.util.List;

/**
 * Result of {@code RoutingEngine#probeReachableDirections}: the bare
 * {@code double[]} of viable bearings (legacy contract for downstream placement)
 * and the per-direction scored records (FAST tier, to drop weak one-shot
 * directions). Returning both as one value keeps callers off a stale
 * side-channel field.
 */
public final class ProbeResult {

  /** Compass bearings where at least one of the three probe distances snapped. */
  public final double[] viableDirections;

  /** Per-direction scoring records, one per element of {@link #viableDirections}. */
  final List<ProbeDirection> scored;

  /** The start point's road match (for reachability filtering), or {@code null}. */
  final MatchedWaypoint startMatch;

  public ProbeResult(double[] viableDirections, List<ProbeDirection> scored) {
    this(viableDirections, scored, null);
  }

  public ProbeResult(double[] viableDirections, List<ProbeDirection> scored, MatchedWaypoint startMatch) {
    this.viableDirections = viableDirections;
    this.scored = (scored != null) ? scored : Collections.emptyList();
    this.startMatch = startMatch;
  }
}
