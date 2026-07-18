package btools.router.roundtrip;

import java.util.Locale;

/**
 * Round-trip loop generation modes, forming a speed/quality ladder. Four are
 * user-facing (see the per-constant docs for tuning rationale):
 * <ul>
 *   <li><b>FAST</b> ({@link #WAYPOINT}) — geometric placement, no routed legs:
 *       ~10x faster (sub-second) at lower quality.</li>
 *   <li>{@link #BALANCED} — one budgeted {@link #ISO_GREEDY} run (~8s, reduced
 *       routed top-K, no retry/competition); the interactive/mobile default.</li>
 *   <li>{@link #AUTO} (default) — competes {@link #GREEDY} and {@link #ISO_GREEDY}
 *       and keeps the best, effort resolved from request context.</li>
 *   <li>{@link #QUALITY} — the full competition at max effort (wider top-K,
 *       doubled budget).</li>
 * </ul>
 *
 * <p>The internal enum names are also accepted for explicit forcing (e.g. tests
 * pinning one planner). {@link #ISOCHRONE} places waypoints directly from the
 * frontier (no routed legs); from AUTO it is reached only as the competition's
 * last resort, or by explicit request.
 */
public enum RoundTripAlgorithm {
  /** Pick a mode based on terrain, length, and request parameters. */
  AUTO,
  /** Bounded quality tier: one budgeted ISO_GREEDY run, reduced routed top-K,
   *  WAYPOINT fallback — predictable latency for interactive/mobile use. */
  BALANCED,
  /** Max-effort tier: the full AUTO competition with both planners always
   *  running, a wider routed top-K (4/6) and a doubled plan budget — "best
   *  loop, take your time". Deliberately NOT an ISO_GREEDY alias: greedy wins
   *  ~a quarter of the competition cells, so forcing one planner would ship
   *  worse loops than AUTO at the same cost. */
  QUALITY,
  /** FAST mode: geometric probe/waypoint placement (sub-second preview, lower quality). */
  WAYPOINT,
  /** Direct isochrone-frontier waypoint placement (explicitly requested, or
   *  the AUTO competition's last resort). */
  ISOCHRONE,
  /** Iterative routed-leg planner, radial candidate provider — an AUTO competitor. */
  GREEDY,
  /** Iterative routed-leg planner, isochrone-derived candidates — an AUTO competitor. */
  ISO_GREEDY;

  /**
   * Parse the algorithm name (case-insensitive). Accepts every enum name plus
   * the alias {@code FAST} → {@code WAYPOINT}; unknown or null input falls back
   * to {@link #AUTO}.
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

  /**
   * The algorithm a request gets when it names none: the
   * {@code roundtrip.default.algorithm} system property (any name
   * {@link #fromString} accepts), {@code FAST} otherwise. FAST is the shipped
   * default because it keeps the latency envelope of the historic round-trip
   * routine — no existing caller gets slower; the quality tiers are an opt-in
   * (per request via {@code roundTripAlgorithm}, or per deployment via the
   * property). Read per call so embedders and tests can change it at runtime;
   * an explicit request parameter always wins.
   */
  public static RoundTripAlgorithm defaultAlgorithm() {
    return fromString(System.getProperty("roundtrip.default.algorithm", "FAST"));
  }
}
