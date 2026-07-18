package btools.router.roundtrip;

/**
 * Bounded-effort tier: one bounded, graph-aware planning run with predictable
 * latency. Used by the BALANCED tier and by AUTO when the effort policy
 * resolves BOUNDED (constrained resources).
 *
 * <p>A single ISO_GREEDY dispatch (its internal graph-native compare stays
 * available) under a hard {@code min(request budget, tierBudget)} deadline and a
 * reduced routed top-K. The Phase 2.1 axis retry and the ISO_GREEDY→GREEDY
 * recursion are skipped ({@link RoundTripEffortPolicy#skipRetryLayers}); a
 * degraded-but-rideable loop is adopted best-effort for the lenient gate. When
 * the planner produces no track, or one the gate would hard-reject, the tier
 * falls back to a single FAST/WAYPOINT attempt under a fresh tier slice — always
 * returning some loop beats strict adherence to one slice. With
 * {@code greedyCapable == false} (allowSamewayback) only the budgeted fallback
 * runs. The outcome passes through the shared floors and gate in
 * {@code doRoundTrip}; this tier never returns an ungated success.
 */
final class BoundedStrategy implements RoundTripStrategy {

  private final RoundTripOrchestrator orchestrator;
  private final RoundTripEngineOps ops;
  private final RoundTripStrategy greedyStrategy;
  private final RoundTripStrategy fastStrategy;

  BoundedStrategy(RoundTripOrchestrator orchestrator,
                  RoundTripStrategy greedyStrategy, RoundTripStrategy fastStrategy) {
    this.orchestrator = orchestrator;
    this.ops = orchestrator.ops;
    this.greedyStrategy = greedyStrategy;
    this.fastStrategy = fastStrategy;
  }

  @Override
  public void attempt(RoundTripRequest request, TierSlice slice) {
    RoundTripEffortPolicy policy = slice.effortPolicy;
    String tierLabel = slice.label;
    double searchRadius = slice.searchRadius;
    double direction = slice.direction;

    long tierBudgetMs = policy.tierBudgetMs;
    long t0 = System.currentTimeMillis();
    long savedDeadline = request.requestDeadline();
    long plannerMs = 0;
    if (!slice.greedyCapable) {
      // Same constraint as the greedy dispatch: the planner generates its own
      // intermediate points and does not honor allowSamewayback. The waypoint
      // placement below still runs under the tier budget — bypassing the tier
      // would hand this input the full request budget.
      ops.logInfo(tierLabel + ": planner does not support allowSamewayback,"
        + " using waypoint placement under the tier budget");
    } else {
      RoundTripEffortPolicy savedPolicy = request.effortPolicy;
      long effectiveMs = RoundTripOrchestrator.tierSliceMs(tierBudgetMs, savedDeadline, t0);
      request.setRequestDeadline(t0 + effectiveMs);
      request.effortPolicy = policy;
      // The engine-level timers (island check, leg searches) run in THIS engine
      // and consult ops.maxRunningTime() — floor it to the slice too, or a nearly-
      // spent request budget times out the matching before the planner starts.
      // (The competition path achieves the same by flooring each child's doRun
      // budget.) 0 stays 0: an untimed request keeps engine timers off here;
      // the planner slice is still bounded by ops.roundTripRequestDeadline().
      long savedMaxRunningTime = ops.maxRunningTime();
      if (ops.maxRunningTime() > 0) {
        ops.setMaxRunningTime((t0 + effectiveMs) - ops.startTime());
      }
      try {
        greedyStrategy.attempt(request, new TierSlice(RoundTripAlgorithm.ISO_GREEDY, null,
          searchRadius, direction, true, RoundTripAlgorithm.ISO_GREEDY.toString()));
      } finally {
        request.effortPolicy = savedPolicy;
        request.setRequestDeadline(savedDeadline);
        ops.setMaxRunningTime(savedMaxRunningTime);
      }
      plannerMs = System.currentTimeMillis() - t0;
      if (request.track != null) {
        // The bounded planner adopts degraded best-effort snapshots and defers
        // the verdict to the uniform gate in doRoundTrip. Take that verdict
        // now: a track the gate will hard-reject must not suppress the tier's
        // geometric fallback — by the time the shared gate nulls the track,
        // the chance to fall back is gone and the tier returns a hard error
        // instead of the loop it promises.
        // explicitViaMode == false by construction: the bounded tier is only
        // dispatched in generated-loop mode (the explicit-via skeleton
        // branches off before the tier dispatch).
        RoundTripQualityResult verdict = orchestrator.evaluateRoundTripGate(request.track, searchRadius, false);
        if (!verdict.isAccepted() && ops.roundTripQualityHardReject(verdict)) {
          ops.logInfo(tierLabel + ": bounded planner track fails the quality gate ("
            + verdict.getRejectionReason() + "); falling back to waypoint placement");
          orchestrator.setRejectedTrack(request.track);
          orchestrator.setTrack(null);
        } else {
          // The surviving track flows unchanged to the shared gate in
          // doRoundTrip — stash the verdict so that gate consumes it instead
          // of paying a second full-track evaluation (crossing grid, corridor
          // index) on every interactive bounded request. The fallback path
          // leaves this null: its track needs a fresh verdict.
          request.boundedGateVerdict = verdict;
        }
      }
      if (request.track == null) {
        ops.logInfo(tierLabel + ": bounded planner produced no accepted loop in " + plannerMs
          + "ms (budget " + tierBudgetMs + "ms)"
          + (request.error == null ? "" : " — " + request.error)
          + "; falling back to waypoint placement");
      }
    }
    if (request.track == null) {
      orchestrator.setError(null);
      // Fresh tier slice for the fallback (see class javadoc). Worst case is
      // two slices; the request-level watchdog still applies on top. Same
      // minimum-slice floor as above so a spent budget still funds the one
      // cheap geometric attempt.
      long fallbackStart = System.currentTimeMillis();
      long fallbackMs = RoundTripOrchestrator.tierSliceMs(tierBudgetMs, savedDeadline, fallbackStart);
      request.setRequestDeadline(fallbackStart + fallbackMs);
      long savedRoutingBudget = request.routingBudgetMs;
      long savedMaxRunningTime = ops.maxRunningTime();
      // Scope the engine timers to the fallback slice, UNCONDITIONALLY. The
      // placement phase (probing + the islanded-via guard) runs before
      // doRouting re-arms ops.startTime()/ops.maxRunningTime() from the routing budget:
      // under the request-scoped timer a spent budget makes every placement
      // engine call throw instantly — the island guard degrades to
      // keep-every-via and routing then dies on "target island detected" —
      // and an untimed request (all timer fields 0) would run the fallback
      // with no bound at all. Both violate the tier's slice contract.
      request.routingBudgetMs = fallbackMs;
      ops.setMaxRunningTime((fallbackStart + fallbackMs) - ops.startTime());
      try {
        fastStrategy.attempt(request, new TierSlice(RoundTripAlgorithm.WAYPOINT, null,
          searchRadius, direction, false, RoundTripAlgorithm.WAYPOINT.toString()));
      } finally {
        request.setRequestDeadline(savedDeadline);
        request.routingBudgetMs = savedRoutingBudget;
        ops.setMaxRunningTime(savedMaxRunningTime);
      }
      if (request.track != null) {
        // The shipped track came from the waypoint fallback, not the planner —
        // keeping the FAILED planner result would attribute its counters and
        // pool-health telemetry to a loop the planner never produced.
        orchestrator.setPlannerResult(null);
      }
    }
    ops.logInfo(tierLabel + ": finished in " + (System.currentTimeMillis() - t0)
      + "ms (planner " + plannerMs + "ms, budget " + tierBudgetMs + "ms/slice, "
      + (request.track == null ? "no track" : "track " + request.track.distance + "m") + ")");
  }
}
