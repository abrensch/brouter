package btools.router.roundtrip;

/**
 * Semantic shape of a routed round-trip loop — the step between raw geometry
 * (closure, distance ratio, beelines) and the accept/reject decision. It is
 * what lets the gate accept a scenic spur to a mountain pass while rejecting an
 * accidental U-turn in the middle of an otherwise fine loop. LOLLIPOP and
 * OUT_AND_BACK can be high-quality, but MUST be disclosed and only returned
 * where the policy allows them.
 */
public enum RouteShape {
  /** Normal loop — no retracing beyond an unavoidable short shared stem near start/end. */
  STRICT_LOOP,
  /** Loop reached via a retraced stem ("stick"); the unique loop body is most of the distance. */
  LOLLIPOP,
  /** Out-and-back along the same road to an extremity (pass, cape, dead-end); loop body empty.
   *  Accepted only when the caller allowed same-way-back, never dressed up as a loop. */
  OUT_AND_BACK,
  /** Long mid-route retrace that is neither a short shared stem nor a terminal spur —
   *  accidental backtracking. Hard reject. */
  INVALID_RETRACE
}
