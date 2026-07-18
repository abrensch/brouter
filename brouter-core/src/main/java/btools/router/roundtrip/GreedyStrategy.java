package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;

/**
 * Greedy plan-and-route tier (GREEDY / ISO_GREEDY). {@code doGreedyRoundTrip}
 * is a driver over named dispatch phases, mirroring the planner's own split:
 * {@link #prepareCandidateSources} (providers, oracle, pool shape, policies),
 * {@link #runAttemptLadder} (first attempt + Phase 2.1 axis retry),
 * {@link #maybeRunInternalComparison} (the graph-native comparison branch),
 * {@link #stampDispatchTelemetry}, and the outcome pair
 * {@link #adoptPlannedLoop} / {@link #handleNoAcceptableLoop} (bypass or
 * re-route adoption vs. recursion / budget reject / best-effort). Outcome
 * lands on the request and continues to the orchestrator's shared floors and
 * gate; all request state is threaded as the {@code request} parameter.
 */
final class GreedyStrategy implements RoundTripStrategy {

  private final RoundTripOrchestrator orchestrator;
  private final RoundTripEngineOps ops;

  GreedyStrategy(RoundTripOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
    this.ops = orchestrator.ops;
  }

  @Override
  public void attempt(RoundTripRequest request, TierSlice slice) {
    doGreedyRoundTrip(request, slice.searchRadius, slice.direction, slice.algo);
  }

  private void doGreedyRoundTrip(RoundTripRequest request, double searchRadius,
                                 double direction, RoundTripAlgorithm algo) {
    // Initialize nodesCache — needed before the planner can match ops.waypoints() to the graph.
    ops.resetCache(false);
    request.forcedCorridorAccepted = false;
    // Loop scale for the via-relocation bound (profileAwareMatchPoint): must be
    // set BEFORE planner via matching — the doRouting fallthrough below used to
    // set it only late, leaving the bound inert during greedy placement.
    request.setSearchRadius(searchRadius);

    OsmNodeNamed start = ops.waypoints().get(0);
    double desiredDistance = 2 * Math.PI * searchRadius;
    ops.logInfo("greedy round trip: desired distance=" + (int) desiredDistance
      + "m, searchRadius=" + (int) searchRadius + "m, direction=" + (int) direction
      + ", mode=" + algo);

    CandidateSources src = prepareCandidateSources(start, searchRadius, direction, algo,
      desiredDistance);
    LadderOutcome ladder = runAttemptLadder(request, start, searchRadius, desiredDistance,
      direction, src);
    RoundTripResult result = maybeRunInternalComparison(request, algo, src, start,
      searchRadius, desiredDistance, ladder.result);
    stampDispatchTelemetry(result, src, ladder);

    // A real loop needs at least a triangle: start + 2 intermediate ops.waypoints() + closing
    // start (>= 4 entries). A single intermediate is just an out-and-back, so reject it
    // rather than attributing a legacy waypoint/probe fallback route to GREEDY.
    // Reject loops the planner explicitly flagged as failing its quality gates
    // (DEGRADED_FALLBACK_PREFIX) — shipping a 180% overshoot or 60%-reused
    // forced-closure loop as success would silently fool downstream consumers.
    request.forcedCorridorAccepted = result != null && result.isForcedCorridorAccepted();
    boolean degradedFallback = isDegradedGreedyResult(result);
    if (degradedFallback) {
      ops.logInfo("greedy: rejecting degraded fallback (" + result.getFallbackReason()
        + ")");
    }
    if (!degradedFallback
        && result != null && result.getLoopWaypoints() != null
        && result.getLoopWaypoints().size() >= 4) {
      adoptPlannedLoop(request, result, searchRadius);
    } else {
      handleNoAcceptableLoop(request, result, algo, searchRadius, direction);
    }
  }

  /**
   * Candidate sources and per-dispatch policies, resolved once: the isochrone
   * expansion (ISO_GREEDY), the Phase 2.0 asymmetry bias, both candidate
   * providers, the return oracle, the pool shape/health seed, the frontier
   * axis, and the start policy. Read-only for the rest of the dispatch.
   */
  private static final class CandidateSources {
    final IsochroneExpansionResult iso;
    final IsoAsymmetryBias bias;
    /** User direction, or the Phase 2.0 bias bearing when it fired. */
    final double effectiveDirection;
    final GraphNativeCandidateProvider graphNativeProvider;
    final RoundTripCandidateProvider provider;
    final int baseSubRouteCount;
    final ReturnDistanceOracle returnOracle;
    final IsoPoolHealth.PoolShape poolShape;
    final FrontierAxis frontierAxis;
    final IsoStartPolicy isoStartPolicy;
    final boolean startGraphNativeOnly;

    CandidateSources(IsochroneExpansionResult iso, IsoAsymmetryBias bias,
                     double effectiveDirection, GraphNativeCandidateProvider graphNativeProvider,
                     RoundTripCandidateProvider provider, int baseSubRouteCount,
                     ReturnDistanceOracle returnOracle, IsoPoolHealth.PoolShape poolShape,
                     FrontierAxis frontierAxis, IsoStartPolicy isoStartPolicy) {
      this.iso = iso;
      this.bias = bias;
      this.effectiveDirection = effectiveDirection;
      this.graphNativeProvider = graphNativeProvider;
      this.provider = provider;
      this.baseSubRouteCount = baseSubRouteCount;
      this.returnOracle = returnOracle;
      this.poolShape = poolShape;
      this.frontierAxis = frontierAxis;
      this.isoStartPolicy = isoStartPolicy;
      this.startGraphNativeOnly = isoStartPolicy == IsoStartPolicy.GRAPH_NATIVE_ONLY;
    }
  }

  /** Dispatch phase 1: resolve every candidate source and policy for this run. */
  private CandidateSources prepareCandidateSources(OsmNodeNamed start, double searchRadius,
      double direction, RoundTripAlgorithm algo, double desiredDistance) {
    // Phase 2.0: when ISO_GREEDY runs without an explicit user direction,
    // use the isochrone's reachability asymmetry to bias the initial bearing
    // toward the most-reaching sector. The legacy default of "direction=-1"
    // (ANY) means candidate scoring's direction term is inert at step 1,
    // and the candidate placement uses an unrelated heuristic. On terrain-
    // asymmetric networks (coast, valley, island) this can place initial
    // candidates in geographically unreachable regions. The asymmetry bias
    // grounds the initial direction in actual graph reachability.
    //
    // Bias applies ONLY when:
    //   - algo == ISO_GREEDY (we need the frontier table)
    //   - direction < 0 (user did not specify a direction)
    //   - at least one bucket meets quality thresholds (airDist >= 0.6 *
    //     searchRadius AND hits >= 3)
    // Otherwise direction is preserved verbatim.
    // NOTE (measured 2026-07-04, do not re-attempt without new evidence):
    // adopting the expansion's compiled step-1 legs as planner sub-legs
    // (includeCandidateTracks=true here + routedTrack forwarding at step 1)
    // was implemented and A/B-measured on the deterministic Basel matrix.
    // Result: quality-neutral mean with a systematic short-bias (exact
    // Dijkstra legs are shorter than the pass1coefficient-directed legs the
    // planner is tuned around), one deterministic shipped regression
    // (basel_30km_gravel_W: AUTO 0.84 -> 0.58 composite, 21km for a 30km
    // request), and no latency win (+4%: track-compile overhead outweighed
    // the saved step-1 re-routes). Reverted; diff preserved in the session
    // findings.
    IsochroneExpansionResult iso = algo == RoundTripAlgorithm.ISO_GREEDY
      ? ops.runIsochroneExpansion(start, searchRadius)
      : null;
    double effectiveDirection = direction;
    IsoAsymmetryBias bias = IsoAsymmetryBias.NONE;
    if (algo == RoundTripAlgorithm.ISO_GREEDY && direction < 0 && iso != null) {
      bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(iso.frontier, searchRadius);
      if (bias.applied) {
        effectiveDirection = bias.bearingDegrees;
        ops.logInfo("ISO_GREEDY: iso-asymmetry bias selected bearing="
          + (int) bias.bearingDegrees + "° (indirectness=" + String.format("%.2f", bias.indirectness)
          + ", hits=" + bias.hits + ", airDist=" + bias.airDistMeters + "m)");
      }
    }
    GraphNativeCandidateProvider graphNativeProvider = new GraphNativeCandidateProvider(ops, ops);
    RoundTripCandidateProvider provider = buildCandidateProvider(algo, start, searchRadius,
      effectiveDirection, iso, graphNativeProvider);
    int baseSubRouteCount = selectGreedySubRouteCount(desiredDistance, ops.routingContext().getProfileName());

    // Return-distance oracle (F6): sector-resolved return estimates from the
    // start-centered pool expansion when one exists (ISO_GREEDY — largest
    // coverage). Plain GREEDY deliberately has no oracle: a step-1 expansion
    // oracle was measured quality-negative, so null means the planner falls
    // back to the global-EMA estimate everywhere.
    ReturnDistanceOracle returnOracle = ReturnDistanceOracle.build(iso, start.ilon, start.ilat);
    if (returnOracle != null) {
      ops.logInfo("greedy: return oracle from pool expansion (kappa="
        + String.format(Locale.ROOT, "%.2f", returnOracle.kappa()) + ")");
    }

    // Iso-pool shape for the planner's health tracker: measured
    // once per pool, shared across the ladder rungs (each rung wraps it in a
    // fresh per-plan IsoPoolHealth). Null when the provider is graph-native
    // only — the planner then skips every health hook (plain GREEDY parity).
    IsoPoolHealth.PoolShape poolShape = null;
    if (provider instanceof BlendedCandidateProvider) {
      IsochroneCandidateProvider isoProvider = ((BlendedCandidateProvider) provider).isoProvider();
      poolShape = new IsoPoolHealth.PoolShape(isoProvider.poolSize(),
        isoProvider.distinctSectorCount(), isoProvider.angularSpanDegrees(),
        isoProvider.contourLevelCount(), returnOracle != null);
      ops.logInfo("ISO_GREEDY: iso-pool shape: " + poolShape.describe());
    }

    FrontierAxis frontierAxis = (algo == RoundTripAlgorithm.ISO_GREEDY && iso != null)
      ? GeometricWaypointPlacer.computeFrontierAxis(iso.frontier, searchRadius) : FrontierAxis.NONE;
    IsoStartPolicy isoStartPolicy = algo == RoundTripAlgorithm.ISO_GREEDY
      ? selectIsoStartPolicy(poolShape)
      : IsoStartPolicy.BLEND;
    if (algo == RoundTripAlgorithm.ISO_GREEDY) {
      ops.logInfo("ISO_GREEDY: start policy " + isoStartPolicy);
    }
    return new CandidateSources(iso, bias, effectiveDirection, graphNativeProvider,
      provider, baseSubRouteCount, returnOracle, poolShape, frontierAxis, isoStartPolicy);
  }

  /** Attempt-ladder outcome: the (possibly degraded) planner result plus the
   *  Phase 2.1 axis-retry telemetry the dispatch stamps afterwards. */
  private static final class LadderOutcome {
    final RoundTripResult result;
    final boolean phase21Triggered;
    final boolean phase21Succeeded;
    final double phase21RetryDir;

    LadderOutcome(RoundTripResult result, boolean phase21Triggered,
                  boolean phase21Succeeded, double phase21RetryDir) {
      this.result = result;
      this.phase21Triggered = phase21Triggered;
      this.phase21Succeeded = phase21Succeeded;
      this.phase21RetryDir = phase21RetryDir;
    }
  }

  /**
   * Dispatch phase 2: the first attempt (start policy applied) and, when the
   * terrain evidence warrants it, the Phase 2.1 axis retry.
   */
  private LadderOutcome runAttemptLadder(RoundTripRequest request, OsmNodeNamed start,
      double searchRadius, double desiredDistance, double direction, CandidateSources src) {
    RoundTripCandidateProvider primaryProvider = src.startGraphNativeOnly ? src.graphNativeProvider : src.provider;
    // The return oracle survives a graph-native-only start: it calibrates from
    // the raw expansion cell cloud, not the filtered pool, so it stays valid in
    // exactly the constrained terrain that demotes the pool.
    ReturnDistanceOracle primaryReturnOracle = src.returnOracle;
    IsoPoolHealth.PoolShape primaryPoolShape = src.startGraphNativeOnly ? null : src.poolShape;

    // First attempt — user direction (or Phase 2.0 biased bearing).
    RoundTripResult result = runGreedyAttempt(request, start, searchRadius, desiredDistance,
      src.effectiveDirection, src.baseSubRouteCount, primaryProvider, src.bias,
      primaryReturnOracle, primaryPoolShape, src.isoStartPolicy);

    // Phase 2.1: if the first attempt degraded AND the user supplied an
    // explicit direction AND the frontier has a strong terrain axis AND
    // the user's direction is perpendicular to that axis, retry once
    // along the axis. This addresses the Inn-Valley pattern: 100km loop
    // requested heading N where the road network only supports E-W.
    boolean phase21Triggered = false;
    boolean phase21Succeeded = false;
    double phase21RetryDir = Double.NaN;
    // Bounded effort: the axis retry re-runs the whole ladder exactly when
    // the terrain is hard — the opposite of a bounded tier's contract.
    // (Deliberately NOT gated on the start policy: corridor terrain is both
    // what demotes the pool and what the axis retry exists to recover.)
    if (!request.effortPolicy.skipRetryLayers
        && isDegradedGreedyResult(result)
        && direction >= 0
        && src.frontierAxis.hasStrongAxis
        && GeometricWaypointPlacer.isPerpendicularToAxis(direction, src.frontierAxis.axisBearingDegrees)
        // Request-budget gate: the axis retry re-runs the whole subRouteCount
        // ladder — only worth starting when the request can still fund it.
        && ops.remainingRequestBudgetMs() >= RoundTripOrchestrator.MIN_LADDER_RUNG_BUDGET_MS) {
      phase21Triggered = true;
      phase21RetryDir = GeometricWaypointPlacer.chooseAxisBearing(src.frontierAxis.axisBearingDegrees, direction);
      ops.logInfo("ISO_GREEDY: Phase 2.1 axis retry — user direction " + (int) direction
        + "° is perpendicular to terrain axis " + String.format("%.0f", src.frontierAxis.axisBearingDegrees)
        + "° (strength=" + String.format("%.1fx", src.frontierAxis.strength)
        + "); retrying with axis-aligned direction " + (int) phase21RetryDir + "°");
      RoundTripResult retry = runGreedyAttempt(request, start, searchRadius, desiredDistance,
        phase21RetryDir, src.baseSubRouteCount, src.provider, src.bias, src.returnOracle, src.poolShape,
        IsoStartPolicy.BLEND);
      if (!isDegradedGreedyResult(retry)
          && retry != null && retry.getLoopWaypoints() != null
          && retry.getLoopWaypoints().size() >= 4) {
        phase21Succeeded = true;
        result = retry;
      } else {
        // Retry also degraded → geographic infeasibility. Keep first-attempt
        // result for diagnostic display but mark the infeasibility for the
        // caller's error path below.
        ops.logInfo("ISO_GREEDY: Phase 2.1 axis retry ALSO degraded — geographic infeasibility detected");
      }
    }
    return new LadderOutcome(result, phase21Triggered, phase21Succeeded, phase21RetryDir);
  }

  /**
   * Dispatch phase 3: the internal graph-native-only comparison branch — runs
   * a second ladder on graph-native candidates when the blended result is
   * below the clear-accept bar, and returns the better of the two.
   */
  private RoundTripResult maybeRunInternalComparison(RoundTripRequest request,
      RoundTripAlgorithm algo, CandidateSources src, OsmNodeNamed start,
      double searchRadius, double desiredDistance, RoundTripResult result) {
    RouteChoiceScore.Verdict blendedInternalVerdict = null;
    boolean runInternalBranch = false;
    if (algo == RoundTripAlgorithm.ISO_GREEDY
        && ops.routingContext().roundTripInternalCompare
        && !src.startGraphNativeOnly
        // QUALITY (runGreedyAlways) already fields a dedicated plain-GREEDY
        // child in the parent competition — this internal comparison would run
        // materially the same graph-native ladder a second time.
        && !request.effortPolicy.runGreedyAlways
        && src.provider instanceof BlendedCandidateProvider
        && System.currentTimeMillis() < (request.requestDeadline() == 0
            ? Long.MAX_VALUE : request.requestDeadline())) {
      // Evaluate the blended verdict ONCE; the selection below reuses it.
      blendedInternalVerdict = scoreInternalGreedyResult(request, result, desiredDistance, src.effectiveDirection);
      runInternalBranch = internalBranchNeeded(blendedInternalVerdict);
    }
    if (runInternalBranch) {
      ops.logInfo("ISO_GREEDY: running internal graph-native-only comparison branch");
      // Ladder order: BLEND (base first), NOT GRAPH_NATIVE_ONLY (base-1 first).
      // This branch replaced the ISO_GREEDY→GREEDY recursion, which ran the
      // BLEND-order ladder — and the fewer-steps-first order is measurably
      // wrong here: at mallorca_30km_gravel_W the base-1 rung returns a
      // non-degraded 10.4%-error plan that STOPS the ladder with a 4-point
      // loop routing to distR 0.62 (the undershoot-sentinel contraction
      // class), while the base rung produces the healthy loop the old
      // recursion shipped. Fewer-first remains correct for the START policy
      // (pool unhealthy from step 0), which keeps GRAPH_NATIVE_ONLY.
      RoundTripResult graphNativeResult = runGreedyAttempt(request, start, searchRadius, desiredDistance,
        src.effectiveDirection, src.baseSubRouteCount, src.graphNativeProvider, src.bias, null, null,
        IsoStartPolicy.BLEND);
      RouteChoiceScore.Verdict graphNativeVerdict = scoreInternalGreedyResult(
        request, graphNativeResult, desiredDistance, src.effectiveDirection);
      boolean comparable = graphNativeVerdict != null;
      RoundTripResult selected = selectBetterInternalIsoGreedyResult(
        result, blendedInternalVerdict, graphNativeResult, graphNativeVerdict);
      if (selected == graphNativeResult) {
        ops.logInfo("ISO_GREEDY: internal graph-native branch selected");
      } else if (comparable) {
        ops.logInfo("ISO_GREEDY: blended branch kept after internal graph-native comparison");
      } else {
        ops.logInfo("ISO_GREEDY: internal graph-native branch produced no comparable track");
      }
      result = selected;
      if (comparable && result != null) {
        result.setInternalGraphNativeCompared(true);
      }
      orchestrator.setPlannerResult(result);
    }
    return result;
  }

  /** Dispatch phase 4: stamp the source-attribution and Phase 2.1 telemetry. */
  private void stampDispatchTelemetry(RoundTripResult result, CandidateSources src,
                                      LadderOutcome ladder) {
    if (result != null) {
      // The explicit record of the shipped result's candidate source. When no
      // blend exists at all (pool not admitted → `src.provider` IS the graph-native
      // src.provider), every attempt — including a successful Phase 2.1 axis retry —
      // planned on graph-native candidates. With an admitted blend, a
      // successful retry ran the blend, so only the primary attempt's start
      // policy counts.
      boolean blendAvailable = src.provider instanceof BlendedCandidateProvider;
      result.setGraphNativeOnlyStart(!blendAvailable
        || (src.startGraphNativeOnly && !ladder.phase21Succeeded));
      result.setPhase21AxisRetryTriggered(ladder.phase21Triggered);
      result.setPhase21AxisRetrySucceeded(ladder.phase21Succeeded);
      result.setPhase21AxisBearingDegrees(src.frontierAxis.hasStrongAxis
        ? src.frontierAxis.axisBearingDegrees : Double.NaN);
      result.setPhase21AxisStrength(src.frontierAxis.hasStrongAxis ? src.frontierAxis.strength : 0.0);
      result.setPhase21RetryDirectionDegrees(ladder.phase21RetryDir);
    }

    // Phase 2.1 used to also set request.error when both attempts degraded
    // (the spec's "refuse with infeasibility error" option). That cut off
    // doRoundTrip's later fallback path (waypoint algorithm), losing 2
    // iso_greedy/gravel scenarios on the broader corpus that the legacy
    // waypoint fallback had been salvaging. Drop the request.error write;
    // let the result return as degraded so the caller can fall back as
    // before. The axis info is still surfaced via the Phase 2.1 telemetry
    // fields on RoundTripResult for diagnostic purposes.
    if (ladder.phase21Triggered && !ladder.phase21Succeeded) {
      ops.logInfo("ISO_GREEDY: Phase 2.1 axis retry also degraded — geographic"
        + " infeasibility (axis " + axisName(src.frontierAxis.axisBearingDegrees)
        + ", strength " + String.format("%.1fx", src.frontierAxis.strength)
        + "); falling through to legacy fallback chain");
    }
  }

  /**
   * Dispatch outcome, success side: swap the planned waypoints in and adopt
   * the planner's detailed track directly (bypass), falling back to a budgeted
   * doRouting re-route when the bypass fails.
   */
  private void adoptPlannedLoop(RoundTripRequest request, RoundTripResult result,
                                double searchRadius) {
    for (String diag : result.getDiagnostics()) {
      ops.logInfo("greedy: " + diag);
    }
    // Spec §10 telemetry — compute-budget audit.
    ops.logInfo("greedy telemetry: candidatesGenerated=" + result.getCandidatesGenerated()
      + ", candidatesRouted=" + result.getCandidatesRouted()
      + ", returnChecks=" + result.getReturnChecksPerformed()
      + ", runtimeMs=" + result.getRuntimeMillis()
      + ", fallbackReason=" + (result.getFallbackReason() == null ? "none" : result.getFallbackReason()));
    // Source attribution — the aggregate view of the per-leg
    // "leg N source:" diagnostics logged above.
    ops.logInfo("greedy source attribution: acceptedIso=" + result.getAcceptedIsoLegs()
      + ", acceptedGraphNative=" + result.getAcceptedNonIsoLegs()
      + ", quotaInjectedAccepted=" + result.getAcceptedQuotaInjectedLegs()
      + ", poolHealth=" + (Double.isNaN(result.getIsoPoolHealthScore())
          ? "n/a" : String.format(Locale.US, "%.2f", result.getIsoPoolHealthScore()))
      + ", poolDemotedAtStep=" + result.getPoolDemotedAtStep());
    if (!result.isWithinTolerance()) {
      ops.logInfo("greedy: fallback — " + result.getFallbackReason());
    }
    ops.logInfo("greedy: planned " + result.getLoopWaypoints().size() + " waypoints"
      + ", estimated distance=" + result.getTotalDistanceMeters() + "m");

    // Route through the greedy ops.waypoints() with the standard routing engine.
    // The greedy planner's lookahead ensures ops.waypoints() are in well-connected
    // areas (not dead-end valleys), so orchestrator.doRoutingIntoRequest() produces gap-free tracks
    // following roads appropriate for the profile.
    ops.waypoints().clear();
    ops.waypoints().addAll(result.getLoopWaypoints());

    if (result.getMatchedWaypoints() != null) {
      ops.setMatchedWaypoints(result.getMatchedWaypoints());
    }

    if (result.getLegTracks() != null) {
      List<OsmTrack> legs = result.getLegTracks();
      request.setGreedyLegTracks(legs.toArray(new OsmTrack[0]));
    }

    // Phase 2 v3: the planner now retracks each committed leg, so its
    // merged track has full per-edge MessageData. Use that directly
    // instead of running orchestrator.doRoutingIntoRequest() which re-routes via a fragile
    // corridor mechanism that frequently fails or diverges. The
    // re-routing was wiping out the planner's hostility-aware
    // candidate choices, so the quality gate was seeing routes the
    // planner itself would have rejected. Diagnostic data: roughly
    // 80% of greedy legs in failing fastbike scenarios had the
    // corridor fail or diverge.
    boolean useDetailedPlannerTrack = result != null && result.getTrack() != null
      && result.getTrack().nodes != null && result.getTrack().nodes.size() >= RoundTripOrchestrator.MIN_ROUNDTRIP_LOOP_NODES;
    if (useDetailedPlannerTrack) {
      try {
        orchestrator.setTrack(result.getTrack());
        if (result.getMatchedWaypoints() != null) {
          ops.setMatchedWaypoints(result.getMatchedWaypoints());
        }
        orchestrator.cleanup.finalizeAdoptedRoundTripTrack(request.track, ops.matchedWaypoints());
      } catch (Exception e) {
        ops.logInfo("greedy: bypass path failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), falling back to doRouting");
        useDetailedPlannerTrack = false;
      }
    }
    if (!useDetailedPlannerTrack) {
      ops.routingContext().waypointCatchingRange = 250;
      request.setSearchRadius(searchRadius);
      // Honor the request deadline: once it has fully passed, do NOT start
      // the fallback re-route at all (doRouting resets ops.startTime(), so any
      // budget handed to it is a real overrun). While budget remains, fund
      // the fallback with the REMAINING budget, floored so a nearly-spent
      // request still gets a usable (bounded, < RoundTripOrchestrator.MIN_LADDER_RUNG_BUDGET_MS
      // overrun) salvage slice rather than a guaranteed instant timeout.
      long remaining = ops.remainingRequestBudgetMs();
      if (request.routingBudgetMs > 0 && remaining <= 0) {
        orchestrator.setError("round-trip request budget exhausted before the fallback re-route ("
          + remaining + "ms remaining)");
        ops.logInfo(request.error);
        orchestrator.setTrack(null);
        request.setGreedyLegTracks(null);
        return;
      }
      try {
        long fallbackBudget = request.routingBudgetMs <= 0
          ? request.routingBudgetMs
          : Math.min(request.routingBudgetMs,
              Math.max(RoundTripOrchestrator.MIN_LADDER_RUNG_BUDGET_MS, remaining));
        orchestrator.doRoutingIntoRequest(fallbackBudget);
      } catch (Exception e) {
        ops.logInfo("greedy: doRouting failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        throw e;
      } finally {
        request.setGreedyLegTracks(null);
      }
    }
  }

/**
 * Dispatch outcome, failure side: the ISO_GREEDY→GREEDY recursion, the
   * budget-exhausted rejection, or best-effort adoption for the shared gate
   * to grade.
   */
  private void handleNoAcceptableLoop(RoundTripRequest request, RoundTripResult result,
                                      RoundTripAlgorithm algo, double searchRadius,
                                      double direction) {
    // ISO_GREEDY only fails over to plain GREEDY if it also failed; otherwise
    // ISO_GREEDY's planner already added graph-native per-step candidates
    // when the start-centered iso pool was insufficient (see buildCandidateProvider).
    // BALANCED skips this recursion (another full ladder): it adopts the
    // best-effort track below instead, and its caller falls back to the
    // cheap WAYPOINT placement when no track exists at all.
    if (algo == RoundTripAlgorithm.ISO_GREEDY
        && !request.effortPolicy.skipRetryLayers
        && ops.remainingRequestBudgetMs() >= RoundTripOrchestrator.MIN_LADDER_RUNG_BUDGET_MS) {
      ops.logInfo("ISO_GREEDY produced no loop, falling back to GREEDY with graph-native candidates");
      doGreedyRoundTrip(request, searchRadius, direction, RoundTripAlgorithm.GREEDY);
    } else if (algo == RoundTripAlgorithm.ISO_GREEDY && !request.effortPolicy.skipRetryLayers) {
      // Same recursion, but the request budget is spent — adopt/report what
      // we have instead of starting another multi-plan GREEDY ladder.
      ops.logInfo("ISO_GREEDY produced no loop and request budget is exhausted ("
        + ops.remainingRequestBudgetMs() + "ms left), skipping GREEDY fallback ladder");
      orchestrator.rejectWithError(
        "greedy round trip planner produced no acceptable loop within the request budget"
          + (result == null || result.getFallbackReason() == null ? "" : ": " + result.getFallbackReason()),
        result == null ? null : result.getTrack());
    } else {
      // Adopt the planner's best-effort loop (if any) and hand it up to the
      // uniform quality gate in doRoundTrip, which is the single place that
      // decides hard-reject (STRUCTURAL, or any failure under strict mode) vs.
      // a lenient advisory. This keeps greedy consistent with the other
      // algorithms and removes a duplicate, tier-blind leniency decision: the
      // gate (plus the node/distance floor just above it) inspects the verdict
      // rather than re-deriving "usable" from node counts here.
      OsmTrack bestEffort = result == null ? null : result.getTrack();
      if (bestEffort != null && bestEffort.nodes != null && !bestEffort.nodes.isEmpty()) {
        ops.logInfo("greedy: adopting best-effort loop for the quality gate to grade ("
          + (result.getFallbackReason() == null ? "?" : result.getFallbackReason()) + ")");
        orchestrator.setTrack(bestEffort);
        if (result.getMatchedWaypoints() != null) {
          ops.setMatchedWaypoints(result.getMatchedWaypoints());
        }
        // finalize can throw (voice hints / speed profile / spur removal). Guard
        // it like the bypass path above: an exception here would otherwise
        // unwind past doRoundTrip's floor + quality gate (its catch does not
        // null request.track), shipping this un-gated best-effort track as a
        // success. On failure, reject instead so nothing skips the gate.
        try {
          orchestrator.cleanup.finalizeAdoptedRoundTripTrack(request.track, ops.matchedWaypoints());
          // request.error stays null: the floor check + quality gate in
          // doRoundTrip reject (and set request.error) if the loop is too small,
          // structurally broken, or strict mode is on; else it ships with a warning.
        } catch (Exception e) {
          orchestrator.rejectWithError("greedy best-effort finalize failed ("
            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", bestEffort);
        }
      } else {
        // Reached by plain GREEDY and by BALANCED's bounded ISO_GREEDY run
        // (which skips the GREEDY recursion) — keep the wording source-neutral.
        orchestrator.rejectWithError("greedy round trip planner produced no acceptable loop"
          + (result == null || result.getFallbackReason() == null ? "" : ": " + result.getFallbackReason()),
          result == null ? null : result.getTrack());
      }
    }
  }


  /**
   * Run one greedy planning attempt — the inner sub-route-count loop for a single
   * {@code tryDirection}. Stamps iso-asymmetry telemetry on the result and updates
   * the last-round-trip-result on every iteration so cross-attempt comparison sees
   * consistent metadata. The returned {@link RoundTripResult} may be degraded —
   * the caller decides whether to accept or retry.
   */
  private RoundTripResult runGreedyAttempt(RoundTripRequest request, OsmNodeNamed start, double searchRadius,
                                           double desiredDistance, double tryDirection,
                                           int baseSubRouteCount,
                                           RoundTripCandidateProvider provider,
                                           IsoAsymmetryBias bias,
                                           ReturnDistanceOracle returnOracle,
                                           IsoPoolHealth.PoolShape poolShape,
                                           IsoStartPolicy subRoutePolicy) {
    RoundTripResult result = null;
    RoundTripResult heldForcedCorridor = null;
    boolean firstRung = true;
    for (int subRouteCount : greedySubRouteCountPlan(baseSubRouteCount, subRoutePolicy)) {
      // Request-budget gate on the retry ladder: each plan() used to get a
      // fresh 30s deadline regardless of remaining request budget, so the
      // ladder alone could run ~4x the requested timeout. Stop starting new
      // rungs once the request budget cannot fund a useful plan anymore.
      // The FIRST rung is exempt: minimum-slice floors (the bounded tier, the
      // competition's MIN_CHILD) deliberately fund exactly one run, and that
      // floor equals this gate's threshold — checking remaining-vs-threshold
      // a millisecond into the slice would veto the very run the floor
      // funded. The planner still honors its external deadline internally.
      long remaining = ops.remainingRequestBudgetMs();
      if (!firstRung && remaining < RoundTripOrchestrator.MIN_LADDER_RUNG_BUDGET_MS) {
        ops.logInfo("greedy: request budget exhausted (" + remaining
          + "ms left), skipping remaining subRouteCount ladder");
        break;
      }
      firstRung = false;
      ops.logInfo("greedy round trip: subRouteCount=" + subRouteCount + ", direction=" + (int) tryDirection);
      GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(ops, provider,
        new CandidateScorer(), subRouteCount, 0.05, 8);
      planner.setHostilityActive(request.pavedProfile);
      // The planner's paved verdict feeds only its hostility checks and its
      // internal gate calls; it comes from the request-owned classification
      // probed once at request entry.
      planner.setPavedProfile(request.pavedProfile);
      planner.setVarietySeed(ops.routingContext().getRoundTripSeed());
      planner.setRouteBudgets(request.effortPolicy.topKNormal, request.effortPolicy.topKLate);
      planner.setPlanBudgetScale(request.effortPolicy.planBudgetScale);
      planner.setReturnOracle(returnOracle);
      // Fresh per-plan health tracker: dynamic evidence must not leak across
      // ladder rungs (a demotion earned at subRouteCount=5 says nothing about
      // the 4-step plan's pool usage).
      planner.setPoolHealth(poolShape == null ? null : new IsoPoolHealth(poolShape));
      planner.setExternalDeadline(request.requestDeadline() == 0
        ? Long.MAX_VALUE : request.requestDeadline());
      result = planner.plan(start, desiredDistance, tryDirection);
      if (result != null) {
        result.setIsoAsymmetryBearingApplied(bias.applied);
        result.setIsoAsymmetryBearingDegrees(bias.bearingDegrees);
        result.setIsoAsymmetryBestBucketIndirectness(bias.indirectness);
        result.setIsoAsymmetryBestBucketHits(bias.hits);
        result.setIsoAsymmetryBestBucketAirDistMeters(bias.airDistMeters);
      }
      orchestrator.setPlannerResult(result);
      if (isAcceptableRungResult(result)) {
        if (!result.isForcedCorridorAccepted()) {
          return result;
        }
        // Forced-corridor is per-rung evidence: THIS sub-route count found no
        // clean alternative, but another rung may (annecy 30km S: 5 sub-legs
        // force the corridor, 6 ride a clean full-length loop). Hold the
        // closest-to-target forced result and keep climbing the ladder; ship
        // it only when no rung produces a clean loop.
        heldForcedCorridor = betterForcedFallback(heldForcedCorridor, result, desiredDistance);
        ops.logInfo("greedy: attempt with " + subRouteCount
          + " sub-routes forced a same-way-back corridor — held as fallback, retrying for a clean loop");
        continue;
      }
      ops.logInfo("greedy: attempt with " + subRouteCount + " sub-routes did not produce an acceptable loop"
        + (result == null || result.getFallbackReason() == null ? "" : " (" + result.getFallbackReason() + ")"));
    }
    if (heldForcedCorridor != null) {
      orchestrator.setPlannerResult(heldForcedCorridor);
      return heldForcedCorridor;
    }
    return result;
  }

  /**
   * A rung result the ladder may accept or hold: not degraded, and carrying a
   * real loop skeleton (start + 2 vias + close). Forced-corridor status is a
   * SEPARATE axis — an acceptable forced result is held, not returned.
   */
  static boolean isAcceptableRungResult(RoundTripResult result) {
    return !isDegradedGreedyResult(result)
      && result != null && result.getLoopWaypoints() != null
      && result.getLoopWaypoints().size() >= 4;
  }

  /** The forced-corridor fallback to keep: whichever rung result lands closest to the target distance. */
  static RoundTripResult betterForcedFallback(RoundTripResult held, RoundTripResult candidate,
                                              double desiredDistance) {
    if (held == null) {
      return candidate;
    }
    return distanceError(candidate, desiredDistance) < distanceError(held, desiredDistance)
      ? candidate : held;
  }

  /** Relative distance miss of a rung result — the tie-breaker between held forced-corridor rungs. */
  private static double distanceError(RoundTripResult result, double desiredDistance) {
    return Math.abs(result.getTotalDistanceMeters() - desiredDistance) / Math.max(1.0, desiredDistance);
  }

  /**
   * Candidate provider for the mode: GREEDY uses per-step graph-native candidates;
   * ISO_GREEDY blends a bounded start-centered isochrone pool with that same
   * provider. Geometric radial placement is intentionally unused here.
   */
  private RoundTripCandidateProvider buildCandidateProvider(RoundTripAlgorithm algo,
                                                            OsmNodeNamed start,
                                                            double searchRadius,
                                                            double startDirection,
                                                            IsochroneExpansionResult iso,
                                                            GraphNativeCandidateProvider graphNative) {
    if (algo != RoundTripAlgorithm.ISO_GREEDY) {
      return graphNative;
    }
    if (iso == null || iso.frontier.length < 6 || iso.candidates.size() < 12) {
      ops.logInfo("ISO_GREEDY: insufficient isochrone data ("
        + (iso == null ? 0 : iso.frontier.length) + " buckets, "
        + (iso == null ? 0 : iso.candidates.size()) + " raw candidates), using graph-native candidates");
      return graphNative;
    }
    IsochroneCandidateProvider isoProvider =
      IsochroneCandidateProvider.fromPool(searchRadius, startDirection, iso.candidates);
    if (isoProvider.poolSize() < 6) {
      ops.logInfo("ISO_GREEDY: candidate pool too small after filtering ("
        + isoProvider.poolSize() + "), using graph-native candidates");
      return graphNative;
    }
    if (!isoProvider.isDiverse()) {
      ops.logInfo("ISO_GREEDY: candidate pool concentrated in a narrow corridor ("
        + isoProvider.poolSize() + " candidates), using graph-native candidates");
      return graphNative;
    }
    // ISO_GREEDY: blend start-centered iso depth with per-step graph-native
    // candidates. Both sources are road-native; neither invents coordinates.
    ops.logInfo("ISO_GREEDY: blended isochrone+graph-native provider (iso pool="
      + isoProvider.poolSize() + ")");
    return new BlendedCandidateProvider(isoProvider, graphNative);
  }

  private RouteChoiceScore.Verdict scoreInternalGreedyResult(RoundTripRequest request, RoundTripResult result,
                                                            double desiredDistance,
                                                            double direction) {
    return scoreInternalGreedyResult(result, desiredDistance,
      ops.routingContext().getProfileName(), request.pavedProfile, direction,
      ops.routingContext().allowSamewayback, ops.roundTripFerriesAllowed());
  }

  static RouteChoiceScore.Verdict scoreInternalGreedyResult(RoundTripResult result,
                                                            double desiredDistance,
                                                            String profileName,
                                                            boolean pavedProfile,
                                                            double direction,
                                                            boolean allowSamewayback,
                                                            boolean allowFerries) {
    if (result == null || isDegradedGreedyResult(result)
        || result.getTrack() == null
        || result.getLoopWaypoints() == null
        || result.getLoopWaypoints().size() < 4) {
      return null;
    }
    RoundTripQualityResult gate = RoundTripQualityGate.evaluate(result.getTrack(),
      desiredDistance, pavedProfile,
      allowSamewayback || result.isForcedCorridorAccepted(),
      false, allowFerries);
    if (gate == null || !gate.isAccepted()) {
      return null;
    }
    return RouteChoiceScore.score(result.getTrack(), desiredDistance,
      profileName, gate, direction);
  }

  /**
   * A blended verdict below the clear-accept bar (or null) warrants the internal
   * graph-native comparison. Trigger and selection both read verdicts from
   * {@link #scoreInternalGreedyResult} so they can never judge a track differently
   * — they drifted once (ferries hard-coded off in a separate trigger path) and
   * every ferry-using loop paid a spurious extra ladder.
   */
  static boolean internalBranchNeeded(RouteChoiceScore.Verdict blendedVerdict) {
    return blendedVerdict == null || blendedVerdict.score() < RoundTripOrchestrator.CLEAR_ACCEPT_THRESHOLD;
  }

  private static boolean isDegradedGreedyResult(RoundTripResult result) {
    return result != null
      && result.getFallbackReason() != null
      && result.getFallbackReason().startsWith(GreedyRoundTripPlanner.DEGRADED_FALLBACK_PREFIX);
  }

  /**
   * Pick between the blended result and the internal graph-native branch, on
   * verdicts computed ONCE at the call site (each gate+score pass rebuilds the
   * crossing grid and corridor index — two per comparison, not four).
   */
  private static RoundTripResult selectBetterInternalIsoGreedyResult(
      RoundTripResult blended, RouteChoiceScore.Verdict blendedScore,
      RoundTripResult graphNative, RouteChoiceScore.Verdict graphScore) {
    if (graphScore == null) {
      return blended;
    }
    if (blendedScore == null) {
      return graphNative;
    }
    if (graphScore.score() > blendedScore.score() + 1e-9) {
      return graphNative;
    }
    return blended;
  }

  /** Human-readable axis label for the infeasibility error. */
  private static String axisName(double axisBearingDegrees) {
    // axisBearingDegrees is canonical [0, 180). Snap to the nearest cardinal
    // pair for a readable label.
    double a = ((axisBearingDegrees % 180) + 180) % 180;
    if (a < 22.5 || a >= 157.5) return "N-S";
    if (a < 67.5) return "NE-SW";
    if (a < 112.5) return "E-W";
    return "NW-SE";
  }

  public static int selectGreedySubRouteCount(double desiredDistance, String profileName) {
    int n;
    if (desiredDistance < 8000) {
      n = 3;
    } else if (desiredDistance < 30000) {
      n = 4;
    } else if (desiredDistance < 80000) {
      n = 5;
    } else {
      n = 6;
    }
    if (profileName != null && profileName.toLowerCase(Locale.US).contains("mtb")) {
      n++;
    }
    return Math.max(3, Math.min(6, n));
  }

  public static int[] greedySubRouteCountPlan(int base, IsoStartPolicy policy) {
    int clamped = Math.max(3, Math.min(6, base));
    List<Integer> counts = new ArrayList<>(6);
    if (policy == IsoStartPolicy.GRAPH_NATIVE_ONLY) {
      addUniqueCount(counts, clamped - 1);
      addUniqueCount(counts, clamped);
      addUniqueCount(counts, clamped - 2);
      addUniqueCount(counts, clamped + 1);
      addUniqueCount(counts, clamped + 2);
      addUniqueCount(counts, clamped - 3);
    } else {
      addUniqueCount(counts, clamped);
      addUniqueCount(counts, clamped + 1);
      addUniqueCount(counts, clamped - 1);
      addUniqueCount(counts, clamped - 2);
      addUniqueCount(counts, clamped + 2);
      addUniqueCount(counts, clamped - 3);
    }
    int[] result = new int[counts.size()];
    for (int i = 0; i < counts.size(); i++) result[i] = counts.get(i);
    return result;
  }

  private static void addUniqueCount(List<Integer> counts, int n) {
    if (n < 3 || n > 6 || counts.contains(n)) return;
    counts.add(n);
  }

  public static IsoStartPolicy selectIsoStartPolicy(IsoPoolHealth.PoolShape poolShape) {
    if (poolShape == null) {
      return IsoStartPolicy.GRAPH_NATIVE_ONLY;
    }
    IsoPoolHealth staticHealth = new IsoPoolHealth(poolShape);
    // Only UNHEALTHY escalates to a graph-native-only start. A statically
    // DEGRADED pool (the weakest admitted shape sits exactly at 0.50) keeps
    // the blend: the planner's influence reduction — stripped prior terms
    // plus an extra routed quota seat — is the calibrated response, and it
    // engages from step 1 because the static deduction is already in the
    // score. UNHEALTHY stays reachable only through in-plan evidence with
    // the current weights (static floor 0.50); unadmitted pools take the
    // poolShape == null arm above.
    if (staticHealth.state() == IsoPoolHealth.State.UNHEALTHY) {
      return IsoStartPolicy.GRAPH_NATIVE_ONLY;
    }
    // No third value for the perpendicular-strong-axis situation: the former
    // DUAL_IF_WEAK was behaviorally identical to BLEND (no consumer ever
    // distinguished them), and the Phase 2.1 axis retry derives its own
    // trigger conditions from the frontier axis directly.
    return IsoStartPolicy.BLEND;
  }

  public enum IsoStartPolicy {
    BLEND,
    GRAPH_NATIVE_ONLY
  }
}
