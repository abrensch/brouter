package btools.router;

/**
 * Semantic shape of a generated round-trip route.
 *
 * <p>Classifies the visible topology of a routed loop into one of four
 * categories that drive acceptance and disclosure decisions. Distinguishing
 * these is what lets us accept a beautiful scenic spur to a mountain pass
 * while still rejecting an accidental U-turn in the middle of an otherwise
 * fine loop.
 *
 * <ul>
 *   <li>{@link #STRICT_LOOP} — a normal loop. Little or no retracing.
 *       Cyclist sees no part of the route twice (or only an unavoidable
 *       short shared stem near start/end).</li>
 *   <li>{@link #LOLLIPOP} — a loop with a retraced stem ("stick") leading
 *       to a hub from which the loop runs. The stem is a meaningful chunk
 *       of the route but the unique loop body is the majority of the
 *       distance.</li>
 *   <li>{@link #OUT_AND_BACK} — the route is essentially an
 *       out-and-back along the same road to an extremity (mountain pass,
 *       cape, dead-end valley). The "loop body" is empty or trivial; the
 *       cyclist returns the same way. Accepted only when the caller
 *       explicitly allowed same-way-back, never silently dressed up as
 *       a loop.</li>
 *   <li>{@link #INVALID_RETRACE} — the route contains a long contiguous
 *       retrace that is neither a small unavoidable shared stem nor a
 *       terminal spur to a clear extremity. This is the accidental
 *       backtracking pattern: the planner reused a road in the middle of
 *       the route without a forced/scenic reason. Hard reject.</li>
 * </ul>
 *
 * <p>Shape classification is the semantic step that sits between raw
 * geometry (closure, distance ratio, beelines) and the final accept/reject
 * decision. A LOLLIPOP and a OUT_AND_BACK can both be high-quality
 * routes for a cyclist — but they MUST be disclosed as such and only
 * returned in policies that allow them.
 */
public enum RouteShape {
  /** Normal loop — little or no retraced distance. */
  STRICT_LOOP,
  /** Loop with a retraced terminal spur ("stick" of the lollipop). */
  LOLLIPOP,
  /** Out-and-back along the same road to an extremity. */
  OUT_AND_BACK,
  /** Accidental mid-route backtracking that should be rejected. */
  INVALID_RETRACE
}
