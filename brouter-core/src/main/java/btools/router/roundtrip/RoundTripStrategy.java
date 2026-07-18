package btools.router.roundtrip;

/**
 * One rung of the round-trip tier ladder (FAST &lt; BALANCED &lt; AUTO &lt;
 * QUALITY). This is the sanctioned flexibility point of the subsystem (see
 * {@code .agents/adr-no-shared-planner-interface.md}): tiers are
 * interchangeable here; the planners below them are deliberately not.
 */
interface RoundTripStrategy {

  /**
   * Run one tier attempt under the given slice, leaving the outcome on the
   * request (track XOR error). EVERY strategy's outcome then flows through the
   * orchestrator's single shared finalization — floors, uniform quality gate,
   * lenient/strict policy, and advisory decoration. (Historically the AUTO
   * competition self-finalized, signalled first by a boolean and then a typed
   * Outcome enum; its children now run undecorated and it stashes the winner's
   * verdict for the shared gate instead, so the split lifecycle — and the
   * enum — are gone.)
   */
  void attempt(RoundTripRequest request, TierSlice slice);
}
