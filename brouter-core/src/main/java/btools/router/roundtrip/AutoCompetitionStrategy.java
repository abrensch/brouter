package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

/**
 * AUTO candidate competition tier: runs candidate algorithms in isolated
 * child engines, scores the gated results, and adopts the winner (or the
 * least-bad best-effort track in lenient mode). The adopted outcome flows
 * through the orchestrator's SHARED finalization like every other tier:
 * children run with decoration suppressed
 * ({@link btools.router.RoutingContext#roundTripSuppressDecoration}), the
 * winner's parent-computed gate verdict is stashed for the shared gate to
 * consume (no third gate pass), and the parent decorates and writes output
 * exactly once. QUALITY is this strategy pinned to the MAX preset (see the
 * ladder resolution).
 */
final class AutoCompetitionStrategy implements RoundTripStrategy {

  private final RoundTripOrchestrator orchestrator;
  private final RoundTripEngineOps ops;

  AutoCompetitionStrategy(RoundTripOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
    this.ops = orchestrator.ops;
  }

  @Override
  public void attempt(RoundTripRequest request, TierSlice slice) {
    request.effortPolicy = slice.effortPolicy;
    runAutoCandidateCompetition(slice.searchRadius, slice.direction);
  }

  private static final long DEFAULT_AUTO_BUDGET_MS = 60_000;

  /**
   * AUTO's plain-GREEDY entitlement check: a below-threshold ISO_GREEDY does not
   * imply a useful second GREEDY run — if ISO_GREEDY already used graph-native
   * candidates (provider fallback or internal graph-native compare), GREEDY would
   * just duplicate the same source truth.
   */
  static boolean autoNeedsPlainGreedy(RoundTripCandidateResult isoGreedyR,
                                      long now, long deadline) {
    return autoPlainGreedyDiscardReason(isoGreedyR, now, deadline) == null;
  }

  static String autoPlainGreedyDiscardReason(RoundTripCandidateResult isoGreedyR,
                                             long now, long deadline) {
    if (now >= deadline) {
      return "past deadline at decision point";
    }
    if (isoGreedyR == null || !isoGreedyR.accepted()) {
      return null;
    }
    if (isoGreedyR.scoreValue() >= RoundTripOrchestrator.CLEAR_ACCEPT_THRESHOLD) {
      return "ISO_GREEDY strong";
    }
    if (isoGreedyR.internalGraphNativeCompared()) {
      return "ISO_GREEDY already compared graph-native branch";
    }
    if (isoGreedyAbsorbedGraphNativeTruth(isoGreedyR)) {
      return "ISO_GREEDY absorbed graph-native truth";
    }
    return null;
  }

  /**
   * Budget (ms) for the next sequential AUTO candidate: time left to the shared
   * competition deadline, floored at {@link #MIN_CHILD_BUDGET_MS} so a spawned
   * candidate gets a usable slice rather than ~0.
   */
  static long childCandidateBudgetMs(long deadline, long now) {
    return Math.max(MIN_CHILD_BUDGET_MS, deadline - now);
  }

  /**
   * AUTO candidate competition for generated round trips (no user vias). Runs
   * greedy candidates first, the legacy probe/WAYPOINT generator only as fallback:
   * <ol>
   *   <li>ISO_GREEDY — iso-derived candidates fed to the greedy planner.</li>
   *   <li>GREEDY — plain graph-native planner, if ISO_GREEDY fails or is weak.</li>
   *   <li>WAYPOINT/probe — only if greedy produced no accepted route.</li>
   * </ol>
   *
   * <p>Each candidate runs in an isolated child {@link RoutingEngine} built from a
   * request-fields-only copy of the parent {@link RoutingContext} (no parsed/runtime
   * state shared, output suppressed). The highest-scoring accepted candidate's
   * {@link OsmTrack} is adopted; its disclosures are surfaced. If none pass strict
   * validation, the lenient default adopts the least-bad best-effort track (see
   * {@link #selectBestEffortCandidate}); strict mode leaves the track null and sets
   * an error.
   */
  private void runAutoCandidateCompetition(double searchRadius, double direction) {
    long t0 = System.currentTimeMillis();
    // One wall-clock budget shared across the sequentially-run candidates, so
    // the competition cannot run ~Nx the requested timeout. Each child gets the
    // remaining slice (see runChildCandidate); once it is exhausted we stop
    // spawning further candidates.
    long deadline = t0 + (ops.maxRunningTime() > 0 ? ops.maxRunningTime() : DEFAULT_AUTO_BUDGET_MS);
    List<RoundTripCandidateResult> results = new ArrayList<>(3);

    // 1+2. Run ISO_GREEDY first, then plain GREEDY only when the ISO result
    // proves the comparison is still useful. This is the default:
    // avoid duplicate production algorithm runs when ISO_GREEDY is strong or
    // has already absorbed the graph-native provider fallback.
    RoundTripCandidateResult isoGreedyR =
      runChildCandidate(RoundTripAlgorithm.ISO_GREEDY, searchRadius, direction, deadline);
    long greedyDecisionTime = System.currentTimeMillis();
    // MAX effort (QUALITY tier): the plain-GREEDY competitor always runs — the
    // caller asked for the best loop and accepts the cost; the health-gated
    // skip is a latency optimization the tier explicitly opts out of. Still
    // bounded by the shared deadline.
    boolean greedyNeeded = orchestrator.request.effortPolicy.runGreedyAlways
      && System.currentTimeMillis() < deadline
      || autoNeedsPlainGreedy(isoGreedyR, greedyDecisionTime, deadline);
    results.add(isoGreedyR);
    ops.logInfo("AUTO candidate: " + isoGreedyR);

    if (greedyNeeded) {
      RoundTripCandidateResult greedyR =
        runChildCandidate(RoundTripAlgorithm.GREEDY, searchRadius, direction, deadline);
      results.add(greedyR);
      ops.logInfo("AUTO candidate: " + greedyR);
    }

    // 3. Compare accepted greedy candidates; pick highest score.
    RoundTripCandidateResult winner = null;
    for (RoundTripCandidateResult r : results) {
      if (!r.accepted()) continue;
      if (winner == null || r.scoreValue() > winner.scoreValue()) {
        winner = r;
      }
    }

    // 4. Legacy fallback only if both greedy variants failed hard validation
    //    and budget remains.
    if (winner == null && System.currentTimeMillis() < deadline) {
      RoundTripCandidateResult waypointR = runChildCandidate(
        RoundTripAlgorithm.WAYPOINT, searchRadius, direction, deadline);
      results.add(waypointR);
      ops.logInfo("AUTO candidate: " + waypointR);
      if (waypointR.accepted()) {
        winner = waypointR;
      }
    }

    // 5. Last-resort ISOCHRONE fallback. The direct isochrone-frontier
    //    placement reaches loops the greedy radial candidates miss in
    //    constrained terrain (e.g. a valley where the radial probe can't
    //    form a loop in the requested direction, or only finds a chaotic
    //    one). Purely additive: only runs when ISO_GREEDY, GREEDY and
    //    WAYPOINT have all already failed, so it cannot displace a winner.
    if (winner == null && System.currentTimeMillis() < deadline) {
      RoundTripCandidateResult isochroneR = runChildCandidate(
        RoundTripAlgorithm.ISOCHRONE, searchRadius, direction, deadline);
      results.add(isochroneR);
      ops.logInfo("AUTO candidate: " + isochroneR);
      if (isochroneR.accepted()) {
        winner = isochroneR;
      }
    }
    long totalMs = System.currentTimeMillis() - t0;

    // Lenient default: if no candidate passed strict validation but one produced
    // a rideable route that failed only a QUALITY check, adopt the best-effort
    // one (the child already attached its "Warning:" advisory) instead of
    // returning nothing — keeping AUTO consistent with direct-dispatch leniency.
    // Candidates are in algorithm-quality order (ISO_GREEDY, GREEDY, WAYPOINT,
    // ISOCHRONE), so the first quality-failed track is the best best-effort.
    // The lenient/strict decision uses the same predicate as the gate path
    // (roundTripQualityHardReject), so strict mode keeps the hard "no acceptable
    // route" and only QUALITY verdicts are adopted leniently.
    if (winner == null) {
      // Among the QUALITY-tier best-effort candidates (STRUCTURAL and, under strict
      // mode, every failure are excluded by roundTripQualityHardReject), pick the
      // LEAST-BAD overall rather than the first by algorithm order. We rank with the
      // same multi-factor RouteChoiceScore used for accepted winners — distance
      // closeness (its largest weight), profile cost/m match, and reuse/shape — so
      // each candidate is penalised on the very axis it failed and the most rideable
      // degraded loop wins. No extra routing: the tracks are already generated.
      List<RoundTripCandidateResult> bestEffort = new ArrayList<>();
      for (RoundTripCandidateResult r : results) {
        if (r.track != null && r.gateVerdict != null
            && !ops.roundTripQualityHardReject(r.gateVerdict)) {
          bestEffort.add(r);
        }
      }
      winner = selectBestEffortCandidate(bestEffort, 2 * Math.PI * searchRadius,
        ops.routingContext().getProfileName(), direction);
      if (winner != null) {
        ops.logInfo("AUTO: no strictly-accepted route; adopting best-effort " + winner.algorithm
          + " (most rideable of " + bestEffort.size()
          + " degraded candidate(s)) with quality warning (lenient mode)");
      }
    }

    if (winner == null) {
      // All candidates failed. Surface the most recent (richest) error.
      String err = null;
      for (int i = results.size() - 1; i >= 0; i--) {
        if (results.get(i).errorMessage != null) { err = results.get(i).errorMessage; break; }
      }
      // Surface the best-geometry rejected candidate for post-mortem inspection,
      // mirroring the direct-dispatch reject paths. Candidates are in
      // algorithm-quality order, so the first with a track is the best
      // available rejected geometry.
      OsmTrack rejected = null;
      for (RoundTripCandidateResult r : results) {
        if (r.track != null) {
          rejected = r.track;
          break;
        }
      }
      orchestrator.rejectWithError("AUTO competition produced no acceptable route "
        + "(tried " + results.size() + " candidates in " + totalMs + "ms): "
        + (err == null ? "unknown" : err), rejected);
      return;
    }
    adoptCandidateWinner(winner, results, totalMs);
  }

  /**
   * Adopt the winning candidate's track as this engine's working result and
   * hand it to the orchestrator's shared finalization: the winner's
   * parent-computed gate verdict is stashed for the shared gate (which also
   * appends the lenient Warning for a best-effort winner — children run
   * undecorated, so it cannot already be present), the floors are marked as
   * child-enforced, and the output write is deferred until after decoration.
   * Only the AUTO competition summary is strategy-specific and appended here.
   */
  private void adoptCandidateWinner(RoundTripCandidateResult winner,
                                    List<RoundTripCandidateResult> all, long totalMs) {
    orchestrator.setTrack(winner.track);
    orchestrator.setError(null);
    orchestrator.cleanup.finalizeAdoptedRoundTripTrack(orchestrator.request.track, orchestrator.request.track == null ? null : orchestrator.request.track.getMatchedWaypoints());
    // Append a summary message so debugging consumers can see the
    // competition outcome. Score breakdown is in the route-choice verdict.
    StringBuilder summary = new StringBuilder(256);
    summary.append("AUTO selected ").append(winner.algorithm)
      .append(" (score ").append(String.format(Locale.US, "%.3f", winner.scoreValue()))
      .append(") after ").append(all.size()).append(" candidate(s) in ").append(totalMs).append("ms.");
    for (RoundTripCandidateResult r : all) {
      if (r == winner) continue;
      summary.append(" Also tried ").append(r.algorithm).append(": ")
        .append(r.accepted() ? String.format(Locale.US, "score %.3f", r.scoreValue())
                             : (r.errorMessage == null ? "no track" : "rejected"))
        .append('.');
    }
    orchestrator.appendRouteMessage(orchestrator.request.track, summary.toString());
    ops.logInfo(summary.toString());
    if (winner.score != null) {
      ops.logInfo("AUTO winner score breakdown:\n" + winner.score.describe());
    }
    // Shared-finalization handoff: consume the verdict runChildCandidate
    // already computed on this track (no third gate pass), honor the child's
    // keep-when-forced marker, skip the parent-placement floors (the child
    // enforced its own), and write output after decoration.
    orchestrator.request.boundedGateVerdict = winner.gateVerdict;
    orchestrator.request.forcedCorridorAccepted = winner.forcedCorridorAccepted();
    orchestrator.request.candidateFloorsEnforced = true;
    orchestrator.request.deferredOutputWrite = true;
  }

  /**
   * Rank degraded best-effort candidates, return the most rideable (or {@code null}
   * if none have a track). Uses {@link RouteChoiceScore#scoreBestEffort}, which
   * bypasses the scorer's accepted-only zero-guard (a rejected track is ranked on
   * real geometry, not collapsed to 0) but still applies the gate verdict's shape
   * penalty, so a rejected LOLLIPOP/OUT_AND_BACK cannot outrank a strict loop.
   * Ties keep {@code candidates} order (AUTO algorithm-quality order). Does no routing.
   */
  static RoundTripCandidateResult selectBestEffortCandidate(
      List<RoundTripCandidateResult> candidates, double expectedDistance,
      String profileName, double direction) {
    RoundTripCandidateResult best = null;
    double bestScore = -1.0;
    RouteChoiceScore.Verdict bestVerdict = null;
    for (RoundTripCandidateResult r : candidates) {
      if (r.track == null) {
        continue;
      }
      RouteChoiceScore.Verdict v = RouteChoiceScore.scoreBestEffort(
        r.track, expectedDistance, profileName, r.gateVerdict, direction);
      double s = v.score();
      if (s > bestScore) {
        bestScore = s;
        best = r;
        bestVerdict = v;
      }
    }
    // Surface the computed best-effort score on the winner so the adoption
    // summary logs the real value (and the score breakdown) instead of 0.000;
    // r.score is otherwise only set for strictly-accepted candidates.
    if (best != null && best.score == null) {
      best.score = bestVerdict;
    }
    return best;
  }

  /**
   * Run one AUTO candidate in an isolated child engine, score it, return the
   * wrapper. Never throws — failures land in the result's {@code errorMessage}.
   */
  private RoundTripCandidateResult runChildCandidate(RoundTripAlgorithm algo,
                                                     double searchRadius, double direction,
                                                     long deadline) {
    long t0 = System.currentTimeMillis();
    RoundTripCandidateResult r = new RoundTripCandidateResult(algo);
    try {
      RoutingContext childCtx = ops.routingContext().copyRequestFields();
      childCtx.roundTripAlgorithm = algo;
      childCtx.startDirection = (int) direction;
      // Children never decorate: the parent's shared finalization appends the
      // advisories/Warning to the adopted winner exactly once. Without this,
      // every advisory would appear twice on AUTO routes (child + parent).
      childCtx.roundTripSuppressDecoration = true;
      // Inherit the user's direction intent from copyRequestFields rather than
      // hard-forcing it. forceUseStartDirection makes the first leg leave on a
      // strict bearing; when the user supplied only a soft `direction` (or
      // none) that over-constrains the loop and can shove the opening leg onto
      // a profile-hostile stretch, failing a candidate that the same algorithm
      // accepts when free to pick a nearby bearing. Only an explicit `heading`
      // (which sets forceUseStartDirection on the parent) hard-forces here.
      // Copy waypoint list — child engine mutates its own list.
      List<OsmNodeNamed> childWps = new ArrayList<>(ops.waypoints().size());
      for (OsmNodeNamed wp : ops.waypoints()) {
        OsmNodeNamed copy = new OsmNodeNamed(new OsmNode(wp.ilon, wp.ilat));
        copy.name = wp.name;
        childWps.add(copy);
      }
      // Output suppressed (null outfileBase). Child runs its own pipeline
      // including post-routing checks + quality gate; we just inspect the
      // result.
      RoutingEngine child = new RoutingEngine(null, null, ops.segmentDir(), childWps, childCtx,
        RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
      child.quite = true;
      // The child plans with the parent's resolved effort (QUALITY's raised
      // top-K / plan budget must reach the planners it spawns).
      child.roundTripOps().setRoundTripEffortPolicy(orchestrator.request.effortPolicy);
      // Termination cascade: a server pre-emption terminates the PARENT; the
      // child checks its own kill flag per pop, so without this hook the
      // pre-empted request keeps burning its core until the child budget ends.
      ops.addTerminationHook(child::terminate);
      // Give the child only the remaining shared budget (floored so a spawned
      // candidate still gets a usable slice), not the full request timeout.
      long budget = childCandidateBudgetMs(deadline, System.currentTimeMillis());
      child.doRun(budget);
      r.track = child.getFoundTrack();
      r.errorMessage = child.getErrorMessage();
      r.runtimeMillis = System.currentTimeMillis() - t0;
      // Aggregate the child's expansion work into the parent so
      // getLinksProcessed() reports request-level totals (the perf budget
      // suite's work metric).
      ops.addLinksProcessed(child.getLinksProcessed());
      // All winner-attribution telemetry (incl. the keep-when-forced marker
      // the re-gate below honors) reads through this reference — no
      // field-by-field copy to forget when RoundTripResult grows.
      r.planner = child.getLastRoundTripResult();

      if (r.track != null) {
        // Child-verdict transport: the child already gated this exact track in
        // its own shared finalization and published the verdict (with its
        // stamped LoopAnalysis, which the scorer below reuses). Consuming it
        // makes the child's run the single gate evaluation per candidate.
        // ChildVerdictTransportEquivalenceTest pins field-level equality with
        // the re-gate this replaces; the re-gate remains only as the defensive
        // fallback for a child that shipped a track without publishing (which
        // no current path does).
        double expectedDist = 2 * Math.PI * searchRadius;
        String profileName = ops.routingContext().getProfileName();
        RoundTripQualityResult childVerdict = child.getLastRoundTripQuality();
        r.gateVerdict = childVerdict != null ? childVerdict
          : orchestrator.evaluateRoundTripGate(r.track, searchRadius, false,
              r.forcedCorridorAccepted());
        if (r.gateVerdict.isAccepted()) {
          r.score = RouteChoiceScore.score(r.track, expectedDist,
            profileName, r.gateVerdict, direction);
        }
      }
    } catch (RuntimeException e) {
      // Preserve the exception type: e.getMessage() is null for NPE/AIOOBE/CCE,
      // which otherwise surfaces an undiagnosable "threw: null" to the operator.
      // Also log the full stack trace on the parent (which, unlike the child, is
      // not `quite`) so a recurring child failure is diagnosable from logs — the
      // child suppressed its own logging via quite=true + null outfileBase.
      ops.logThrowable(e);
      r.errorMessage = "candidate " + algo + " threw: " + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage());
      r.runtimeMillis = System.currentTimeMillis() - t0;
    }
    return r;
  }

  /**
   * Floor (ms) under a spawned candidate's budget slice: a candidate the
   * competition DECIDED to run gets a usable slice even when the shared
   * deadline is (nearly) spent — a deliberate, bounded floor overrun of the
   * request budget, never a way to skip the candidate. Tests assert this
   * contract by name.
   */
  static final long MIN_CHILD_BUDGET_MS = 5_000;

  private static boolean isoGreedyAbsorbedGraphNativeTruth(RoundTripCandidateResult isoGreedyR) {
    // The child's explicit start-policy decision: a graph-native-only plan
    // already used the same candidate source as plain GREEDY, so a separate
    // GREEDY child would duplicate it. (This used to be inferred from three
    // telemetry sentinels — no iso legs + some non-iso legs + NaN health —
    // which any telemetry-semantics change could silently flip.)
    return isoGreedyR.algorithm == RoundTripAlgorithm.ISO_GREEDY
      && isoGreedyR.graphNativeOnlyStart();
  }
}
