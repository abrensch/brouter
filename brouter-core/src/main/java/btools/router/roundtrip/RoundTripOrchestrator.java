package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.util.CheapAngleMeter;

/**
 * Round-trip request orchestrator: the FAST &lt; BALANCED &lt; AUTO &lt; QUALITY
 * tier ladder, waypoint-based and explicit-via generation, greedy/bounded
 * planner dispatch, and the AUTO candidate competition over child engines.
 * Reaches the engine only through {@link RoundTripEngineOps}; child engines are
 * driven through their public API and their own ops seam.
 */
public final class RoundTripOrchestrator {

  final RoundTripEngineOps ops;

  /** The active request's mutable state; recreated at each doRoundTrip entry. */
  RoundTripRequest request;

  final WaypointSnapper snapper;
  final GeometricWaypointPlacer placer;
  final RoundTripTrackCleanup cleanup;

  /** One resolved rung of the tier ladder: which strategy runs, with which slice. */
  static final class Rung {
    final RoundTripStrategy strategy;
    final TierSlice slice;

    Rung(RoundTripStrategy strategy, TierSlice slice) {
      this.strategy = strategy;
      this.slice = slice;
    }
  }

  /** Waypoint-based tier: geometric/FAST placement, then one routing run. */
  private final RoundTripStrategy fastStrategy;

  /** Greedy plan-and-route tier (GREEDY / ISO_GREEDY). */
  private final RoundTripStrategy greedyStrategy;

  /** Bounded tier: budget-sliced greedy attempt with a waypoint fallback. */
  private final RoundTripStrategy boundedStrategy;

  /**
   * AUTO candidate competition over child engines. Children run with
   * decoration suppressed and the winner's gate verdict is stashed for the
   * shared finalization below, so AUTO outcomes flow through the same floors,
   * gate, and advisory pipeline as every other tier. QUALITY is this strategy
   * pinned to the MAX preset — configuration, not a separate implementation.
   */
  private final RoundTripStrategy autoCompetitionStrategy;

  /**
   * Resolve the tier ladder for this request context. QUALITY pins the
   * competition to the MAX preset; AUTO resolves an effort preset from context
   * and runs the competition — or, on constrained resources, the bounded tier,
   * and on fast-motorized profiles the waypoint tier; explicit BALANCED runs
   * bounded; GREEDY/ISO_GREEDY run the planner when the request supports it;
   * everything else (and every samewayback downgrade) runs the waypoint tier. Returns the rungs to attempt in order — currently
   * always exactly one; multi-rung fallback is the extension point.
   */
  List<Rung> resolveLadder(RoundTripAlgorithm algo, double searchRadius, double direction) {
    // Request context for the effort policy: profile class from the profile's
    // own validFor* globals (name-independent), coarse length class, and
    // resources. Logged once so future policy rules land on recorded evidence.
    RoundTripEffortPolicy.ProfileClass profileClass = classifyProfileClass();
    RoundTripEffortPolicy.LengthClass lengthClass =
      RoundTripEffortPolicy.classifyLength(2 * Math.PI * searchRadius);
    if (profileClass == RoundTripEffortPolicy.ProfileClass.FAST_MOTOR) {
      ops.logInfo("round trip: profile class FAST_MOTOR — loop quality is unvalidated for"
        + " fast-motorized profiles (car, motorbike); using bike-derived policies (provisional)");
    }

    boolean greedyCapable = greedySupports(ops.routingContext().allowSamewayback, ops.waypoints().size());

    // QUALITY: the full competition at max effort — both planners always run,
    // wider routed top-K, doubled plan budget. NOT an ISO_GREEDY alias: greedy
    // wins ~a quarter of competition cells.
    if (algo == RoundTripAlgorithm.QUALITY && greedyCapable) {
      ops.logInfo("round trip effort: " + RoundTripEffortPolicy.MAX_PRESET.rationale);
      return Collections.singletonList(new Rung(autoCompetitionStrategy,
        new TierSlice(algo, RoundTripEffortPolicy.MAX_PRESET, searchRadius, direction, true, "QUALITY")));
    }
    if (algo == RoundTripAlgorithm.QUALITY) {
      // The planners do not honor allowSamewayback. Name the tier in the log —
      // the silent rewrite below (QUALITY -> selectRoundTripAlgorithm ->
      // WAYPOINT) otherwise hides that the MAX effort request was downgraded.
      ops.logInfo("QUALITY round trip does not support allowSamewayback, falling back to waypoint algorithm");
    }

    // AUTO on a fast-motorized profile resolves straight to the waypoint tier.
    // Measured (Basel, car-vario, 50-180km): the isochrone candidate pools
    // starve on motor cost scales (the budget calibration cap assumes bike
    // cost-per-meter), both planners fail with "could not build any loop",
    // and the competition adopts its own WAYPOINT candidate at ~3x the wall
    // clock — same loop, seconds wasted. Explicit tier requests are untouched:
    // GREEDY/ISO_GREEDY/QUALITY/BALANCED remain the path for calibration work.
    if (algo == RoundTripAlgorithm.AUTO
        && profileClass == RoundTripEffortPolicy.ProfileClass.FAST_MOTOR) {
      ops.logInfo("round trip effort: AUTO resolved WAYPOINT tier — fast-motorized profile"
        + " (planner candidate pools are bike-calibrated and build no loops on motor"
        + " cost scales; request a planner tier explicitly to override)");
      return Collections.singletonList(new Rung(fastStrategy,
        new TierSlice(RoundTripAlgorithm.WAYPOINT, null, searchRadius, direction,
          greedyCapable, "AUTO(fastmotor)")));
    }

    // AUTO candidate competition, effort resolved from context. Constrained
    // resources (short request budget, memory-constrained device) resolve to
    // the BOUNDED preset — the bounded tier instead of the full competition,
    // with the same fall-through to the shared floors and quality gate (an
    // early return would ship ungated tracks that an identical explicit
    // BALANCED request rejects or returns with a Warning).
    if (algo == RoundTripAlgorithm.AUTO && greedyCapable) {
      RoundTripEffortPolicy resolved = RoundTripEffortPolicy.resolveAuto(
        profileClass, lengthClass, ops.routingContext().memoryclass, ops.maxRunningTime());
      ops.logInfo("round trip effort: " + resolved.rationale);
      if (resolved.preset != RoundTripEffortPolicy.Preset.BOUNDED) {
        return Collections.singletonList(new Rung(autoCompetitionStrategy,
          new TierSlice(algo, resolved, searchRadius, direction, true, "AUTO")));
      }
      return Collections.singletonList(new Rung(boundedStrategy,
        new TierSlice(algo, resolved, searchRadius, direction, true, "AUTO(bounded)")));
    }

    if (algo == RoundTripAlgorithm.AUTO || algo == RoundTripAlgorithm.QUALITY) {
      algo = selectRoundTripAlgorithm(searchRadius);
    }
    ops.logInfo("round trip algorithm: " + algo);

    if (algo == RoundTripAlgorithm.BALANCED) {
      // allowSamewayback is handled inside the bounded tier: the planner slice
      // is skipped, but the waypoint placement keeps the tier budget instead
      // of inheriting the full request budget.
      return Collections.singletonList(new Rung(boundedStrategy,
        new TierSlice(algo, RoundTripEffortPolicy.BOUNDED_PRESET, searchRadius, direction, greedyCapable, "BALANCED")));
    }
    if (algo == RoundTripAlgorithm.GREEDY || algo == RoundTripAlgorithm.ISO_GREEDY) {
      if (!greedyCapable) {
        // Greedy generates its own intermediate points and does not honor
        // allowSamewayback. (User vias are handled in explicit-via mode.)
        ops.logInfo("greedy round trip does not support allowSamewayback, falling back to waypoint algorithm");
        return Collections.singletonList(new Rung(fastStrategy,
          new TierSlice(RoundTripAlgorithm.WAYPOINT, null, searchRadius, direction, false, "WAYPOINT")));
      }
      // ISO_GREEDY: isochrone-derived candidate pool; falls back to plain
      // GREEDY internally if the candidate pool is insufficient.
      return Collections.singletonList(new Rung(greedyStrategy,
        new TierSlice(algo, null, searchRadius, direction, true, algo.toString())));
    }
    return Collections.singletonList(new Rung(fastStrategy,
      new TierSlice(algo, null, searchRadius, direction, greedyCapable, algo.toString())));
  }

  /** Record the gate-rejected track on the active request (post-mortem surface). */
  void setRejectedTrack(OsmTrack track) {
    request.lastRejectedTrack = track;
  }

  /**
   * Publish a rejection: set + log the error, keep {@code rejected} for
   * post-mortem inspection, and null the result track (track-XOR-error).
   */
  void rejectWithError(String message, OsmTrack rejected) {
    setError(message);
    ops.logInfo(message);
    setRejectedTrack(rejected);
    setTrack(null);
  }

  /** Set the request's working result track (published to the engine at request end). */
  void setTrack(OsmTrack track) {
    request.track = track;
  }

  /** Set the request's working error message (published to the engine at request end). */
  void setError(String error) {
    request.error = error;
  }

  /**
   * Run the engine routing pipeline and capture its outcome on the request.
   * The engine's result fields are seeded from the request first, so the run
   * sees exactly the state the individual field writes used to leave behind.
   */
  void doRoutingIntoRequest(long budgetMs) {
    ops.setFoundTrack(request.track);
    ops.setErrorMessage(request.error);
    RoutingOutcome outcome = ops.doRouting(budgetMs);
    request.track = outcome.track;
    request.error = outcome.error;
  }

  /** Record the planner-result telemetry on the active request. */
  void setPlannerResult(RoundTripResult result) {
    request.lastResult = result;
  }

  public RoundTripOrchestrator(RoundTripEngineOps ops) {
    this.ops = ops;
    this.snapper = new WaypointSnapper(ops, ops, ops);
    this.placer = new GeometricWaypointPlacer(ops);
    this.cleanup = new RoundTripTrackCleanup(snapper, ops, ops, ops);
    this.request = new RoundTripRequest(ops);
    // Constructed here, not in field initializers: the strategies capture
    // collaborators off this orchestrator, which must be fully wired first.
    this.fastStrategy = new FastStrategy(this);
    this.greedyStrategy = new GreedyStrategy(this);
    this.boundedStrategy = new BoundedStrategy(this, greedyStrategy, fastStrategy);
    this.autoCompetitionStrategy = new AutoCompetitionStrategy(this);
  }

  // A loop must enclose area: at least a triangle (start + 2 intermediate waypoints).
  // A single intermediate point is only an out-and-back, not a loop.
  private static final int MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS = 2;

  // A produced round-trip below either bound is a degenerate stub, not a loop.
  static final int MIN_ROUNDTRIP_LOOP_NODES = 6;

  static final int MIN_ROUNDTRIP_LOOP_METERS = 200;

  private static final int ROUNDTRIP_DEFAULT_DIRECTIONADD = 45;

  /**
   * Loops up to this length must work on the standard request budget; longer
   * loops require the caller to opt in with a raised timeout (gate in doRoundTrip).
   */
  static final double MAX_STANDARD_LOOP_METERS = 200_000;

  /** Minimum request budget accepted for loops above {@link #MAX_STANDARD_LOOP_METERS}. */
  static final long LONG_LOOP_MIN_BUDGET_MS = 120_000;

  private static final java.util.concurrent.atomic.AtomicLongArray PLACEMENT_PATH_COUNTS =
    new java.util.concurrent.atomic.AtomicLongArray(PlacementPath.values().length);

  /**
   * Append a space-separated line to {@code track.message} (advisories and gate
   * disclosures for the GPX/JSON formatters). No-op if either argument is null/empty.
   */
  static void appendRouteMessage(OsmTrack track, String message) {
    if (track == null || message == null || message.isEmpty()) return;
    if (track.message == null || track.message.isEmpty()) {
      track.message = message;
    } else {
      track.message += " " + message;
    }
  }

  /**
   * Profile family from the profile's own validFor* globals (name-independent):
   * validForBikes, validForFoot, or validForCars. A profile declaring none reads
   * UNKNOWN and keeps standard-effort behavior.
   */
  private RoundTripEffortPolicy.ProfileClass classifyProfileClass() {
    if (ops.routingContext() == null || ops.routingContext().expctxWay == null) {
      return RoundTripEffortPolicy.ProfileClass.UNKNOWN;
    }
    return RoundTripEffortPolicy.classifyProfile(
      ops.routingContext().expctxWay.getVariableValue("validForFoot", 0f) == 1f,
      ops.routingContext().expctxWay.getVariableValue("validForBikes", 0f) == 1f,
      ops.routingContext().expctxWay.getVariableValue("validForCars", 0f) == 1f);
  }

  /**
   * Uniform round-trip gate — the single source of truth for the gate flags,
   * shared by {@code doRoundTrip}'s verdict and the bounded tier's fallback so the
   * two can never drift. Explicit-via mode makes distance advisory (skeleton
   * defines the route) but still enforces beeline/closure/hostility. A forced
   * same-way-back corridor is accepted as a disclosed OUT_AND_BACK (keep-when-forced;
   * the planner sets the flag only when no clean alternative exists).
   */
  RoundTripQualityResult evaluateRoundTripGate(OsmTrack track, double searchRadius,
                                                boolean explicitViaMode) {
    return evaluateRoundTripGate(track, searchRadius, explicitViaMode,
      request.forcedCorridorAccepted);
  }

  public void doRoundTrip() {
    request = new RoundTripRequest(ops);
    request.effortPolicy = ops.roundTripEffortPolicy();
    request.routingBudgetMs = ops.roundTripRoutingBudgetMs();
    request.setRequestDeadline(ops.roundTripRequestDeadline());
    // Track/error seeds: the engine starts with an initial empty track and a
    // null error; early-return paths must publish exactly those back.
    request.track = ops.foundTrack();
    request.error = ops.errorMessage();
    try {
      long wallStart = System.currentTimeMillis();

      ops.routingContext().useDynamicDistance = true;
      // Classify the profile's surface policy once per request, from its cost
      // model (not its name), and store it on the request so the quality gate
      // and planner hostility checks use a consistent, name-independent verdict
      // for the rest of this request.
      request.pavedProfile = RoundTripQualityGate.classifyPavedProfile(ops.routingContext().expctxWay);
      double searchRadius;
      if (ops.routingContext().roundTripLength != null) {
        // roundTripLength is the desired total loop distance — convert to internal search radius.
        // The waypoint strategies place points at searchRadius from start and route between them,
        // so the loop traces roughly the circle circumference: total ≈ 2*PI * searchRadius.
        // Do NOT raise this factor toward L/2 (the out-and-back relation) thinking it gives a
        // "wider" loop: a closed loop traces the circumference, so a larger radius overshoots.
        // Measured across 4 real regions (urban/alpine/coastal/rural) for a 40km target, the
        // distance ratio climbs monotonically with the factor — L/2π≈0.91, 0.20→1.3, 0.25→1.6,
        // 0.33→2.1, L/2→3.2 — so L/2π is the calibrated optimum (closest to 1.0, best composite).
        searchRadius = ops.routingContext().roundTripLength / (2 * Math.PI);
      } else {
        // Defensive floor: a non-positive roundTripDistance (e.g. set directly on
        // the context, bypassing the param-layer guard) would otherwise become a
        // zero/negative searchRadius. That ships a wrong-scale loop with the
        // distance gate silently disabled — the ratio check is skipped when
        // expectedDistance (2*PI*searchRadius) <= 0 — so floor it to the default.
        searchRadius = (ops.routingContext().roundTripDistance == null
          || ops.routingContext().roundTripDistance <= 0) ? 1500 : ops.routingContext().roundTripDistance;
      }

      double direction = (ops.routingContext().startDirection == null ? -1 :ops.routingContext().startDirection);
      double directionAdd = (ops.routingContext().roundTripDirectionAdd == null ? ROUNDTRIP_DEFAULT_DIRECTIONADD :ops.routingContext().roundTripDirectionAdd);
      if (direction == -1) {
        direction = ops.getRandomDirectionFromData(ops.waypoints().get(0), searchRadius);
        direction += directionAdd;
      }
      // Normalize to a [0,360) compass bearing: ops.getRandomDirectionFromData()+directionAdd
      // can exceed 360 (e.g. 332+45=377), and a user-supplied startDirection may be out of
      // range, while downstream bearing comparisons assume a normalized value.
      direction = CheapAngleMeter.normalize(direction);

      // Explicit-via round-trip: when the caller supplied via points (any
      // waypoint beyond the start), treat those vias as a hard route
      // skeleton and bypass all generated-loop placement, regardless of
      // roundTripAlgorithm. User vias express stronger intent than any AUTO
      // heuristic, so they win. Generated rt* points are never added; the via
      // order is preserved exactly; distance settings become advisory.
      boolean explicitViaMode = ops.waypoints().size() > 1;
      if (explicitViaMode) {
        ops.logInfo("round trip: explicit-via mode (" + (ops.waypoints().size() - 1) + " user via points)");
        // Variety-seed disclosure: user vias are a hard skeleton expressing
        // stronger intent than any heuristic, so the alternativeidx seed is ignored.
        if (ops.routingContext().getRoundTripSeed() > 0) {
          ops.logInfo("alternativeidx has no effect in explicit-via round trips");
        }
        doExplicitViaRoundTrip(searchRadius, direction);
      } else {
        // Product sizing gate: the standard loop class is 40-100km and up to
        // 200km must work without special action; ABOVE 200km the caller must
        // explicitly ask for a longer calculation by raising the request
        // timeout (server: -DmaxRunningTime, embedders: doRun budget). A
        // default 60s budget cannot fund a good 200km+ loop, so failing fast
        // with instructions beats a guaranteed degraded result. Untimed
        // callers (budget <= 0, e.g. CLI) are already explicit and pass.
        double requestedLoopMeters = 2 * Math.PI * searchRadius;
        if (requestedLoopMeters > MAX_STANDARD_LOOP_METERS
            && ops.maxRunningTime() > 0 && ops.maxRunningTime() < LONG_LOOP_MIN_BUDGET_MS) {
          setError("round trips above " + (int) (MAX_STANDARD_LOOP_METERS / 1000)
            + "km need an explicitly increased calculation budget: requested "
            + Math.round(requestedLoopMeters / 1000.0) + "km with a "
            + (ops.maxRunningTime() / 1000) + "s timeout; raise maxRunningTime to at least "
            + (LONG_LOOP_MIN_BUDGET_MS / 1000) + "s");
          ops.logInfo(request.error);
          return;
        }
        RoundTripAlgorithm algo = ops.routingContext().roundTripAlgorithm;

        for (Rung rung : resolveLadder(algo, searchRadius, direction)) {
          rung.strategy.attempt(request, rung.slice);
          if (request.track != null || request.error != null) {
            break; // outcome decided — hand it to the shared floors + gate below
          }
        }
      }

      if (request.track == null && request.error != null) {
        return;
      }

      // A loop needs at least a triangle (start + 2 intermediate ops.waypoints()). With a single
      // intermediate the route is only an out-and-back, which closure/detour handling cannot
      // turn into a loop. Same-way-back is the deliberate exception (it IS an out-and-back).
      //
      // Explicit-via mode skips this check: a single user-supplied via is a valid
      // route skeleton (start → via1 → start), even though the result shape is
      // out-and-back. The user is expressing route intent, not a loop request.
      int intermediateWaypoints = (ops.matchedWaypoints() == null) ? 0 : ops.matchedWaypoints().size() - 2;
      if (!ops.routingContext().allowSamewayback && !explicitViaMode
          && !request.candidateFloorsEnforced
          && intermediateWaypoints < MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS) {
        setError("round-trip could not place enough waypoints to form a loop (need "
          + MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS + " intermediate, got " + Math.max(0, intermediateWaypoints)
          + ") for direction " + (int) direction + " at radius " + (int) searchRadius + "m");
        ops.logInfo(request.error);
        setTrack(null);
        return;
      }

      // Contract: a round-trip must yield an actual loop. When intermediate ops.waypoints()
      // cannot be placed on reachable roads (e.g. the requested direction has no roads
      // within this radius), routing collapses to a 1-3 node stub. Report that as a
      // failure rather than returning a non-loop as success.
      //
      // Explicit-via mode also bypasses the strict node/length floors: a short
      // one-via route may produce fewer than MIN_ROUNDTRIP_LOOP_NODES if the
      // via is right next to the start. We still reject null/no-track outcomes
      // below as a safety net.
      // candidateFloorsEnforced (AUTO adoption): the child engine already ran
      // these floors on its own placement; only the null-track safety net
      // applies to an adopted candidate.
      if (request.track == null || request.track.nodes == null
          || (!explicitViaMode && !request.candidateFloorsEnforced
              && (request.track.nodes.size() < MIN_ROUNDTRIP_LOOP_NODES
                                || request.track.distance < MIN_ROUNDTRIP_LOOP_METERS))) {
        int n = (request.track == null || request.track.nodes == null) ? 0 : request.track.nodes.size();
        int d = request.track == null ? 0 : request.track.distance;
        setError("round-trip could not form a loop for direction " + (int) direction
          + " at radius " + (int) searchRadius + "m (only " + n + " nodes, " + d
          + "m) — no reachable roads in that direction at this distance");
        ops.logInfo(request.error);
        setRejectedTrack(request.track); // preserve stub for post-mortem
        setTrack(null);
        return;
      }

      // Production-safety acceptance gate: applied uniformly across all
      // round-trip algorithms (WAYPOINT/ISOCHRONE/GREEDY/ISO_GREEDY) right
      // before returning success. The gate rejects unsafe routes (beeline
      // segments, broken closure, distance way off, profile-hostile surfaces,
      // accidental mid-route backtracking). Acceptance is shape-aware:
      // STRICT_LOOP/LOLLIPOP/OUT_AND_BACK each get explicit
      // disclosures so the cyclist knows what they're getting; only
      // INVALID_RETRACE is rejected. See {@link RoundTripQualityGate}.
      double expectedDistance = 2 * Math.PI * searchRadius;
      // Reuse the bounded tier's verdict when it evaluated this same track
      // (set only when the planner track survived its pre-gate; the fallback
      // path leaves it null). Consumed once.
      RoundTripQualityResult quality = request.boundedGateVerdict != null
        ? request.boundedGateVerdict
        : evaluateRoundTripGate(request.track, searchRadius, explicitViaMode);
      request.boundedGateVerdict = null;
      // Record the request's final verdict for the end-of-request publication
      // (getLastRoundTripQuality) — set for accept, lenient-keep, AND the
      // hard-reject below (where it describes the lastRejectedTrack).
      request.qualityVerdict = quality;
      // Child engines of the AUTO competition skip ALL user-facing track
      // decoration below (advisories, lenient Warning, info-message sync):
      // the parent decorates the adopted winner exactly once. Gate policy
      // (accept / hard-reject / lenient-keep) is unaffected.
      boolean suppressDecoration = ops.routingContext().roundTripSuppressDecoration;
      if (!quality.isAccepted()) {
        // STRUCTURAL failures (broken / un-routable / not-a-loop) are always
        // hard-rejected — there is nothing usable to offer. QUALITY failures
        // (distance off-target, self-crossing/hairpin chaos, hostile surface,
        // mid-route backtracking) are advisory by default: the route is
        // rideable, so we return it with a Warning and let the user decide.
        // roundTripStrictQuality=1 restores the old hard-reject behaviour.
        boolean hardReject = ops.roundTripQualityHardReject(quality);
        if (hardReject) {
          setError("round-trip rejected by quality gate (direction " + (int) direction
            + ", radius " + (int) searchRadius + "m, shape=" + quality.getShape() + "): "
            + quality.getRejectionReason());
          ops.logInfo(request.error);
          setRejectedTrack(request.track);
          setTrack(null);
          return;
        }
        // Lenient default: surface the quality issue as a warning and keep the
        // route. The planner already searched strictly and shipped its best
        // effort; we disclose the problem rather than discard a rideable loop.
        String advisory = "Warning: " + quality.getRejectionReason()
          + " (shape=" + quality.getShape() + ") — route returned anyway; ride at your"
          + " discretion, or set roundTripStrictQuality=1 to reject it.";
        ops.logInfo("round-trip quality advisory (lenient): " + advisory);
        if (!suppressDecoration) {
          appendRouteMessage(request.track, advisory);
        }
        // fall through to disclosure surfacing + success
      }
      // Surface the route shape + disclosures (e.g. "contains retraced
      // scenic spur: 4.2km") so the cyclist isn't surprised to find
      // they're returning the same way along a stretch. Stays in the
      // route message stream so it propagates to GPX/JSON exports.
      ops.logInfo("round-trip quality: " + quality);
      if (!suppressDecoration) {
        for (String d : quality.getDisclosures()) {
          appendRouteMessage(request.track, d);
        }
      }

      // Transparency for the silent band: 1..MAX crossings and guard-blocked
      // spurs pass the gate without any message, yet the cyclist sees them on
      // the map. Disclose every nonzero count — informational only, the route
      // ships either way (lenient product policy: odd-but-cycleable > nothing).
      // The whole decoration block runs under its own guard: the loop is
      // complete and gate-accepted at this point, and the outer catch nulls
      // request.track — an exception in a cosmetic advisory must never destroy
      // a rideable result. Suppressed entirely for AUTO child engines (the
      // parent decorates the adopted winner once).
      if (!suppressDecoration) {
        try {
          // Reuse the gate's own count for this track instead of re-scanning it
          // a third time (already computed once in evaluateRoundTripGate above,
          // once again inside RouteChoiceScore for AUTO candidates).
          int shippedCrossings = quality.getSelfIntersections() >= 0
            ? quality.getSelfIntersections()
            : RoundTripQualityGate.countSelfIntersections(request.track);
          if (shippedCrossings > 0) {
            appendRouteMessage(request.track, String.format(Locale.US,
              "Note: route crosses its own path %d time%s.",
              shippedCrossings, shippedCrossings == 1 ? "" : "s"));
          }
          if (request.track.nodes != null) {
            int[] spurInfo = LoopQualityMetrics.computeSpurInfo(request.track.nodes);
            if (spurInfo[0] > 0 && spurInfo[1] > 600) {
              appendRouteMessage(request.track, String.format(Locale.US,
                "Note: route contains %d out-and-back section%s (longest %.1fkm).",
                spurInfo[0], spurInfo[0] == 1 ? "" : "s", spurInfo[1] / 1000.0));
            }
          }

          // Residual-chord advisory (loop-review backlog item 1): the planner's
          // fidelity enforcement retries chord legs, but a best-effort adoption or
          // a non-greedy path can still ship a long null-tag edge that renders as
          // a straight line cutting across terrain. Ground truth (Lozère study):
          // these follow a real curving road whose detail is missing, so the route
          // is rideable — disclose, don't reject. Same threshold as the planner's
          // fidelity check so the two mechanisms never disagree about what a
          // chord is.
          int chordMeters = LoopQualityMetrics.maxSingleNullEdgeMeters(request.track);
          if (chordMeters > GreedyRoundTripPlanner.MAX_UNDETAILED_EDGE_METERS) {
            appendRouteMessage(request.track, String.format(Locale.US,
              "Note: route contains an undetailed straight-line section of ~%dm "
                + "(way detail missing in the map data; the actual road may curve).",
              chordMeters));
          }

          // Soft advisory: even within the [0.5, 1.8] ratio band, a >1.5
          // overshoot is worth flagging so the caller can suggest a shorter
          // distance. This stays informational because the hard gate above
          // already rejects ratios outside the safe range.
          if (request.track.distance > 0) {
            double ratio = request.track.distance / expectedDistance;
            if (ratio > 1.5) {
              String warning = String.format(
                "Warning: route distance (%dkm) exceeds requested loop distance (%dkm) by %.0f%%. "
                + "The road network in this area is too constrained for a compact loop at this distance. "
                + "Consider a shorter distance or an out-and-back route.",
                request.track.distance / 1000, (int) (expectedDistance / 1000), (ratio - 1) * 100);
              ops.logInfo(warning);
              appendRouteMessage(request.track, warning);
            }
          }

          // The advisory/disclosures above were appended to request.track.message, but
          // FormatGpx emits <brouter:info> and its message comments from
          // messageList, not message. Sync messageList[0] so the quality warning
          // actually reaches GPX/JSON consumers. Idempotent; covers every tier
          // including the AUTO adoption (whose summary was appended by the
          // strategy before this shared finalization ran).
          cleanup.ensureInfoMessage(request.track);
        } catch (RuntimeException advisoryFailure) {
          ops.logInfo("round-trip advisory decoration failed ("
            + advisoryFailure.getClass().getSimpleName()
            + "); returning the track without advisories");
          ops.logThrowable(advisoryFailure);
        }
      }

      // Deferred single output write (AUTO adoption): children ran with output
      // suppressed, and the write must happen AFTER the decoration above so
      // the file carries the advisories the direct-dispatch write gets from
      // doRouting's own flow.
      if (request.deferredOutputWrite && request.track != null) {
        ops.writeAdoptedTrackOutput(request.track);
      }

      long endTime = System.currentTimeMillis();
      ops.logInfo("round trip execution time = " + (endTime - wallStart) / 1000. + " seconds");
    } catch (Exception e) {
      ops.logException(e);
      ops.logThrowable(e);
      // logException publishes the exception text on the ENGINE's error field;
      // mirror it into the request, which is the working copy the finally
      // publishes.
      setError(ops.errorMessage());
      // Contract: a round trip ends with a usable track XOR a clean error. An
      // exception can land here before any assignment, leaving request.track as
      // the constructor's initial empty OsmTrack (or a partial one) — and
      // logException copies e.getMessage(), which is null for message-less
      // exceptions. Guarantee both halves of the contract: a non-empty error
      // and no degenerate "success" track. Non-empty geometry is preserved on
      // request.lastRejectedTrack for post-mortem inspection like other reject paths.
      if (request.error == null || request.error.isEmpty()) {
        setError("round trip failed: " + e.getClass().getSimpleName());
      }
      if (request.track != null && request.track.nodes != null && !request.track.nodes.isEmpty()) {
        request.lastRejectedTrack = request.track;
      }
      setTrack(null);
    } finally {
      // Track XOR error, enforced for EVERY path: the catch above covers
      // exceptions, but early-return rejects (missing start tile, oversized
      // loop without budget) set only the error and would otherwise publish
      // the request's seeded initial empty track alongside it. Non-empty
      // geometry moves to lastRejectedTrack like the other reject paths.
      if (request.error != null && request.track != null) {
        if (request.track.nodes != null && !request.track.nodes.isEmpty()
            && request.lastRejectedTrack == null) {
          request.lastRejectedTrack = request.track;
        }
        request.track = null;
      }
      // Final result + telemetry publication: the engine's public getters
      // (getFoundTrack/getErrorMessage/getLastRejectedTrack/
      // getLastRoundTripResult) serve these after the request; nothing
      // engine-side reads them mid-request.
      ops.setFoundTrack(request.track);
      ops.setErrorMessage(request.error);
      ops.setLastRejectedTrack(request.lastRejectedTrack);
      ops.setLastRoundTripResult(request.lastResult);
      ops.setLastRoundTripQuality(request.qualityVerdict);
      ops.cleanupRoutingResources();
    }

  }

  /**
   * Explicit-via round-trip: route through the caller's via points exactly, in
   * input order, with no generated {@code rt*} waypoints.
   *
   * <p>Skeleton:
   * <ul>
   *   <li>{@code allowSamewayback=false}: {@code start → via1 → ... → viaN → start}</li>
   *   <li>{@code allowSamewayback=true}: forward chain only; {@code doRouting} mirrors it back.</li>
   * </ul>
   *
   * <p>{@code roundTripPoints} is ignored; {@code roundTripDistance}/{@code roundTripLength}
   * and {@code startDirection} are advisory (distance-ratio mismatch becomes a
   * disclosure, not a rejection; direction does not reorder vias). A via that
   * cannot be snapped within range fails with an error naming it — user vias are
   * hard constraints, never silently dropped (no-beeline invariant).
   *
   * @param searchRadius sizes the snap tolerance; also logged
   * @param direction    logged only; does not reorder vias
   */
  private void doExplicitViaRoundTrip(double searchRadius, double direction) {
    OsmNodeNamed start = ops.waypoints().get(0);
    List<OsmNodeNamed> userVias = new ArrayList<>(ops.waypoints().subList(1, ops.waypoints().size()));
    ops.waypoints().subList(1, ops.waypoints().size()).clear();
    // Default-name only blanks; preserve any user-supplied via names so that
    // diagnostic output references the user's identifiers.
    for (int i = 0; i < userVias.size(); i++) {
      OsmNodeNamed v = userVias.get(i);
      if (v.name == null || v.name.isEmpty()) {
        v.name = "via" + (i + 1);
      }
    }

    // Snap start and every user via. Failure on a user via is fatal and
    // names the via — explicit vias are hard constraints, never dropped.
    // Note: `snapper.snapStartToRoad(ops.waypoints(), ...)` short-circuits when
    // ops.waypoints().size() < 2, so we snap the start directly via the
    // single-waypoint helper to avoid that early-return.
    double userSnapDist = Math.min(searchRadius * 0.3, 2000);
    snapper.snapStartProfileAware(start, userSnapDist);
    List<Boolean> matched = snapper.snapWaypointsToRoad(userVias, userSnapDist, "snapUserVia");
    for (int i = 0; i < userVias.size(); i++) {
      if (!matched.get(i)) {
        throw new IllegalArgumentException("user waypoint " + userVias.get(i).name
          + " has no road within " + (int) userSnapDist + "m");
      }
    }
    // Anchor cycle [start, via1, ..., viaN]: the user-via skeleton, order preserved.
    List<OsmNodeNamed> anchors = new ArrayList<>();
    anchors.add(start);
    anchors.addAll(userVias);

    ops.waypoints().clear();
    ops.waypoints().addAll(anchors);

    // For allowSamewayback=false append the closing start copy so the route
    // forms a closed loop. For allowSamewayback=true the existing doRouting
    // expansion at the top of RoutingEngine#doRouting mirrors the chain back —
    // we must NOT add a closing copy here or we'd double-close.
    if (!ops.routingContext().allowSamewayback) {
      OsmNodeNamed closing = new OsmNodeNamed(new OsmNode(start.ilon, start.ilat));
      closing.name = "to";
      ops.waypoints().add(closing);
    }

    ops.routingContext().waypointCatchingRange = 250;
    request.setSearchRadius(searchRadius);
    request.setExplicitVia(true);
    ops.logInfo("explicit-via round-trip: " + userVias.size() + " user via(s), "
      + "allowSamewayback=" + ops.routingContext().allowSamewayback
      + ", direction=" + (int) direction + " (advisory only)");
    doRoutingIntoRequest(request.routingBudgetMs);
  }

  public static RoundTripAlgorithm selectRoundTripAlgorithm(double searchRadius) {
    // Cheap fallback selector. The full AUTO policy lives in
    // {@link AutoCompetitionStrategy}; this helper remains as a stable
    // entry point for direct callers and unsupported AUTO modes.
    return RoundTripAlgorithm.GREEDY;
  }

  /**
   * Whether greedy planning applies: it generates its own intermediate waypoints,
   * so user vias and allowSamewayback are not honored.
   */
  public static boolean greedySupports(boolean allowSamewayback, int waypointCount) {
    return !allowSamewayback && waypointCount <= 1;
  }

  /**
   * One bounded tier slice: the tier budget clamped to the remaining request
   * budget, floored at {@link #MIN_LADDER_RUNG_BUDGET_MS} so a nearly-spent
   * request still funds ONE run (deliberate bounded overrun, not a guaranteed
   * instant timeout). An untimed request (deadline 0) gets the full tier budget.
   */
  static long tierSliceMs(long tierBudgetMs, long requestDeadline, long now) {
    return Math.min(tierBudgetMs, requestDeadline == 0 ? tierBudgetMs
      : Math.max(requestDeadline - now, MIN_LADDER_RUNG_BUDGET_MS));
  }

  // --- Placement-path instrumentation (diagnostic only) -------------------
  // Monotonic process-wide counters recording which waypoint-placement path
  // each round-trip leg used. Purely additive: NO routing logic reads these.
  // They exist to measure how often the terrain-unaware ENVELOPE path is taken
  // (esp. ENVELOPE_ISO_FALLBACK, the only envelope case where an indirectness
  // compensation could be derived) so the P5 envelope-compensation work can be
  // prioritised and validated against the loop-quality corpus. AUTO runs its
  // candidates in `quite` child engines whose logInfo is suppressed, so a
  // static counter — not per-call logging — is what survives a corpus run.
  // Aggregate with placementPathCounts(); reset between corpus cases with
  // resetPlacementPathCounts().
  enum PlacementPath { ISOCHRONE, ENVELOPE_ISO_FALLBACK, ENVELOPE_FAST, CIRCLE }

  void recordPlacementPath(PlacementPath path) {
    PLACEMENT_PATH_COUNTS.incrementAndGet(path.ordinal());
    ops.logInfo("roundtrip placement path: " + path); // no-op for quite child engines
  }

  /** Snapshot of placement-path counts, indexed by {@link PlacementPath#ordinal()}. */
  public static long[] placementPathCounts() {
    long[] out = new long[PLACEMENT_PATH_COUNTS.length()];
    for (int i = 0; i < out.length; i++) out[i] = PLACEMENT_PATH_COUNTS.get(i);
    return out;
  }

  /** Reset the placement-path counters (for test/corpus isolation). */
  public static void resetPlacementPathCounts() {
    for (int i = 0; i < PLACEMENT_PATH_COUNTS.length(); i++) PLACEMENT_PATH_COUNTS.set(i, 0L);
  }

  /**
   * Minimum remaining request budget worth starting another subRouteCount rung,
   * Phase-2.1 retry, or ISO_GREEDY→GREEDY recursion — below this a fresh plan()
   * could not route even a couple of legs, so leave the time to the fallback.
   */
  static final long MIN_LADDER_RUNG_BUDGET_MS = 3_000;

  /**
   * Clear-accept threshold: below this, AUTO normally runs the plain GREEDY
   * candidate as a comparison before the legacy WAYPOINT fallback. ISO_GREEDY's
   * own internal graph-native fallback (see {@link IsoPoolHealth}) makes that
   * comparison win less over time; it is retained until winner-attribution
   * evidence proves it unneeded — check the ISO_GREEDY candidate's
   * {@code quotaAccepted}/{@code poolHealth}/{@code demotedAtStep} suffix
   * ({@link RoundTripCandidateResult#toString}) before removing it.
   */
  static final double CLEAR_ACCEPT_THRESHOLD = 0.85;

  /** Overload for verdicts on a CANDIDATE's track, whose forced-corridor
   *  marker lives on the candidate rather than the engine field. */
  RoundTripQualityResult evaluateRoundTripGate(OsmTrack track, double searchRadius,
                                                boolean explicitViaMode,
                                                boolean forcedCorridorAccepted) {
    boolean allowSamewayback = ops.routingContext().allowSamewayback || forcedCorridorAccepted;
    return RoundTripQualityGate.evaluate(track, 2 * Math.PI * searchRadius,
      request.pavedProfile, allowSamewayback, explicitViaMode,
      ops.roundTripFerriesAllowed());
  }

}
