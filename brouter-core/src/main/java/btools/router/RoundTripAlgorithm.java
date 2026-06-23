package btools.router;

import java.util.Locale;

/**
 * Round-trip loop generation modes.
 *
 * <p>User-facing tiers (mapped from these internal values):
 * <ul>
 *   <li><b>FAST</b> → {@link #WAYPOINT}: cheap probe/waypoint strategy, mobile-friendly.</li>
 *   <li><b>BALANCED</b> → {@link #GREEDY}: iterative routed-leg planner (the dependable default).</li>
 *   <li><b>QUALITY</b> → {@link #ISO_GREEDY}: greedy planner with road-native candidates
 *       extracted from a bounded isochrone expansion. Higher quality, more compute.</li>
 * </ul>
 *
 * <p>{@link #ISOCHRONE} places waypoints directly from the isochrone frontier
 * (without routing real candidate legs the way the QUALITY tier does). It is
 * <em>not</em> selected by loop size: from {@link #AUTO} it is reached only via
 * the explicit {@code roundTripIsochrone=1} shortcut, or as the last-resort
 * candidate in the AUTO competition (tried only after ISO_GREEDY, GREEDY, and
 * WAYPOINT have all failed — see
 * {@code RoutingEngine#runAutoCandidateCompetition}). Otherwise it is an explicit
 * choice and primarily a debug/comparison surface.
 */
public enum RoundTripAlgorithm {
  /** Pick a mode based on terrain, length, and request parameters. */
  AUTO,
  /** FAST tier: probe-based waypoint placement. */
  WAYPOINT,
  /** Direct isochrone-frontier waypoint placement (from AUTO only via the
   *  {@code roundTripIsochrone=1} shortcut or as the competition's last resort). */
  ISOCHRONE,
  /** BALANCED tier: iterative routed-leg planner with radial candidate provider. */
  GREEDY,
  /** QUALITY tier: greedy planner with isochrone-derived candidate pool. */
  ISO_GREEDY;

  /**
   * Parse the user-facing algorithm name. Accepts both the internal enum names
   * ({@code WAYPOINT}, {@code GREEDY}, {@code ISO_GREEDY}, {@code ISOCHRONE},
   * {@code AUTO}) and the user-facing tier aliases ({@code FAST} →
   * {@code WAYPOINT}, {@code BALANCED} → {@code GREEDY}, {@code QUALITY} →
   * {@code ISO_GREEDY}). Case-insensitive. Unknown input falls back to
   * {@link #AUTO}.
   */
  public static RoundTripAlgorithm fromString(String s) {
    if (s == null) return AUTO;
    String upper = s.toUpperCase(Locale.ROOT);
    switch (upper) {
      case "FAST":     return WAYPOINT;
      case "BALANCED": return GREEDY;
      case "QUALITY":  return ISO_GREEDY;
      default:
        try {
          return valueOf(upper);
        } catch (IllegalArgumentException e) {
          return AUTO;
        }
    }
  }
}
