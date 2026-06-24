package btools.router;

import java.util.Locale;

/**
 * Round-trip loop generation modes.
 *
 * <p>Two user-facing modes are recommended:
 * <ul>
 *   <li><b>{@link #AUTO}</b> (default) — runs the iterative planners and keeps
 *       the best loop. {@link #GREEDY} and {@link #ISO_GREEDY} are the two
 *       candidate-placement strategies it competes; measured across the test
 *       matrix they cost the same (median ~3s) and score almost identically
 *       (mean composite 0.812 vs 0.817), so AUTO simply adopts the better one
 *       per request rather than exposing them as separate speed/quality tiers.</li>
 *   <li><b>{@code FAST}</b> → {@link #WAYPOINT} — geometric waypoint placement
 *       with no routed-leg evaluation: ~10x faster (sub-second) at lower quality,
 *       useful as a quick preview on limited hardware.</li>
 * </ul>
 *
 * <p>The internal enum names ({@code WAYPOINT}, {@code GREEDY}, {@code ISO_GREEDY},
 * {@code ISOCHRONE}, {@code AUTO}) are also accepted for explicit/advanced forcing,
 * e.g. tests that pin a single planner. {@link #ISOCHRONE} places waypoints
 * directly from the isochrone frontier (no routed legs); from {@link #AUTO} it is
 * reached only via {@code roundTripIsochrone=1} or as the competition's last
 * resort (after ISO_GREEDY, GREEDY, WAYPOINT all fail), and is otherwise a
 * debug/comparison surface.
 */
public enum RoundTripAlgorithm {
  /** Pick a mode based on terrain, length, and request parameters. */
  AUTO,
  /** FAST mode: geometric probe/waypoint placement (sub-second preview, lower quality). */
  WAYPOINT,
  /** Direct isochrone-frontier waypoint placement (from AUTO only via the
   *  {@code roundTripIsochrone=1} shortcut or as the competition's last resort). */
  ISOCHRONE,
  /** Iterative routed-leg planner, radial candidate provider — an AUTO competitor. */
  GREEDY,
  /** Iterative routed-leg planner, isochrone-derived candidates — an AUTO competitor. */
  ISO_GREEDY;

  /**
   * Parse the algorithm name. Accepts the internal enum names ({@code WAYPOINT},
   * {@code GREEDY}, {@code ISO_GREEDY}, {@code ISOCHRONE}, {@code AUTO}) plus the
   * one user-facing alias {@code FAST} → {@code WAYPOINT} (quick-preview mode).
   * Case-insensitive. Unknown input falls back to {@link #AUTO} — which is the
   * recommended choice for a best-quality loop. (The former {@code BALANCED} /
   * {@code QUALITY} aliases were dropped: GREEDY and ISO_GREEDY are same-cost,
   * same-quality AUTO competitors, not distinct speed/quality tiers.)
   */
  public static RoundTripAlgorithm fromString(String s) {
    if (s == null) return AUTO;
    String upper = s.toUpperCase(Locale.ROOT);
    if ("FAST".equals(upper)) {
      return WAYPOINT;   // quick-preview alias
    }
    try {
      return valueOf(upper);
    } catch (IllegalArgumentException e) {
      return AUTO;
    }
  }
}
