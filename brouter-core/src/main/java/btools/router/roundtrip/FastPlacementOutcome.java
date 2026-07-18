package btools.router.roundtrip;

import btools.router.OsmNodeNamed;

import java.util.Collections;
import java.util.List;

/**
 * Result of the optimized FAST waypoint placement: a complete waypoint skeleton
 * plus which placement path produced it. The caller swaps in {@link #skeleton}
 * in one step (build-then-commit), so a failed placement never leaves partial
 * waypoints behind.
 */
public final class FastPlacementOutcome {

  /**
   * Full skeleton: start, intermediate vias, closing start. Fully validated on
   * every path (optimized paths commit only road-snapped, deduped,
   * reachability-checked vias; the circle fallback runs the shared matching pass
   * behind {@link FastPlacementOps#circleFallbackValidated}). Routed as-is.
   */
  public final List<OsmNodeNamed> skeleton;
  final Path path;
  /** Reason for a degraded path (e.g. the circle fallback), or {@code null}. */
  final String diagnostic;

  public FastPlacementOutcome(List<OsmNodeNamed> skeleton, Path path, String diagnostic) {
    this.skeleton = Collections.unmodifiableList(skeleton);
    this.path = path;
    this.diagnostic = diagnostic;
  }

  /** True for the optimized (probe-snapped) paths — the {@code ENVELOPE_FAST} counter side. */
  public boolean optimizedPlacement() {
    return path != Path.CIRCLE_FALLBACK;
  }

  /**
   * Which placement path produced the skeleton. The first three map to the
   * {@code ENVELOPE_FAST} telemetry counter, {@code CIRCLE_FALLBACK} to
   * {@code CIRCLE}; that 2-value counter is a stable corpus contract.
   */
  enum Path {RING, DIRECTIONAL_LOBE, RING_FALLBACK, CIRCLE_FALLBACK}
}
