package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.*;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

import java.util.*;

/**
 * Greedy routed-leg planner for cycling round-trips. Builds a loop one leg at a
 * time, walking a chain of vias outward from the start and back;
 * {@link GreedyStrategy} constructs it, calls {@link #plan}, and
 * adopts the loop (or falls through to WAYPOINT when the fallback reason starts
 * with "rejected"). Follows CEUR-WS Vol-3885.
 * <p>
 * Per step (= one leg): a {@link RoundTripCandidateProvider} generates vias near
 * the target sub-distance; ALL are scored by O(1) air-distance heuristics
 * ({@link CandidateScorer} plus the placement terms below); a small top-K with
 * angular spread ({@link #pickDiverseTopK}) is routed with full Dijkstra and
 * re-scored on routed distance, edge reuse, and cost; the best is committed (on
 * failure the radius shrinks and retries); then ONE return path checks closure
 * within {@code tolerance}. Routing only the top-K per step, not every candidate,
 * is what keeps it real-time.
 * <p>
 * The per-step scorer is biased toward a clean loop SHAPE by three placement terms
 * on top of {@link CandidateScorer#score}: heading persistence
 * ({@link #headingPersistencePenalty}), angular-sweep convexity
 * ({@link #loopSweepPenalty}), and unimodal radius ({@link #unimodalRadiusPenalty}).
 * All fade with terrain freedom ({@link #headingTerrainFreedom}) and switch off
 * after repeated closure rejections, so constrained (coastal/valley) loops that
 * cannot sweep a full circle stay feasible.
 * <p>
 * In ISO_GREEDY mode the planner also tracks the start-pool's trustworthiness via
 * {@link IsoPoolHealth}. A DEGRADED pool loses its prior-based scoring
 * terms and cedes an extra routed slot to graph-native candidates; an UNHEALTHY
 * pool switches to graph-native-only steps (the internal plain-GREEDY fallback).
 * Every accepted leg records a source-attribution diagnostic ("leg N source: …").
 */
public class GreedyRoundTripPlanner {

  private static final int DEFAULT_SUB_ROUTE_COUNT = 5;
  private static final double DEFAULT_TOLERANCE = 0.05;
  private static final int DEFAULT_MAX_ATTEMPTS = 8;
  private static final double ROAD_INDIRECTNESS = 1.3;
  /**
   * Upper clamp on the adaptive air-to-road factor. The flat 1.3 baseline
   * under-modeled an Elz-valley leg that routed at 2.0× air distance,
   * over-extending the loop into a zigzag closure (freiburg_100km_fastbike_N).
   * The planner learns a per-plan estimate from each routed leg (EMA, alpha
   * {@link #INDIRECTNESS_EMA_ALPHA}), clamped to [{@link #ROAD_INDIRECTNESS}, this]
   * — it can only grow more conservative, never more optimistic, so flat terrain
   * is unchanged.
   */
  private static final double MAX_INDIRECTNESS_EST = 2.5;
  private static final double INDIRECTNESS_EMA_ALPHA = 0.5;

  /**
   * Heading-persistence weight. A smooth loop turns ~360°/subRouteCount per step;
   * a candidate kinking beyond that quota (plus {@link #HEADING_QUOTA_SLACK}) pays
   * this × the normalized excess, rounding corners into arcs and blocking the
   * sharp reversals that precede self-crossing zigzags (a heading-monotone loop
   * cannot self-intersect). Soft by design — terrain may force a sharp bend, so it
   * only tilts near-ties. A full 180° reversal at slack-quota 90° costs 0.5 × weight.
   */
  private static final double W_HEADING_PERSISTENCE = 1.0;
  /** Slack factor on the per-step heading quota (1.5 → 90° allowed at 6 steps). */
  private static final double HEADING_QUOTA_SLACK = 1.5;
  /**
   * Indirectness at which the heading term fully fades to zero. At full weight,
   * constrained coastal/mountain cells blew up (coastal_nice_100km_gravel: 0→43
   * crossings — terrain forces sharp macro-turns there) while open networks
   * improved (spurs −30%, lassos −25%). The adaptive indirectness estimate is the
   * terrain-freedom signal: ~1.3 open, →2.0+ where the graph forces indirect
   * roads. Weight fades linearly from full at the baseline to zero here.
   */
  private static final double HEADING_TERRAIN_FADE_MAX = 2.0;
  /**
   * After this many closed-loop rejections in one plan, the heading term is
   * disabled for the rest: the planner is struggling to close and shape
   * preferences must yield to feasibility.
   */
  static final int HEADING_BRAKE_REJECTIONS = 2;

  /**
   * Terrain-freedom factor in [0,1] for the heading term: 1 at the indirectness
   * baseline (open network), 0 at {@link #HEADING_TERRAIN_FADE_MAX} (terrain
   * dictates headings).
   */
  static double headingTerrainFreedom(double indirectnessEst) {
    double f = (HEADING_TERRAIN_FADE_MAX - indirectnessEst)
      / (HEADING_TERRAIN_FADE_MAX - ROAD_INDIRECTNESS);
    return Math.max(0.0, Math.min(1.0, f));
  }

  /**
   * Normalized penalty for a candidate bearing kinking beyond the smooth-loop
   * quota vs the previous leg's bearing: 0 within quota, up to (180 − quota)/180
   * for a full reversal.
   */
  static double headingPersistencePenalty(double prevLegBearing, double candidateBearing,
                                          int subRouteCount) {
    double quota = HEADING_QUOTA_SLACK * 360.0 / Math.max(1, subRouteCount);
    double delta = CheapAngleMeter.getDifferenceFromDirection(prevLegBearing, candidateBearing);
    return Math.max(0, delta - quota) / 180.0;
  }

  // ---- Loop-convexity terms (root-cause fix for via-placement lobes) --------
  // A clean round trip sweeps monotonically around the START by ~360/subRouteCount
  // degrees per step, with a UNIMODAL distance-from-start (rise to apogee, fall to
  // 0). The greedy per-step scorer (spreadPenalty + heading persistence) does not
  // enforce either: heading persistence is about consecutive LEG bearings, not the
  // angle swept around the start. Diagnosed on basel_80km_gravel_E, where via3
  // landed at the SAME bearing-from-start as via2 (no angular progress) while its
  // radius collapsed 18km→8km, then via4 climbed back to 9km — the radial dent IS
  // the Lörrach lobe. These two terms make the loop convex by construction.
  // Both fade with terrain freedom and are braked with closures, exactly like the
  // heading-persistence term, so constrained/half-plane (coastal, valley) loops
  // that cannot sweep a full circle are exempt. Weights are tunable for sweeps.
  static final double W_LOOP_SWEEP =
    4.0;
  static final double W_UNIMODAL_RADIUS =
    3.0;

  /** Signed angular delta from→to in (-180,180]. */
  static double signedAngleDelta(double from, double to) {
    return (to - from + 540.0) % 360.0 - 180.0;
  }

  /**
   * Penalty for a candidate via that fails to advance the loop's angular sweep
   * around the start. The prior sweep (prevPrev→current, as bearings FROM START)
   * sets the rotation sense; the candidate's increment should match
   * {@code ±360/subRouteCount}. A stall or backtrack (the lobe signature) scores
   * high. Returns 0 until rotation is established (prevPrev clear of the start,
   * prior sweep non-trivial). Capped.
   */
  static double loopSweepPenalty(int sLon, int sLat, int ppLon, int ppLat,
                                 int curLon, int curLat, int cpLon, int cpLat,
                                 int subRouteCount) {
    if (CheapRuler.distance(sLon, sLat, ppLon, ppLat) < 500) return 0; // prevPrev ≈ start
    // cos(lat)-scaled bearings: the increments are judged against the TRUE
    // angular target 360/subRouteCount, and raw integer angles are non-linear
    // in true angle (an even true sweep reads uneven raw increments — ±0.3
    // penalty units at 50°N depending on where the loop sits). Mutually-raw
    // angles are NOT self-consistent here because the target is true-geometry.
    double aPP = CheapRuler.getScaledBearing(sLon, sLat, ppLon, ppLat);
    double aP = CheapRuler.getScaledBearing(sLon, sLat, curLon, curLat);
    double established = signedAngleDelta(aPP, aP);
    if (Math.abs(established) < 5.0) return 0; // rotation not clearly established
    double rot = Math.signum(established);
    double target = rot * (360.0 / Math.max(2, subRouteCount));
    double aC = CheapRuler.getScaledBearing(sLon, sLat, cpLon, cpLat);
    double inc = signedAngleDelta(aP, aC);
    double dev = (inc - target) / Math.abs(target);
    return Math.min(4.0, dev * dev);
  }

  /**
   * Penalty for distance-from-start growing past the loop's apogee (phase ≥ 0.5):
   * a unimodal loop only contracts after the midpoint, so a candidate radius
   * exceeding the previous via's is climbing back out. 0 before apogee or when
   * contracting. Capped.
   */
  static double unimodalRadiusPenalty(double candRadius, double prevRadius,
                                      int step, int subRouteCount) {
    double phase = (double) step / Math.max(1, subRouteCount);
    if (phase < 0.5 || prevRadius <= 0 || candRadius <= prevRadius) return 0;
    double growth = (candRadius - prevRadius) / prevRadius;
    return Math.min(4.0, growth * growth);
  }

  private static final long SUB_ROUTE_TIMEOUT_MS = 10000;
  /**
   * Salvage margin (ms) past the request deadline for the force-close leg only: a
   * nearly-complete loop is worth a bounded overrun.
   */
  private static final long FORCE_CLOSE_GRACE_PAST_BUDGET_MS = 2000;
  /**
   * Per-Dijkstra budget = base + per-air-km, capped at {@link #SUB_ROUTE_TIMEOUT_MS}.
   * A flat 10s cap let two pathological searches eat 2/3 of a 30s plan; with
   * goal-directed legs ({@link #timedFindTrack}) a healthy leg finishes well below
   * its scaled cap, so a stuck search is cut in proportion to its need.
   */
  private static final long FIND_TRACK_BASE_BUDGET_MS = 2000;
  private static final long FIND_TRACK_BUDGET_MS_PER_AIR_KM = 700;
  /**
   * Whole-plan wall-clock ceiling (ms). Worst-case per-sub-route timing blows past
   * 20 minutes; this is the safety net. Each {@link #timedFindTrack} caps at
   * min(SUB_ROUTE_TIMEOUT_MS, deadline − now) so no new Dijkstras start past the
   * deadline.
   */
  private static final long DEFAULT_PLAN_DEADLINE_MS = 30_000;
  /** Loop length at which the plan budget is exactly {@link #DEFAULT_PLAN_DEADLINE_MS}. */
  private static final double PLAN_BUDGET_REFERENCE_DISTANCE_M = 100_000;
  /** Budget scale ceiling: a 200km+ loop gets at most 2x the reference budget. */
  private static final double PLAN_BUDGET_MAX_SCALE = 2.0;
  /**
   * Fraction of the plan budget reserved for the late closure steps: early steps
   * stop at the early deadline, so a plan that burned its budget on outbound legs
   * still has search time to close.
   */
  private static final double CLOSURE_RESERVE_FRACTION = 0.25;
  /** Minimum per-Dijkstra timeout. Below this it's cheaper to skip than try. */
  private static final long MIN_FIND_TRACK_MS = 250;
  /**
   * Radius backoff factors: gentle for "no routable candidate at this radius"
   * (don't skip viable nearby radii), aggressive for "route too long" (radius must
   * come down). Both clamp at {@link #MIN_LOCAL_RADIUS_M}.
   */
  private static final double BACKOFF_FACTOR_NO_CANDIDATE = 0.8;
  private static final double BACKOFF_FACTOR_TOO_LONG = 0.5;
  private static final double MIN_LOCAL_RADIUS_M = 200;

  /**
   * Fallback-reason prefix marking a rejected low-quality loop (wrong length,
   * &gt;half retrace, or non-closing). RoutingEngine.doGreedyRoundTrip treats a
   * "rejected" reason as planner failure and falls through to WAYPOINT.
   */
  public static final String DEGRADED_FALLBACK_PREFIX = "rejected: ";
  // Max candidates to route per step (heuristic top-K, with angular spread).
  private static final int MAX_ROUTE_ATTEMPTS = 3;
  /** Raised cap on late steps or after a failed attempt, where extra exploration pays off. */
  private static final int MAX_ROUTE_ATTEMPTS_LATE = 5;
  /**
   * Min angular separation between routed candidates in a step. Top-K by raw score
   * is often spatially redundant in dense networks; a 30° gap gives diverse routed
   * options instead of three picks in one micro-direction.
   */
  private static final double MIN_ANGULAR_SEPARATION_DEG = 30.0;
  // Weight applied to cost-per-meter when picking among routed candidates.
  // Magnitude is similar to scorer.score() output; 0.5 keeps both signals relevant.
  static final double COST_PER_METER_WEIGHT = 0.5;
  /**
   * Weight per self-intersection a tentative partial loop introduces: among
   * similar routed candidates, prefer the one keeping loop geometry clean before
   * the final gate sees the completed route.
   */
  // Phase 2.2 chaos-avoidance tuning. Raised from 0.3 → 1.0 per the
  // directive "zick zack and chaos routing must be avoided" — at 0.3
  // a candidate with 1 tentative crossing got a +0.3 score bump, which
  // got dominated by other terms; at 1.0 even one crossing pushes the
  // candidate substantially down the ranking. The 880-scenario corpus
  // measurement validates this is empirically the right magnitude:
  // weight=2.0 was measured but OVER-penalizes — it forces the planner
  // to pick candidates with 0 tentative crossings whose closed loops
  // chaos-out via different geometry, raising chaotic-loop count by
  // +11 vs weight=1.0 (production chaotic 40 → 51).
  static final double PARTIAL_SELF_INTERSECTION_WEIGHT = 1.0;
  // Multiplier applied to the return estimate when deciding whether to skip
  // the return Dijkstra. > 1 means we skip less aggressively.
  private static final double RETURN_SKIP_SAFETY = 1.5;
  /**
   * Skip-safety when the return estimate is oracle-backed: the sector-resolved
   * estimate already carries the graph's detour factor (what the 1.5 fudge
   * covered), so only snap/one-way/anti-reuse slack remains.
   */
  private static final double RETURN_SKIP_SAFETY_ORACLE = 1.15;
  /**
   * When the profile-aware snap relocates a via further than this from the
   * original graph-native node, the pre-routed leg (which ends at the original
   * node) is discarded and re-routed. Below this the cached leg still reaches the
   * via (final matching catches within 250m).
   */
  private static final double VIA_RELOCATION_DROP_CACHED_LEG_M = 50;

  /**
   * Hoisted ranking comparators. Both are stateless, so a shared static instance
   * ranks identically to a per-call allocation. {@code List.sort} is stable, so
   * equal-key ties resolve by insertion order.
   */
  private static final Comparator<RoundTripCandidateProvider.CandidatePoint> BY_HEURISTIC_SCORE =
    (a, b) -> Double.compare(a.score, b.score);
  private static final Comparator<ScoredRoute> BY_ROUTED_SCORE =
    (a, b) -> Double.compare(a.routedScore, b.routedScore);
  /**
   * Max length of a single untagged edge tolerated on a committed leg. In detail
   * mode every link is subdivided and tagged, so one long null-tag edge is the
   * chord fingerprint of a failed detail pass — the shipped geometry cuts straight
   * across terrain where the road curves (the "beeline"). Ground-truthed on Lozère
   * gravel: flagged 300-950m chords all had a real curving road.
   */
  // Package-visible: doRoundTrip's residual-chord disclosure uses the same
  // threshold, so the advisory and the planner's fidelity retry never disagree
  // about what counts as a chord.
  public static final int MAX_UNDETAILED_EDGE_METERS = 200;
  private final CandidateScorer scorer;
  private final RoundTripCandidateProvider candidateProvider;

  private final int subRouteCount;
  private final double tolerance;
  private final int maxAttempts;

  /**
   * Round-trip variety seed (the request's {@code alternativeidx}). 0 = inert
   * (output bit-identical to the unseeded baseline); &gt;= 1 enables
   * {@link #VARIETY_JITTER_AMPLITUDE} jitter on the heuristic score so different
   * seeds route different near-tie candidates while direction focus stays fixed.
   * This is how a caller asks for an alternative loop.
   */
  private int varietySeed;

  /**
   * Amplitude of the variety-seed score jitter (adds ±amplitude × |score| × unit,
   * unit uniform in [-1, 1)). ±10% flips only near-tie rankings; tune from
   * full-matrix A/B evidence, not by feel.
   */
  static final double VARIETY_JITTER_AMPLITUDE = 0.10;
  private final LegRouter router;
  private final EngineIO io;
  private final EngineContext ctx;
  /**
   * Request-owned paved/road-bike verdict, set by {@link GreedyStrategy} before
   * planning (probed once at request entry). The internal
   * {@link #qualityGateReason fallback gate} forwards it to
   * {@link RoundTripQualityGate#evaluate} for the paved-vs-other branch; false
   * (older direct callers) uses profile-agnostic defaults.
   */
  private boolean pavedProfile;

  /**
   * Pocket-avoidance weight on {@link #pocketPenalty}'s [0,1] output. At 2.0 a
   * true pocket (≤3 reachable cells) loses to any well-connected alternative
   * within ~2 score units — enough to steer vias off dead-end roads (teardrop/stub
   * source), weak enough that a well-positioned pocket can still win when nothing
   * else closes.
   */
  static final double POCKET_PENALTY_WEIGHT = 2.0;
  /** Reachable-cell count at/above which a candidate is fully safe (no penalty). */
  static final int POCKET_SAFE_CELLS = 10;
  /** Reachable-cell count at/below which the penalty saturates at 1.0. */
  static final int POCKET_MIN_CELLS = 3;

  /**
   * [0,1] pocket penalty from reachability-cell density (see
   * {@link IsochroneExpansionResult#reachableCellsAround}): 0 at
   * ≥{@link #POCKET_SAFE_CELLS} (junction-rich), 1 at ≤{@link #POCKET_MIN_CELLS}
   * (thin dead-end). No cloud (-1) gets 0 — no signal, no penalty.
   */
  static double pocketPenalty(int reachableCells) {
    if (reachableCells < 0) return 0;
    if (reachableCells >= POCKET_SAFE_CELLS) return 0;
    if (reachableCells <= POCKET_MIN_CELLS) return 1.0;
    return (POCKET_SAFE_CELLS - reachableCells) / (double) (POCKET_SAFE_CELLS - POCKET_MIN_CELLS);
  }
  /**
   * Iso-pool health tracker; null for plain GREEDY / graph-native
   * providers, where every hook is a no-op (behaviour bit-identical to the
   * pre-health planner). Set by {@link GreedyStrategy}, one fresh
   * instance per plan.
   */
  private IsoPoolHealth poolHealth;

  public GreedyRoundTripPlanner(RoundTripEngineOps engine, RoundTripCandidateProvider provider) {
    this(engine, provider, new CandidateScorer(),
      DEFAULT_SUB_ROUTE_COUNT, DEFAULT_TOLERANCE, DEFAULT_MAX_ATTEMPTS);
  }

  /** Convenience wiring form: fan the composite engine seam out to the roles. */
  public GreedyRoundTripPlanner(RoundTripEngineOps engine, RoundTripCandidateProvider provider,
                                CandidateScorer scorer, int subRouteCount, double tolerance,
                                int maxAttempts) {
    this(engine, engine, engine, provider, scorer, subRouteCount, tolerance, maxAttempts);
  }

  /**
   * Enable iso-hostility scoring on the scorer. Only for paved profiles whose
   * typical {@code costFromStart/airDist} is near 1.0; gravel/MTB baselines around
   * 9 would flag every candidate. See {@link CandidateScorer#setHostilityActive}.
   */
  public void setHostilityActive(boolean active) {
    scorer.setHostilityActive(active);
  }

  /** Set the round-trip variety seed (the request's alternativeidx). Negative values clamp to 0 (= inert). */
  public void setVarietySeed(int seed) {
    varietySeed = Math.max(0, seed);
  }

  /**
   * Per-step routed top-K, set by the effort policy ({@link RoundTripEffortPolicy}):
   * BALANCED 2/3, AUTO 3/5, QUALITY 4/6. Each routed candidate is a full Dijkstra
   * leg, so this is the main per-step cost knob; wall-clock bounding is
   * {@link #setExternalDeadline}.
   */
  private int topKNormal = MAX_ROUTE_ATTEMPTS;
  private int topKLate = MAX_ROUTE_ATTEMPTS_LATE;
  /** Multiplier on the internal plan deadline (QUALITY: 2.0). */
  private double planBudgetScale = 1.0;

  public void setRouteBudgets(int normal, int late) {
    topKNormal = normal;
    topKLate = late;
  }

  public void setPlanBudgetScale(double scale) {
    planBudgetScale = scale <= 0 ? 1.0 : scale;
  }

  /** Routed top-K for a step: late steps and retry attempts explore more. */
  int routeBudgetFor(boolean lateStep) {
    return lateStep ? topKLate : topKNormal;
  }

  /**
   * Absolute wall-clock ceiling (epoch ms) from the caller — the request-level
   * deadline. Without it, plan()'s own {@link #DEFAULT_PLAN_DEADLINE_MS} was the
   * only bound, and the subRouteCount ladder, axis retry, ISO_GREEDY→GREEDY
   * recursion, and AUTO children each multiplied a fresh 30s into a minutes-long
   * worst case. Effective deadline = min of both. {@code <= 0} = unbounded
   * (default for direct callers and tests).
   */
  private long externalDeadline = Long.MAX_VALUE;

  public void setExternalDeadline(long deadlineMillis) {
    externalDeadline = deadlineMillis <= 0 ? Long.MAX_VALUE : deadlineMillis;
  }

  /**
   * Sector-resolved return-distance estimates (F6 oracle); null = fall back to the
   * global {@code indirectnessEst} EMA. Set from the ISO_GREEDY pool expansion,
   * deliberately NOT for plain GREEDY: a lazy oracle from GREEDY's small step-1
   * disk covers only the loop's near side, and mixing oracle/EMA regimes around
   * the coverage boundary measured quality-NEGATIVE (2 better / 5 worse). Revisit
   * only with a dedicated full-radius start expansion.
   *
   * <p><b>Consumer scope: the return-check skip decision ONLY.</b> It once also
   * fed candidate scoring; the 548-cell A/B measured that as a net-neutral wash
   * hiding four 30km distR crashes — return over-estimates on elevation-asymmetric
   * sectors (costly climb out, cheap ride back) steering placement into premature
   * contraction. A skip-path over-estimate is self-correcting (it only triggers
   * the real return Dijkstra earlier); a ranking over-estimate substitutes for
   * truth with no correction.
   */
  private ReturnDistanceOracle returnOracle;

  public GreedyRoundTripPlanner(LegRouter router, EngineIO io,
                                EngineContext ctx, RoundTripCandidateProvider provider,
                                CandidateScorer scorer, int subRouteCount, double tolerance,
                                int maxAttempts) {
    this.router = router;
    this.io = io;
    this.ctx = ctx;
    this.candidateProvider = provider;
    this.scorer = scorer;
    this.subRouteCount = subRouteCount;
    this.tolerance = tolerance;
    this.maxAttempts = maxAttempts;
  }

  /**
   * Deterministic uniform value in [-1, 1) from a seed and two salts
   * (splitmix64-style). Keyed on stable inputs only (coordinates or fixed knob
   * ids, never iteration order), so the same request + seed reproduces the same
   * route. Shared by the greedy jitter and the WAYPOINT/ISOCHRONE geometry knobs.
   */
  public static double seededUnit(int seed, int saltA, int saltB) {
    long h = seed * 0x9E3779B97F4A7C15L;
    h ^= saltA * 0xC2B2AE3D27D4EB4FL;
    h ^= saltB * 0x165667B19E3779F9L;
    h ^= h >>> 30;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 27;
    h *= 0x94D049BB133111EBL;
    h ^= h >>> 31;
    return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
  }
  /**
   * Length fraction of {@code leg} whose segments run between nodes the reference
   * track visited. Uses node membership, not traveled-edge membership, by design:
   * variant legs are raw junction sequences while the refTrack is detailed, so a
   * looser node test is the right trade for a bounded-retrace MEASUREMENT (the
   * penalty itself uses the strict edge test in OsmPath).
   */
  public static double reuseFraction(OsmTrack leg, OsmTrack refTrack) {
    if (leg == null || leg.nodes == null || leg.nodes.size() < 2 || refTrack == null) {
      return 0;
    }
    double total = 0;
    double reused = 0;
    for (int i = 1; i < leg.nodes.size(); i++) {
      OsmPathElement a = leg.nodes.get(i - 1);
      OsmPathElement b = leg.nodes.get(i);
      double d = a.calcDistance(b);
      total += d;
      if (refTrack.containsNode(a) && refTrack.containsNode(b)) {
        reused += d;
      }
    }
    return total > 0 ? reused / total : 0;
  }

  /** Whether iso-pool prior terms are currently stripped from scoring. */
  private boolean isoInfluenceReduced() {
    return poolHealth != null && poolHealth.influenceReduced();
  }

  /**
   * Return-distance estimate (meters) to the loop start: sector-resolved when the
   * oracle covers the position, else {@code air × indirectnessEst}. {@code air} is
   * passed in because every caller already has it.
   */
  private double estimateReturnMeters(GreedyPlanSession s, int ilon, int ilat, double air) {
    if (returnOracle != null) {
      double v = returnOracle.estimateReturnMeters(ilon, ilat, air);
      if (v >= 0) {
        s.oracleEstimates++;
        if (poolHealth != null) poolHealth.recordReturnEstimate(true);
        return v;
      }
      // Coverage miss — the expansion never reached where the plan is looking.
      // Only meaningful as a health signal when an oracle exists at all (a
      // null oracle is priced once by the static no-oracle deduction).
      if (poolHealth != null) poolHealth.recordReturnEstimate(false);
    }
    s.fallbackEstimates++;
    return air * s.indirectnessEst;
  }

  /**
   * Set the request-owned paved/road-bike verdict. Call during planner
   * construction so the internal fallback gate matches the production gate
   * downstream.
   */
  public void setPavedProfile(boolean pavedProfile) {
    this.pavedProfile = pavedProfile;
  }

  public void setReturnOracle(ReturnDistanceOracle oracle) {
    this.returnOracle = oracle;
  }

  /**
   * Delegate the planner's internal fallback-quality check to the production gate
   * ({@link RoundTripQualityGate#evaluate}), so the planner rejects (and retries)
   * on the same criteria the engine applies downstream instead of shipping
   * fallback loops the gate would then reject. {@code allowSamewayback} is
   * hard-coded false: greedy never produces same-way-back routes.
   */
  // Package-private for direct testing — see GreedyRoundTripPlannerTest's
  // Phase 1.5 delegation verification.
  String qualityGateReason(OsmTrack track, double desiredDistance) {
    RoundTripQualityResult r = qualityGateVerdict(track, desiredDistance);
    if (r == null) return "no track";
    return r.isAccepted() ? null : r.getRejectionReason();
  }

  /** Full gate verdict (allowSamewayback=false), or {@code null} for a non-loop track. */
  RoundTripQualityResult qualityGateVerdict(OsmTrack track, double desiredDistance) {
    if (track == null || track.nodes == null || track.nodes.size() < 4) return null;
    return RoundTripQualityGate.evaluate(
      track, desiredDistance, pavedProfile, /*allowSamewayback*/ false);
  }

  /**
   * Fallback soundness rank (lower = better): clean accepted loop (0) beats a
   * sound same-way-back corridor / out-and-back (1) beats a chaotic loop (2:
   * self-intersections, beelines, hostile surface, zigzag). Shipping the rideable
   * corridor beats wandering into a chaos loop.
   */
  static int fallbackSeverity(RoundTripQualityResult verdict) {
    if (verdict == null) return 3;
    if (verdict.isAccepted()) return 0;
    return verdict.getShape() == RouteShape.OUT_AND_BACK ? 1 : 2;
  }

  /** True when the verdict's sole defect is a same-way-back corridor (rank-1 sound). */
  static boolean isForcedCorridorVerdict(RoundTripQualityResult verdict) {
    return verdict != null && !verdict.isAccepted()
      && verdict.getShape() == RouteShape.OUT_AND_BACK;
  }

  /**
   * Distance-share of the track on edges traversed more than once (first visit not
   * reuse), matching {@link LoopQualityMetrics#computeRoadReusePercent}.
   * Self-contained — use on the FINAL loop, not per-step candidate scoring.
   */
  static double finalTrackReuseRatio(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) return 0.0;
    Map<Long, Integer> localCounts = new HashMap<>();
    double total = 0;
    double reused = 0;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = a.calcDistance(b);
      total += segLen;
      long key = LoopGeometry.edgeKey(a, b);
      Integer cur = localCounts.get(key);
      int prev = (cur == null ? 0 : cur) + 1;
      localCounts.put(key, prev);
      if (prev > 1) reused += segLen;
    }
    return total > 0 ? reused / total : 0.0;
  }

  /**
   * Budget-pressure diagnostic: "was the plan budget enough?". Emitted on every
   * plan() exit; grep "budget:" and read the headroom distribution (healthy fleet
   * = P95 headroom well above 0, near-zero "EXHAUSTED" for the 40-100km class).
   * Also stamps the instance-held plan-exit telemetry {@link #stampTelemetry}
   * (static) can't reach: return-oracle usage and iso-pool health + source counters.
   */
  private void stampBudgetDiagnostic(GreedyPlanSession s) {
    RoundTripResult result = s.result;
    long now = System.currentTimeMillis();
    long usedMs = now - s.planStart;
    long headroomMs = s.deadline - now;
    // Report the EFFECTIVE budget the plan actually ran under (deadline -
    // planStart), not the nominal distance-scaled planBudgetMs: the external
    // request deadline can clamp it smaller (e.g. a small request `timeout`),
    // and printing "used Xms of 30000ms" when the plan was only allowed 5000ms
    // would mislead operators reading the headroom distribution. Annotate when
    // the request budget was the binding constraint.
    long effectiveBudgetMs = s.deadline - s.planStart;
    boolean cappedByRequest = effectiveBudgetMs < s.planBudgetMs;
    result.addDiagnostic("budget: used " + usedMs + "ms of " + effectiveBudgetMs
      + "ms plan budget, headroom " + headroomMs + "ms"
      + (cappedByRequest ? " (capped from " + s.planBudgetMs + "ms by request budget)" : "")
      + (headroomMs <= 0 ? " (EXHAUSTED)" : ""));
    if (s.oracleEstimates + s.fallbackEstimates > 0) {
      result.addDiagnostic("return oracle: " + s.oracleEstimates + " sector-resolved / "
        + s.fallbackEstimates + " EMA-fallback estimates"
        + (returnOracle == null ? " (no oracle)" : ""));
    }
    if (poolHealth != null) {
      result.addDiagnostic("iso-pool health: " + poolHealth.describe()
        + (s.poolDemotedAtStep >= 0 ? ", influence reduced at step " + s.poolDemotedAtStep : "")
        + (s.graphNativeOnlyAtStep >= 0 ? ", graph-native-only from step " + s.graphNativeOnlyAtStep : ""));
      result.setIsoPoolHealthScore(poolHealth.score());
    }
    result.setPoolDemotedAtStep(s.poolDemotedAtStep);
    result.setAcceptedQuotaInjectedLegs(s.acceptedQuotaInjectedLegs);
  }

  private static void stampTelemetry(RoundTripResult result, long planStart,
                                     int candidatesGenerated, int candidatesRouted,
                                     int returnChecksPerformed,
                                     int routedIso, int routedNonIso,
                                     int acceptedIsoLegs, int acceptedNonIsoLegs) {
    // Delegate base counters to the 5-arg overload (not the 9-arg one — that
    // would recurse forever). Sed-rename caught this site too; the explicit
    // 5-arg overload name avoids the trap.
    stampBaseTelemetry(result, planStart, candidatesGenerated, candidatesRouted, returnChecksPerformed);
    result.setRoutedIsoCandidates(routedIso);
    result.setRoutedNonIsoCandidates(routedNonIso);
    result.setAcceptedIsoLegs(acceptedIsoLegs);
    result.setAcceptedNonIsoLegs(acceptedNonIsoLegs);
  }

  private static void stampBaseTelemetry(RoundTripResult result, long planStart,
                                         int candidatesGenerated, int candidatesRouted,
                                         int returnChecksPerformed) {
    result.setCandidatesGenerated(candidatesGenerated);
    result.setCandidatesRouted(candidatesRouted);
    result.setReturnChecksPerformed(returnChecksPerformed);
    result.setRuntimeMillis(System.currentTimeMillis() - planStart);
  }

  private void populateResult(RoundTripResult result, OsmTrack track,
    List<MatchedWaypoint> waypointStack,
    MatchedWaypoint startMwp, List<OsmTrack> segments,
    double desiredDistance, double startDirection) {
    result.setTrack(track);
    result.setLoopWaypoints(buildLoopWaypoints(waypointStack));
    result.setMatchedWaypoints(buildMatchedWaypoints(waypointStack, startMwp));
    result.setLegTracks(new ArrayList<>(segments));
    // Compute the full quality metrics once and record them as a diagnostic so
    // API callers can inspect loop quality instead of it being computed and
    // discarded.
    if (track != null && track.nodes != null && track.nodes.size() >= 2) {
      LoopQualityMetrics metrics = LoopQualityMetrics.compute(track, (int) desiredDistance, startDirection);
      result.addDiagnostic("quality: " + metrics);
      // Also surface the semantic reuse classification — what SHAPE this
      // loop is (STRICT_LOOP / LOLLIPOP / OUT_AND_BACK) and any
      // disclosures (e.g. "contains retraced scenic spur: 4.2km"). The
      // engine's final gate will reject INVALID_RETRACE before the result
      // is returned to the caller, so a classifier verdict here is for
      // diagnostic surfacing only — never a second accept/reject.
      RoundTripQualityResult qr = ReuseClassifier.classify(track, desiredDistance,
        /*allowSamewayback*/ false);
      result.addDiagnostic("shape: " + qr.getShape()
        + ", stem=" + qr.getTerminalStemReuseMeters() + "m"
        + ", spur=" + qr.getScenicSpurReuseMeters() + "m"
        + ", maxContiguous=" + qr.getMaxContiguousReuseMeters() + "m");
      for (String d : qr.getDisclosures()) result.addDiagnostic("disclosure: " + d);
    }
  }

  /**
   * Minimum graph-native (per-step, non-iso) candidates guaranteed a routed slot
   * when a step mixes sources (the ISO_GREEDY blend).
   *
   * <p>Why: phase-1 ranks on estimated leg distance. Graph-native candidates carry
   * expansion-compiled routed truth while start-pool iso candidates use the
   * optimistic {@code airDist × indirectnessEst}, so in a mixed sort the guesses
   * outrank the measurements and iso monopolizes the routed top-K —
   * {@link #combinedRoutedScore} never prices the honest local leg. The 2026-07
   * AUTO winner study: of 139 plain-GREEDY wins, the loss clusters were pricier
   * surfaces (38) and extra self-crossings (38) — both "the cleaner local leg was
   * never routed". Reserving a seat changes nothing when the honest pick deserves
   * to lose; phase-2 still judges on routed truth.
   */
  private static final int GRAPH_NATIVE_MIN_ROUTED = 1;
  /** Quota at the late/retry budget ({@link #MAX_ROUTE_ATTEMPTS_LATE} slots). */
  private static final int GRAPH_NATIVE_MIN_ROUTED_LATE = 2;

  /**
   * Graph-native routed-seat quota for one attempt. A DEGRADED pool cedes one more
   * seat, but the quota never fills the whole routed top-K: DEGRADED still competes
   * on routed truth (total eviction is UNHEALTHY's provider switch), so under small
   * budgets one seat always stays contestable by iso picks.
   */
  static int graphNativeQuota(boolean lateStep, boolean degraded, int routeBudget) {
    int quota = lateStep ? GRAPH_NATIVE_MIN_ROUTED_LATE : GRAPH_NATIVE_MIN_ROUTED;
    if (degraded) {
      quota++;
    }
    if (routeBudget > 1 && quota >= routeBudget) {
      quota = routeBudget - 1;
    }
    return quota;
  }

  /**
   * Start-pool iso candidates carry a real {@code costFromStart}; per-step
   * graph-native candidates carry the {@code NO_ISO_COST} sentinel. Single source
   * of truth for the source split used by the routed-slot quota and diagnostics.
   */
  static boolean isIsoPoolCandidate(RoundTripCandidateProvider.CandidatePoint cp) {
    return cp.costFromStart != RoundTripCandidateProvider.NO_ISO_COST;
  }

  /**
   * Undo a tentatively committed leg — the shared tail of all four rejection
   * sites. Removes the leg and its via, reverses the attribution counters, and
   * fires the iso-rejection health hook. Sites past {@code addVisitedEdges} call
   * {@code removeVisitedEdges} first (order-independent). Returns the restored
   * total distance; the caller re-reads the anchor and restores prev coordinates.
   */
  private void undoTentativeLeg(GreedyPlanSession s, ScoredRoute accepted) {
    s.segments.remove(s.segments.size() - 1);
    if (accepted.fromIsoCandidate) {
      s.acceptedIsoLegs--;
    } else {
      s.acceptedNonIsoLegs--;
    }
    if (accepted.fromQuotaInjection) {
      s.acceptedQuotaInjectedLegs--;
    }
    recordIsoTrialRejection(accepted);
    s.waypointStack.remove(s.waypointStack.size() - 1);
    s.totalDistance -= accepted.routeDistance;
  }

  /** Health hook for an undone trial: an iso-sourced rejection is pool-loss evidence. */
  private void recordIsoTrialRejection(ScoredRoute rejected) {
    if (poolHealth != null && rejected.fromIsoCandidate) {
      poolHealth.recordIsoLegRejection();
    }
  }

  /**
   * Source attribution for a COMMITTED leg: one grep-able diagnostic
   * per accepted leg (source, quota injection, return regime, how routed truth and
   * closure trials moved it from its heuristic rank, pool health). Also feeds the
   * health tracker: a mixed-source routed top-K won by graph-native is the
   * pool-loss signal, and the via's bearing feeds sector-bunching.
   */
  private void recordAcceptedLegAttribution(RoundTripResult result, ScoredRoute accepted,
                                            int step, int trial, boolean mixedSourceRouting,
                                            int startIlon, int startIlat,
                                            MatchedWaypoint committedVia) {
    if (poolHealth != null) {
      if (mixedSourceRouting) {
        poolHealth.recordRoutedComparison(accepted.fromIsoCandidate, accepted.fromQuotaInjection);
      }
      if (committedVia != null && committedVia.crosspoint != null) {
        poolHealth.recordAcceptedLegBearing(CheapAngleMeter.getDirection(
          startIlon, startIlat, committedVia.crosspoint.getILon(), committedVia.crosspoint.getILat()));
      }
    }
    result.addDiagnostic("leg " + step + " source: "
      + (accepted.fromIsoCandidate ? "iso-pool" : "graph-native")
      + " quotaInjected=" + (accepted.fromQuotaInjection ? "yes" : "no")
      + " return=" + (accepted.oracleBackedReturn ? "oracle" : "ema")
      + " heurRank=" + accepted.candidateIndex
      + " trial=" + trial
      + (poolHealth == null ? "" : String.format(Locale.US,
          " poolHealth=%.2f/%s", poolHealth.score(), poolHealth.state())));
  }

  /**
   * Ensure at least {@code minNonIso} graph-native candidates hold routed slots in
   * {@code picked}: add while under {@code k}, else evict the worst-scored iso
   * pick. Quota picks skip the angular-spread rule (at K=3 source fairness outranks
   * spread; phase-2 judges the outcome). No-op for single-source lists. On change,
   * {@code picked} is re-sorted by heuristic score; returns whether anything changed.
   */
  static boolean enforceSourceQuota(List<RoundTripCandidateProvider.CandidatePoint> picked,
                                    List<RoundTripCandidateProvider.CandidatePoint> sorted,
                                    int k, int minNonIso) {
    int nonIso = 0;
    for (RoundTripCandidateProvider.CandidatePoint cp : picked) {
      if (!isIsoPoolCandidate(cp)) nonIso++;
    }
    int need = Math.min(minNonIso, k) - nonIso;
    if (need <= 0) return false;
    boolean changed = false;
    for (RoundTripCandidateProvider.CandidatePoint cp : sorted) {
      if (need <= 0) break;
      if (isIsoPoolCandidate(cp) || picked.contains(cp)) continue;
      if (picked.size() >= k) {
        int evict = -1;
        double worstScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < picked.size(); i++) {
          RoundTripCandidateProvider.CandidatePoint p = picked.get(i);
          if (isIsoPoolCandidate(p) && p.score > worstScore) {
            worstScore = p.score;
            evict = i;
          }
        }
        if (evict < 0) break; // no iso pick left to displace
        picked.remove(evict);
      }
      cp.quotaInjected = true; // attribution: slot held only via injection
      picked.add(cp);
      need--;
      changed = true;
    }
    if (changed) {
      Collections.sort(picked, BY_HEURISTIC_SCORE);
    }
    return changed;
  }

  /**
   * Pick up to {@code k} score-sorted candidates with angular spread: accept each
   * if it is ≥{@link #MIN_ANGULAR_SEPARATION_DEG} from every prior pick. If culling
   * leaves fewer than {@code k}, back-fill with the next best-scored so we never
   * under-budget.
   */
  static List<RoundTripCandidateProvider.CandidatePoint> pickDiverseTopK(
    List<RoundTripCandidateProvider.CandidatePoint> sorted, int k) {
    List<RoundTripCandidateProvider.CandidatePoint> picked = new ArrayList<>(k);
    for (RoundTripCandidateProvider.CandidatePoint cp : sorted) {
      if (picked.size() >= k) break;
      boolean farEnough = true;
      for (RoundTripCandidateProvider.CandidatePoint other : picked) {
        if (CheapAngleMeter.getDifferenceFromDirection(cp.bearing, other.bearing)
            < MIN_ANGULAR_SEPARATION_DEG) {
          farEnough = false;
          break;
        }
      }
      if (farEnough) picked.add(cp);
    }
    if (picked.size() < k) {
      for (RoundTripCandidateProvider.CandidatePoint cp : sorted) {
        if (picked.size() >= k) break;
        if (!picked.contains(cp)) picked.add(cp);
      }
    }
    return picked;
  }

  // --- Routing with timeout ---

  /**
   * Routes from→to with a per-call timeout = min(SUB_ROUTE_TIMEOUT_MS, deadline - now).
   * Returns {@code null} if the remaining budget is below {@link #MIN_FIND_TRACK_MS}.
   */
  // Package-private (not private) so RoutingIslandExceptionTest can drive the
  // unroutable-leg path directly via a RoutingEngine test double.
  /**
   * Cap on how much of a relaxed-penalty return variant may retrace the committed
   * legs (node-membership fraction): a bounded same-way-back stretch may replace a
   * self-crossing return, a full retrace may not.
   */
  private static final double MAX_VARIANT_REUSE_FRACTION = 0.5;
  /** Penalty step-down ladder tried when the fully-penalised return self-crosses. */
  private static final double[] RETURN_VARIANT_FACTORS = {0.5, 0.0};

  public void setPoolHealth(IsoPoolHealth health) {
    this.poolHealth = health;
  }

  /**
   * Plan a greedy round-trip loop. Driver over the internal plan split (one
   * {@link GreedyPlanSession} per call): step loop, then the outcome tails
   * ({@link #finishPlan}). All plan-scoped mutable state lives on the
   * session; this class carries only configuration.
   */
  public RoundTripResult plan(OsmNodeNamed start, double desiredDistance, double startDirection) {
    long planStart = System.currentTimeMillis();
    // Distance-scaled plan budget (product sizing: 40-100km loops are the
    // standard class and keep the calibrated 30s; up to 200km scales linearly
    // to 2x so bigger loops get proportionally more search; beyond that the
    // engine-level opt-in gate applies). Always hard-capped by the request
    // budget (externalDeadline).
    // planBudgetScale: the effort policy's multiplier (QUALITY: 2.0) applies
    // on top of the distance scaling; the request budget (externalDeadline)
    // stays the hard cap either way.
    long planBudgetMs = (long) (DEFAULT_PLAN_DEADLINE_MS * planBudgetScale
      * Math.min(PLAN_BUDGET_MAX_SCALE,
          Math.max(1.0, desiredDistance / PLAN_BUDGET_REFERENCE_DISTANCE_M)));
    long deadline = Math.min(planStart + planBudgetMs, externalDeadline);
    // Closure reserve: early steps may not consume the tail of the plan
    // budget — the endgame (the last two steps, where closure retries
    // concentrate) always keeps at least CLOSURE_RESERVE_FRACTION of it.
    // Late steps, the fallback finalization and the force-close all run
    // against the FULL deadline.
    long earlyDeadline = deadline - (long) (CLOSURE_RESERVE_FRACTION * (deadline - planStart));
    GreedyPlanSession s = new GreedyPlanSession(start, desiredDistance, startDirection,
      planStart, planBudgetMs, deadline, earlyDeadline, subRouteCount, ROAD_INDIRECTNESS);

    MatchedWaypoint startMwp = matchPoint(start.ilon, start.ilat, "greedy_start");
    if (startMwp == null) {
      return failNoStart(s);
    }
    s.startMwp = startMwp;
    s.currentMwp = startMwp;
    s.waypointStack.add(startMwp);
    if (startDirection >= 0) {
      s.dirPref = nearestDirectionPreference(startDirection);
    }

    for (int step = 1; step <= subRouteCount; step++) {
      StepOutcome outcome = planStep(s, step);
      if (outcome == StepOutcome.CLOSED) {
        return s.result;
      }
      if (outcome == StepOutcome.STOPPED) {
        break;
      }
    }
    return finishPlan(s);
  }

  /** Start not matchable to the road network — fail fast with stamped telemetry. */
  private RoundTripResult failNoStart(GreedyPlanSession s) {
    s.result.setFallbackReason("start point not on road network");
    stampExit(s);
    return s.result;
  }

  /** How one step of the plan ended, for the step-loop driver in {@link #plan}. */
  private enum StepOutcome {
    /** The loop closed within tolerance — {@code s.result} is final. */
    CLOSED,
    /** A leg was committed; continue with the next step. */
    COMMITTED,
    /** Deadline hit or attempts exhausted — stop stepping, run the outcome tails. */
    STOPPED
  }

  /**
   * One step of the plan: the shrinking-radius attempt loop over the
   * candidate round ({@link #generateAndScoreCandidates} +
   * {@link #routeTopCandidates}) and the loop assembler
   * ({@link #tryCommitTrials}).
   */
  private StepOutcome planStep(GreedyPlanSession s, int step) {
    // Closure reserve (see earlyDeadline): non-late steps run against the
    // reduced deadline so the endgame always has budget left.
    long stepDeadline = (step >= subRouteCount - 1) ? s.deadline : s.earlyDeadline;
    if (System.currentTimeMillis() >= stepDeadline) {
      s.result.addDiagnostic("step " + step + ": planner stepDeadline reached, stopping");
      return StepOutcome.STOPPED;
    }
    boolean candidateFound = false;
    double localRadius = s.subTarget;
    int currentIlon = s.currentMwp.crosspoint.getILon();
    int currentIlat = s.currentMwp.crosspoint.getILat();
    // Segments only change across steps — any tentative append is undone on retry.
    OsmTrack cachedRefTrack = s.segments.isEmpty() ? null : buildRefTrack(s.segments);

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      if (System.currentTimeMillis() >= stepDeadline) break;

      double airRadius = localRadius / s.indirectnessEst;
      ScoredCandidates scored = generateAndScoreCandidates(s, step, currentIlon, currentIlat,
        airRadius, stepDeadline, cachedRefTrack);
      // The via anchor of this attempt: unchanged until a trial commits, so the
      // routing round and the trial loop share one capture.
      MatchedWaypoint fromMwp = s.currentMwp;
      RoutedRound round = routeTopCandidates(s, step, attempt, scored.candidates,
        scored.stripIsoPriorTerms, currentIlon, currentIlat, airRadius, stepDeadline,
        cachedRefTrack, fromMwp);

      if (round.candidates.isEmpty()) {
        // No routable candidate at this radius — gentle shrink so we don't
        // jump past viable radii. The aggressive halving below applies only
        // when the route is too long.
        s.result.addDiagnostic("step " + step + " attempt " + attempt
          + ": no routable candidate at radius " + (int) localRadius);
        localRadius = Math.max(MIN_LOCAL_RADIUS_M, localRadius * BACKOFF_FACTOR_NO_CANDIDATE);
        continue;
      }

      TrialOutcome trial = tryCommitTrials(s, step, round, stepDeadline,
        currentIlon, currentIlat, cachedRefTrack, fromMwp);
      if (trial == TrialOutcome.CLOSED) {
        return StepOutcome.CLOSED;
      }
      if (trial == TrialOutcome.COMMITTED) {
        candidateFound = true;
        break;
      }

      // Every routed candidate failed its length/detail/closure checks —
      // restore step-start state is already done per trial; shrink the
      // radius (aggressively when length was the dominant failure) and
      // regenerate candidates.
      currentIlon = s.currentMwp.crosspoint.getILon();
      currentIlat = s.currentMwp.crosspoint.getILat();
      localRadius = Math.max(MIN_LOCAL_RADIUS_M,
        localRadius * (trial == TrialOutcome.EXHAUSTED_TOO_LONG
          ? BACKOFF_FACTOR_TOO_LONG : BACKOFF_FACTOR_NO_CANDIDATE));
    }

    if (!candidateFound) {
      s.result.addDiagnostic("step " + step + ": exhausted all " + maxAttempts + " attempts");
      return StepOutcome.STOPPED;
    }
    return StepOutcome.COMMITTED;
  }

  /** Phase-1 output: heuristically scored, sorted candidates plus the pool-health
   *  demotion flag the routed round must honor (computed once per attempt — the
   *  routed re-score must not re-apply prior terms phase 1 stripped). */
  private static final class ScoredCandidates {
    final List<RoundTripCandidateProvider.CandidatePoint> candidates;
    final boolean stripIsoPriorTerms;

    ScoredCandidates(List<RoundTripCandidateProvider.CandidatePoint> candidates,
                     boolean stripIsoPriorTerms) {
      this.candidates = candidates;
      this.stripIsoPriorTerms = stripIsoPriorTerms;
    }
  }

  /**
   * Candidate round, phase 1: generate candidates for this attempt, apply
   * pool-health demotions, and score every candidate by O(1) air-distance
   * heuristics (sorted best-first on return). Also arms the scorer's
   * direction-reference offset, which the phase-2 routed re-score reads —
   * the two phases deliberately share that scorer state per attempt.
   */
  private ScoredCandidates generateAndScoreCandidates(GreedyPlanSession s, int step,
      int currentIlon, int currentIlat, double airRadius, long stepDeadline,
      OsmTrack cachedRefTrack) {
    final RoundTripResult result = s.result;
    final OsmNodeNamed start = s.start;
    final double desiredDistance = s.desiredDistance;
    final double startDirection = s.startDirection;
    final double subTarget = s.subTarget;
    final double searchRadius = s.searchRadius;
    // --- Phase 1: Generate candidates and score by heuristics (no routing) ---
    // Bound the provider's graph expansion by the plan stepDeadline (and a
    // per-call ceiling): the expansion loop historically ran with no time
    // check at all, so a dense-area expansion could overrun every budget.
    List<RoundTripCandidateProvider.CandidatePoint> candidates;
    router.setTransientExpansionDeadline(Math.min(stepDeadline,
      System.currentTimeMillis() + SUB_ROUTE_TIMEOUT_MS));
    try {
      candidates = candidateProvider.candidatesForStep(
        currentIlon, currentIlat, airRadius,
        step, subRouteCount,
        start.ilon, start.ilat,
        startDirection,
        cachedRefTrack);
    } finally {
      router.setTransientExpansionDeadline(0);
    }
    s.candidatesGenerated += candidates.size();

    // Iso-pool health demotions. Evaluated per attempt so
    // evidence recorded mid-step applies to the retry's candidate set.
    // DEGRADED strips the pool's prior-based scoring terms below and adds
    // a routed-quota seat; UNHEALTHY drops iso-pool candidates entirely —
    // the internal graph-native (plain GREEDY) fallback. The per-step
    // graph-native expansion IS the pool refresh: re-expanding from the
    // start would rebuild the same stale pool, while the blend's per-step
    // source re-samples the loop's current lobe fresh on every step.
    boolean stripIsoPriorTerms = isoInfluenceReduced();
    if (stripIsoPriorTerms && s.poolDemotedAtStep < 0) {
      s.poolDemotedAtStep = step;
      result.addDiagnostic("step " + step + ": iso-pool influence reduced ("
        + poolHealth.describe() + ")");
    }
    if (poolHealth != null && poolHealth.graphNativeOnly()) {
      List<RoundTripCandidateProvider.CandidatePoint> graphOnly =
        new ArrayList<>(candidates.size());
      for (RoundTripCandidateProvider.CandidatePoint cp : candidates) {
        if (!isIsoPoolCandidate(cp)) graphOnly.add(cp);
      }
      // Safety valve: when the blend produced no graph-native candidate
      // this attempt, an unhealthy pool still beats an empty step.
      if (!graphOnly.isEmpty()) {
        if (s.graphNativeOnlyAtStep < 0) {
          s.graphNativeOnlyAtStep = step;
          result.addDiagnostic("step " + step
            + ": iso pool UNHEALTHY — graph-native-only from here ("
            + poolHealth.describe() + ")");
        }
        candidates = graphOnly;
      }
    }

    // Terrain-feasibility reference for the direction term: the best heading
    // actually reachable this step. When the requested direction is blocked
    // (sea/mountain), the best candidate is far off-bearing, and charging only
    // the offset BEYOND it stops direction from forcing a bad route. No-op
    // unless CandidateScorer.DIR_FEASIBILITY (which leaves the reference unused otherwise).
    double dirRef = 0.0;
    if (s.dirPref != DirectionPreference.ANY && !candidates.isEmpty()) {
      double best = 180.0;
      for (RoundTripCandidateProvider.CandidatePoint cp : candidates) {
        double diff = CheapAngleMeter.getDifferenceFromDirection(s.dirPref.bearing, cp.bearing);
        if (diff < best) best = diff;
      }
      dirRef = best;
    }
    scorer.setDirectionReferenceOffset(dirRef);

    // Previous leg's bearing for the heading-persistence term: NaN on
    // step 1 (no previous leg — the start-direction term covers it).
    // Must use the cos(lat)-scaled bearing so it shares the convention of
    // cp.bearing (graph-native and isochrone providers both set it
    // via CheapRuler.getScaledBearing). The raw
    // CheapAngleMeter.getDirection would distort the kink angle by ~10-15° off
    // the equator. (loopSweepPenalty uses the same scaled bearings — its
    // increments are judged against the true-geometry 360/N target.)
    double prevLegBearing = s.prevIlon >= 0
      ? CheapRuler.getScaledBearing(s.prevIlon, s.prevIlat, currentIlon, currentIlat)
      : Double.NaN;

    // Current via's radius from start — fixed per step; the unimodal-radius
    // term compares each candidate's radius against it.
    double currentRadius = CheapRuler.distance(currentIlon, currentIlat, start.ilon, start.ilat);

    // Score using air-distance estimates — O(1) per candidate
    for (RoundTripCandidateProvider.CandidatePoint cp : candidates) {
      double airDistToCp = CheapRuler.distance(currentIlon, currentIlat, cp.ilon, cp.ilat);
      // Exact-when-known leg distance (F6-lite): graph-native candidates
      // carry the expansion-compiled leg, whose distance is the routed
      // truth — strictly better than the air*indirectness guess the
      // estimate otherwise is. Fewer mis-ranked candidates means fewer
      // too-long undo cycles downstream.
      double estimatedRouteDist = (cp.routedTrack != null && cp.routedTrack.distance > 0)
        ? cp.routedTrack.distance
        : airDistToCp * s.indirectnessEst;
      double airDistToStart = CheapRuler.distance(cp.ilon, cp.ilat, start.ilon, start.ilat);
      // Deliberately the EMA estimate, NOT the return oracle: in candidate
      // RANKING an oracle over-estimate mis-ranks with no correction, and
      // consumer attribution (2026-07, 13-cell three-way) showed the
      // scoring path caused every 30km distR crash the corpus A/B found.
      // The oracle serves only the return-check skip decision, where an
      // over-estimate is self-correcting (it merely buys a real Dijkstra).
      double estimatedReturn = airDistToStart * s.indirectnessEst;
      double distFromStart = airDistToStart;

      double distFromPrevious = (s.prevIlon >= 0)
        ? CheapRuler.distance(s.prevIlon, s.prevIlat, cp.ilon, cp.ilat) * s.indirectnessEst
        : -1;

      // Health demotion: a DEGRADED pool's candidates lose their iso prior
      // terms (density bonus, contour-depth preference, iso-hostility
      // estimate) and compete on geometry + routed truth alone. The
      // CandidatePoint is NOT mutated — source attribution and the routed
      // quota still classify it as iso-pool via costFromStart.
      boolean stripIso = stripIsoPriorTerms && isIsoPoolCandidate(cp);
      cp.score = scorer.score(
        estimatedRouteDist, subTarget,
        s.totalDistance, estimatedReturn, desiredDistance,
        cp.bearing, s.dirPref,
        step, subRouteCount,
        0.0, // can't estimate visited ratio without routing
        distFromStart, searchRadius,
        distFromPrevious,
        stripIso ? RoundTripCandidateProvider.NO_ISO_COST : cp.costFromStart,
        stripIso ? RoundTripCandidateProvider.NO_ISO_DENSITY : cp.bucketHits,
        stripIso ? RoundTripCandidateProvider.NO_ISO_CONTOUR : cp.sourceContour)
        + POCKET_PENALTY_WEIGHT * pocketPenalty(cp.reachableCells);

      // Heading persistence: prefer candidates that keep turning gently
      // instead of kinking at the via — terrain-gated so constrained
      // networks that force sharp macro-turns are exempt (see
      // W_HEADING_PERSISTENCE / HEADING_TERRAIN_FADE_MAX), and distress-
      // braked once closures start failing (see s.closureRejections).
      if (!Double.isNaN(prevLegBearing) && s.closureRejections < HEADING_BRAKE_REJECTIONS) {
        double terrainFreedom = headingTerrainFreedom(s.indirectnessEst);
        cp.score += W_HEADING_PERSISTENCE * terrainFreedom
          * headingPersistencePenalty(prevLegBearing, cp.bearing, subRouteCount);
        // Loop-convexity: keep the via sequence sweeping monotonically around
        // the start and contracting after the apogee — kills radial-dent lobes
        // (basel_80km via3) and clustered-via tangles. Same terrain/closure
        // gating as heading persistence.
        cp.score += W_LOOP_SWEEP * terrainFreedom
          * loopSweepPenalty(start.ilon, start.ilat, s.prevIlon, s.prevIlat,
              currentIlon, currentIlat, cp.ilon, cp.ilat, subRouteCount);
        cp.score += W_UNIMODAL_RADIUS * terrainFreedom
          * unimodalRadiusPenalty(distFromStart, currentRadius, step, subRouteCount);
      }

      // Variety seed (= request alternativeidx): jitter the HEURISTIC score
      // only — it perturbs which candidates get routed, while the routed-
      // candidate comparison below stays purely quality-driven. The jitter is
      // ±VARIETY_JITTER_AMPLITUDE of the score MAGNITUDE, added (not multiplied),
      // so a positive unit always raises the score (= worse rank) regardless of
      // the score's sign. It flips near-tie
      // rankings without overriding clear winners; in sparse networks with no
      // near-ties, variety is best-effort.
      if (varietySeed > 0) {
        cp.score += VARIETY_JITTER_AMPLITUDE * Math.abs(cp.score)
          * seededUnit(varietySeed, cp.ilon, cp.ilat);
      }
    }

    // Rank by score (lowest = best)
    Collections.sort(candidates, BY_HEURISTIC_SCORE);

    return new ScoredCandidates(candidates, stripIsoPriorTerms);
  }

  /** Phase-2 output: Dijkstra-routed candidates (score-sorted) and whether both
   *  candidate sources priced a leg this attempt (the pool-health comparison
   *  signal — see {@code recordAcceptedLegAttribution}). */
  private static final class RoutedRound {
    final List<ScoredRoute> candidates;
    final boolean mixedSourceRouting;

    RoutedRound(List<ScoredRoute> candidates, boolean mixedSourceRouting) {
      this.candidates = candidates;
      this.mixedSourceRouting = mixedSourceRouting;
    }
  }

  /**
   * Candidate round, phase 2: pick the diverse routed top-K (with the
   * graph-native source quota), route each with full Dijkstra, and re-score
   * on routed distance, edge reuse, and cost.
   */
  private RoutedRound routeTopCandidates(GreedyPlanSession s, int step, int attempt,
      List<RoundTripCandidateProvider.CandidatePoint> candidates, boolean stripIsoPriorTerms,
      int currentIlon, int currentIlat, double airRadius, long stepDeadline,
      OsmTrack cachedRefTrack, MatchedWaypoint fromMwp) {
    final RoundTripResult result = s.result;
    final List<OsmTrack> segments = s.segments;
    final VisitedEdgeStore visitedEdges = s.visitedEdges;
    final OsmNodeNamed start = s.start;
    final double desiredDistance = s.desiredDistance;
    final double subTarget = s.subTarget;
    final double searchRadius = s.searchRadius;
    // --- Phase 2: Route top candidates, pick best by combined routed score ---
    // Heuristic score uses visitedEdgeRatio=0 since pre-routing can't know it.
    // Re-score with actual route distance and visited ratio so reuse-heavy
    // candidates lose to fresh ones at similar cost-per-meter.
    //
    // Pick top-K candidates with angular spread (≥ MIN_ANGULAR_SEPARATION_DEG
    // between picks) rather than just the top K by score — the top heuristic
    // picks are often spatially redundant in dense networks. Bump K from
    // MAX_ROUTE_ATTEMPTS to MAX_ROUTE_ATTEMPTS_LATE on late steps or after
    // an earlier failed attempt this step, where extra exploration pays off.
    boolean lateStep = step >= subRouteCount - 1 || attempt > 1;
    int routeBudget = routeBudgetFor(lateStep);
    List<RoundTripCandidateProvider.CandidatePoint> toRoute =
      pickDiverseTopK(candidates, routeBudget);
    // Source fairness for the routed slots (blended ISO_GREEDY only; no-op
    // for single-source lists): phase-1 ranks iso-pool picks on optimistic
    // airDist*indirectness estimates but graph-native picks on compiled
    // routed truth, so a mixed sort lets iso picks monopolize the routed
    // top-K and the cost-aware routed comparison never prices the honest
    // local alternative. Guarantee it a seat; phase-2 stays the judge.
    int minGraphNative = graphNativeQuota(lateStep, stripIsoPriorTerms, routeBudget);
    if (enforceSourceQuota(toRoute, candidates, routeBudget, minGraphNative)) {
      result.addDiagnostic("step " + step + " attempt " + attempt
        + ": source quota injected graph-native candidate(s) into routed top-" + routeBudget);
    }
    // Source-attribution context for this attempt: only a step where BOTH
    // sources actually produced a routed (priced) candidate constitutes a
    // real iso-vs-graph-native comparison. Computed from the routed
    // results below, not the pre-routing selection — an iso pick that
    // fails to route never priced an alternative, and charging the pool a
    // routed-truth loss for it would demote pools that lost nothing.
    boolean isoPriced = false;
    boolean graphPriced = false;

    // Phase 1 Step 2: keep a ranked list of routed candidates instead of
    // a single best-pick. Step 2 is structural and behavior-preserving —
    // we still commit only the top-ranked candidate at the end. Step 3
    // (closure-aware trial loop) will iterate this list when the locally
    // best candidate's closed loop is rejected.
    List<ScoredRoute> routedCandidates = new ArrayList<>();
    int routeAttempts = toRoute.size();

    // SAFE-4: merge the committed segments into a prefix node list ONCE per
    // attempt and share it (read-only) across every routed candidate's
    // tentative self-intersection count, instead of re-merging the whole
    // prefix per candidate. segments is not mutated inside the r-loop.
    List<OsmPathElement> committedPrefixNodes =
      segments.isEmpty() ? null : mergeSegmentsNoMap(segments, null).nodes;

    for (int r = 0; r < routeAttempts; r++) {
      RoundTripCandidateProvider.CandidatePoint cp = toRoute.get(r);

      // Profile-aware snap for every candidate via: prefer a profile-
      // compatible road near the candidate over the plain nearest way, so
      // a via never commits the loop to a junk-road pocket (the via-pinned
      // bulge source — see RoutingEngine.repairViaPinnedBulges). Graph-
      // native candidates need this just as much as off-road radial
      // points: their Dijkstra expansion terminates on whatever node hits
      // the cost contour, which in a track pocket IS a junk road.
      MatchedWaypoint toMwp = matchCandidatePointProfileAware(cp.ilon, cp.ilat);
      if (toMwp == null) continue;

      // Snap distance from the candidate coordinate to its routed-on-road
      // crosspoint. Reject candidates that snapped too far away.
      int snappedIlon = toMwp.crosspoint.getILon();
      int snappedIlat = toMwp.crosspoint.getILat();
      double snapDist = CheapRuler.distance(cp.ilon, cp.ilat, snappedIlon, snappedIlat);
      if (snapDist > airRadius * 0.5) continue;

      // A pre-routed graph-native leg ends at the ORIGINAL candidate node;
      // if the profile-aware snap relocated the via, that cached leg no
      // longer reaches it. Drop the cache and route to the relocated point
      // — one extra Dijkstra, paid only when a relocation actually fired.
      OsmTrack subTrack = cp.routedTrack;
      if (subTrack != null && snapDist > VIA_RELOCATION_DROP_CACHED_LEG_M) {
        io.logInfo("greedy: candidate via relocated " + (int) snapDist
          + "m to profile-friendly road, re-routing leg");
        subTrack = null;
      }
      if (subTrack == null) {
        subTrack = timedFindTrack("greedy-sub", fromMwp, toMwp, cachedRefTrack, stepDeadline);
      }
      s.candidatesRouted++;
      // Phase 2 v3 deliberate compromise: do NOT retrack candidate
      // sub-tracks here, even though it would give the scorer's
      // worst-contiguous signal real data. Retracking every
      // candidate (3 cands × 5 steps = ~15 per loop) inflates total
      // runtime ~40×. Empirically, Phase 2 v2 measurement showed the
      // scorer-level signal moves at most 0-1 pp of pass-rate. The
      // gate-side win comes from detailing ACCEPTED legs (below) so
      // the gate sees real metadata; candidate-level detail is
      // future work if it ever becomes the bottleneck.
      // Source-aware telemetry: start-iso candidates carry a non-sentinel
      // costFromStart; graph-native/non-start-iso candidates use NO_ISO_COST. We count
      // BEFORE the null/zero-distance guard so "routed" reflects what
      // Dijkstra attempted, not what succeeded.
      boolean isIsoCandidate = isIsoPoolCandidate(cp);
      if (isIsoCandidate) s.routedIso++; else s.routedNonIso++;
      if (subTrack == null || subTrack.distance == 0) continue;

      // Recompute scoring inputs from the SNAPPED endpoint (toMwp.crosspoint).
      // The router actually travels to that snapped location, not the raw
      // candidate point — so air-distance, bearing, return estimate, and the
      // overlong-route reject threshold should all reflect what was routed.
      double snappedAirDistFromCurrent = CheapRuler.distance(
        currentIlon, currentIlat, snappedIlon, snappedIlat);
      if (subTrack.distance > snappedAirDistFromCurrent * 3.0) continue;

      // SAFE-5: computeTrackVisitedRatio and the paved-profile
      // worst-contiguous scan below both iterate subTrack.nodes calling
      // a.calcDistance(b) over the identical segments in the identical
      // orientation. On paved profiles (where both run) compute the
      // per-segment integer distances ONCE and feed both passes, halving
      // the CheapRuler sqrt+round calls. calcDistance returns an int, so
      // the cached value widened to double is bit-identical to recomputing
      // it. Non-paved profiles run only the first pass, so they keep the
      // inline computation (no buffer to share).
      int[] segLens = pavedProfile ? segmentDistances(subTrack) : null;
      double actualVisitedRatio = computeTrackVisitedRatio(subTrack,
        visitedEdges, s.totalDistance, desiredDistance, segLens);
      double airDistToStart = CheapRuler.distance(snappedIlon, snappedIlat, start.ilon, start.ilat);
      // EMA, not the oracle — same ranking-vs-skip rationale as phase-1.
      double estimatedReturn = airDistToStart * s.indirectnessEst;
      double distFromPrevious = (s.prevIlon >= 0)
        ? CheapRuler.distance(s.prevIlon, s.prevIlat, snappedIlon, snappedIlat) * s.indirectnessEst
        : -1;
      double snappedBearing = CheapRuler.getScaledBearing(
        currentIlon, currentIlat, snappedIlon, snappedIlat);

      // Phase 2 v2: feed the routed sub-track's worst contiguous
      // hostile stretch to the scorer. This mirrors the gate's
      // physical-experience metric (a single long unbroken off-road
      // stretch is the cyclist's complaint surface). Phase 2.1's
      // averaged cost/distance ratio was the wrong signal — diagnostic
      // data showed 99% of fastbike rejections come from contiguous-
      // stretch trips, but leg-averages dilute single bad stretches
      // across surrounding clean kilometres. Worst-contiguous is a
      // max over edges, the same shape as the gate.
      //
      // Computed only for paved profiles (the hostile predicate is
      // road-bike specific); -1 sentinel for the rest keeps the
      // scorer on its iso-hostility fall-back.
      //
      // Scorer-side approximation: the gate's worstContiguousHostileMetersPaved
      // returns 0 on single-pass subTracks because it skips edges with
      // null wayKeyValues (the tag check is the dominant hostility signal,
      // costfactor>4.0 only catches extreme cases). Use the costfactor-
      // only variant with the lower SCORER_HOSTILE_COSTFACTOR_THRESHOLD
      // to get a usable signal on single-pass tracks. The gate's precise
      // tag-aware check still runs post-detail before commit.
      int worstHostile = pavedProfile
        ? RoundTripQualityGate.worstContiguousCostlyMetersForScorer(subTrack, segLens)
        : -1;

      // Same iso-prior stripping as phase 1 — the routed comparison must
      // not re-apply the demoted pool's tie-break terms.
      boolean stripIsoRouted = stripIsoPriorTerms && isIsoCandidate;
      double routedScorerScore = scorer.score(
        subTrack.distance, subTarget,
        s.totalDistance, estimatedReturn, desiredDistance,
        snappedBearing, s.dirPref,
        step, subRouteCount,
        actualVisitedRatio,
        airDistToStart, searchRadius,
        distFromPrevious,
        stripIsoRouted ? RoundTripCandidateProvider.NO_ISO_COST : cp.costFromStart,
        stripIsoRouted ? RoundTripCandidateProvider.NO_ISO_DENSITY : cp.bucketHits,
        stripIsoRouted ? RoundTripCandidateProvider.NO_ISO_CONTOUR : cp.sourceContour,
        worstHostile);

      double costPerMeter = (double) subTrack.cost / subTrack.distance;
      double routedScore = combinedRoutedScore(routedScorerScore, costPerMeter);
      int tentativeSelfIntersections = countTentativeSelfIntersections(committedPrefixNodes, subTrack);
      if (tentativeSelfIntersections > 0) {
        routedScore += PARTIAL_SELF_INTERSECTION_WEIGHT * tentativeSelfIntersections;
      }

      ScoredRoute candidate = new ScoredRoute();
      candidate.track = subTrack;
      candidate.toMwp = toMwp;
      candidate.routeDistance = subTrack.distance;
      candidate.visitedRatio = actualVisitedRatio;
      candidate.fromIsoCandidate = isIsoCandidate;
      candidate.fromQuotaInjection = cp.quotaInjected;
      candidate.oracleBackedReturn = returnOracle != null
        && returnOracle.covers(snappedIlon, snappedIlat);
      candidate.routedScore = routedScore;
      candidate.candidateIndex = r;
      candidate.tentativeSelfIntersections = tentativeSelfIntersections;
      candidate.routedLegWorstHostileMeters = worstHostile;
      routedCandidates.add(candidate);
      if (isIsoCandidate) {
        isoPriced = true;
      } else {
        graphPriced = true;
      }
    }

    boolean mixedSourceRouting = isoPriced && graphPriced;

    sortByRoutedScore(routedCandidates);

    return new RoutedRound(routedCandidates, mixedSourceRouting);
  }

  /** How the trial loop over one routed round ended. */
  private enum TrialOutcome {
    /** The loop closed within tolerance — {@code s.result} is final. */
    CLOSED,
    /** A leg was committed (loop not closed yet). */
    COMMITTED,
    /** All trials failed, dominated by too-long projections — shrink hard. */
    EXHAUSTED_TOO_LONG,
    /** All trials failed for other reasons — shrink gently. */
    EXHAUSTED
  }

  /**
   * Loop assembler: the closure-aware trial loop over one routed round —
   * tentative commit, closure check, detail upgrade, gate evaluation, and the
   * undo machinery. May finalize the whole plan (CLOSED) when a closure lands
   * within tolerance and passes the gate.
   */
  private TrialOutcome tryCommitTrials(GreedyPlanSession s, int step, RoutedRound round,
      long stepDeadline, int currentIlon, int currentIlat, OsmTrack cachedRefTrack,
      MatchedWaypoint fromMwp) {
    final List<ScoredRoute> routedCandidates = round.candidates;
    final boolean mixedSourceRouting = round.mixedSourceRouting;
    final RoundTripResult result = s.result;
    final List<OsmTrack> segments = s.segments;
    final VisitedEdgeStore visitedEdges = s.visitedEdges;
    final List<MatchedWaypoint> waypointStack = s.waypointStack;
    final MatchedWaypoint startMwp = s.startMwp;
    final OsmNodeNamed start = s.start;
    final double desiredDistance = s.desiredDistance;
    final double startDirection = s.startDirection;
    final double subTarget = s.subTarget;
    // --- Phase 3+4: closure-aware trial loop over the ranked routed
    // candidates (the "Step 3" the ranked list was built for, previously
    // unimplemented). Historically only the top-ranked candidate was
    // tried; a closure rejection or too-long projection undid the leg
    // and paid a WHOLE fresh attempt — re-expansion, re-matching,
    // re-routing K candidates — although ranks 1..K-1 were already
    // routed and in hand. Now those runner-ups are tried in score order
    // (each costs at most one return Dijkstra plus detailing); only when
    // the whole ranked list fails does the attempt loop shrink the
    // radius and regenerate.
    //
    // Work ordering per trial (cheap-reject-first): the RAW single-pass
    // leg is committed and the length (too-long) decision made BEFORE
    // the detail retrack. A too-long undo — the most common rejection —
    // now costs zero detail Dijkstras (it used to discard 1-3 of them).
    // Detailing, and the paved-hostility/fidelity checks that need
    // per-edge metadata, run only for legs that survive the length
    // decision; the quality gate still only ever sees fully detailed
    // geometry.
    boolean legCommitted = false;
    boolean tooLongSeen = false;
    // Record previous waypoint position for next step's Silesian scoring.
    // Saved once per attempt so every trial's undo can restore it.
    int savedPrevIlon = s.prevIlon;
    int savedPrevIlat = s.prevIlat;
    // SAFE-6: reuse cachedRefTrack instead of rebuilding it. segments is
    // not mutated between its construction (top of step) and here beyond
    // this trial loop's own add/undo pairs, which always restore
    // step-start content before the next detail call. Routing/retrack
    // treat the refTrack as read-only (a fresh OsmTrack is built
    // internally).
    OsmTrack refBeforeAccept = cachedRefTrack;

    for (int trial = 0; trial < routedCandidates.size(); trial++) {
      if (System.currentTimeMillis() >= stepDeadline) break;
      ScoredRoute accepted = routedCandidates.get(trial);

      result.addDiagnostic("step " + step + (trial > 0 ? " trial " + (trial + 1) : "")
        + ": routed " + (int) accepted.routeDistance
        + "m (target " + (int) subTarget + "m)"
        + ", reuse=" + String.format("%.1f%%", accepted.visitedRatio * 100));

      // Tentatively commit the RAW single-pass leg.
      segments.add(accepted.track);
      s.totalDistance += accepted.routeDistance;
      if (accepted.fromIsoCandidate) s.acceptedIsoLegs++;
      else s.acceptedNonIsoLegs++;
      if (accepted.fromQuotaInjection) s.acceptedQuotaInjectedLegs++;
      s.prevIlon = currentIlon;
      s.prevIlat = currentIlat;

      // Use actual track endpoint for next step
      OsmPathElement lastNode = accepted.track.nodes.get(accepted.track.nodes.size() - 1);
      MatchedWaypoint nextMwp = matchPoint(lastNode.getILon(), lastNode.getILat(), "greedy_next");
      s.currentMwp = (nextMwp != null) ? nextMwp : accepted.toMwp;
      waypointStack.add(s.currentMwp);

      // Learn the observed air-to-road ratio of this leg (kept on undo —
      // a routed leg is a real terrain measurement either way). Only the
      // top-ranked trial updates the estimate: the pre-trial-loop code
      // learned exactly once per attempt (from its single candidate), and
      // letting every runner-up update it would let a rejection-heavy
      // attempt shift the EMA several times before a leg commits.
      double legAir = CheapRuler.distance(currentIlon, currentIlat, lastNode.getILon(), lastNode.getILat());
      if (trial == 0 && legAir > 500) {
        double observed = accepted.routeDistance / legAir;
        s.indirectnessEst = Math.max(ROAD_INDIRECTNESS, Math.min(MAX_INDIRECTNESS_EST,
          (1 - INDIRECTNESS_EMA_ALPHA) * s.indirectnessEst + INDIRECTNESS_EMA_ALPHA * observed));
        if (s.indirectnessEst > ROAD_INDIRECTNESS + 0.05) {
          result.addDiagnostic(String.format(Locale.US,
            "step %d: observed indirectness %.2f, estimate now %.2f", step, observed, s.indirectnessEst));
        }
      }

      // --- Closure check (ONE return Dijkstra per trial) ---
      int fromRetIlon = s.currentMwp.crosspoint.getILon();
      int fromRetIlat = s.currentMwp.crosspoint.getILat();
      double airDistToStart = CheapRuler.distance(fromRetIlon, fromRetIlat, start.ilon, start.ilat);
      boolean oracleBacked = returnOracle != null && returnOracle.covers(fromRetIlon, fromRetIlat);
      double minReturn = estimateReturnMeters(s, fromRetIlon, fromRetIlat, airDistToStart);

      // Skip the return check only when closure is clearly out of reach AND
      // we still have multiple steps left. The safety factor covers the
      // estimate's blindness to constrained networks forcing much longer
      // returns — a sector-resolved oracle estimate already carries the
      // graph's detour truth, so it needs far less headroom than the
      // global-EMA guess. Never skip on the last two steps where closure
      // matters.
      boolean isLateStep = step >= subRouteCount - 1;
      double skipSafety = oracleBacked ? RETURN_SKIP_SAFETY_ORACLE : RETURN_SKIP_SAFETY;
      boolean returnChecked = isLateStep
        || s.totalDistance + minReturn * skipSafety >= desiredDistance * (1 - tolerance);
      OsmTrack returnTrack = null;
      OsmTrack returnRef = null;
      if (returnChecked) {
        // One Dijkstra: return path to start. When the fully-penalised return
        // ships a self-crossing, routeReturnWithVariants escalates to
        // relaxed-penalty variants and picks the best shape (extra Dijkstras
        // are spent only on the defective case).
        returnRef = buildRefTrack(segments);
        returnTrack = routeReturnWithVariants(segments, returnRef,
          s.currentMwp, startMwp, stepDeadline, result, s.totalDistance, desiredDistance, step);
        s.returnChecksPerformed++;

        // Too long → undo the RAW sub-route (no detail work paid yet) and
        // try the next ranked candidate.
        if (returnTrack != null && returnTrack.distance > 0
            && s.totalDistance + returnTrack.distance > desiredDistance * (1 + tolerance)) {
          result.addDiagnostic("step " + step + ": projected "
            + (int) (s.totalDistance + returnTrack.distance)
            + "m exceeds desired " + (int) desiredDistance + "m, trying next candidate");
          tooLongSeen = true;
          undoTentativeLeg(s, accepted);
          s.currentMwp = waypointStack.get(waypointStack.size() - 1);
          s.prevIlon = savedPrevIlon;
          s.prevIlat = savedPrevIlat;
          continue;
        }
      }

      // Length decision passed — NOW pay for detail. Phase 2 v3: upgrade
      // the committed sub-track from single-pass (fast, no per-edge
      // MessageData) to detailed via the engine's retracking pass. The
      // quality gate's paved-profile hostility check requires
      // wayKeyValues on every edge; single-pass tracks don't have them,
      // so without this step the gate would either bypass hostility
      // (under suspect-tolerance) or trip the missing-metadata floor.
      OsmTrack detailedAccepted = detailAcceptedTrack(accepted, fromMwp, refBeforeAccept, stepDeadline);
      String detailReject = null;
      if (detailedAccepted == null || detailedAccepted.distance == 0) {
        detailReject = "accepted leg could not be detailed";
      } else if (detailFidelityTooLow(detailedAccepted)) {
        detailReject = "accepted leg still lacks metadata after retrack ("
          + formatPct(RoundTripQualityGate.missingMetadataFraction(detailedAccepted)) + ")";
      } else if (pavedProfile) {
        // Phase 2 v3 hostility post-check. The scorer cannot see hostility
        // while choosing candidates (single-pass tracks lack metadata),
        // but the FINAL gate will reject any leg with a contiguous hostile
        // stretch over the cap. Checking here lets the planner move to the
        // next candidate instead of committing a hostile leg and losing
        // the whole loop. Skipped on non-paved profiles where the
        // predicate would over-flag.
        RoundTripQualityGate.HostileStretch hostileStretch =
          RoundTripQualityGate.worstHostileStretchPaved(detailedAccepted);
        if (hostileStretch.meters > RoundTripQualityGate.MAX_CONTIGUOUS_HOSTILE_METERS) {
          detailReject = "accepted leg has " + hostileStretch.meters
            + "m contiguous hostile stretch (over "
            + RoundTripQualityGate.MAX_CONTIGUOUS_HOSTILE_METERS + ")";
        }
      }
      if (detailReject != null) {
        result.addDiagnostic("step " + step + ": " + detailReject + ", trying next candidate");
        undoTentativeLeg(s, accepted);
        s.currentMwp = waypointStack.get(waypointStack.size() - 1);
        s.prevIlon = savedPrevIlon;
        s.prevIlat = savedPrevIlat;
        continue;
      }

      // Swap the detailed leg in (identical node sequence; distance can
      // shift marginally) and register its edges for reuse scoring.
      double rawLegDistance = accepted.routeDistance;
      accepted.track = detailedAccepted;
      accepted.routeDistance = detailedAccepted.distance;
      segments.set(segments.size() - 1, detailedAccepted);
      s.totalDistance += detailedAccepted.distance - rawLegDistance;
      addVisitedEdges(accepted.track, visitedEdges, s.totalDistance - accepted.routeDistance);

      // Endpoint re-anchor: detailWithFallback's fidelity fallback can
      // REROUTE the leg (toward accepted.toMwp), so the committed leg may
      // end at a different node than the raw leg the step was anchored on.
      // The pre-trial-loop code derived s.currentMwp from the DETAILED
      // track's endpoint, so match that: re-derive the step anchor and,
      // when a return was already routed from the stale anchor, redo the
      // return check from the corrected one (rare path — pays one extra
      // Dijkstra only when a fidelity reroute actually moved the endpoint;
      // without this, the next leg and the return would start at a point
      // the committed track never reaches, shipping a seam gap).
      OsmPathElement detailedEnd = detailedAccepted.nodes.get(detailedAccepted.nodes.size() - 1);
      if (detailedEnd.getILon() != lastNode.getILon()
          || detailedEnd.getILat() != lastNode.getILat()) {
        MatchedWaypoint reanchored = matchPoint(detailedEnd.getILon(), detailedEnd.getILat(), "greedy_next");
        s.currentMwp = (reanchored != null) ? reanchored : accepted.toMwp;
        waypointStack.set(waypointStack.size() - 1, s.currentMwp);
        if (returnChecked) {
          returnRef = buildRefTrack(segments);
          returnTrack = routeReturnWithVariants(segments, returnRef,
            s.currentMwp, startMwp, stepDeadline, result, s.totalDistance, desiredDistance, step);
          s.returnChecksPerformed++;
        }
      }

      if (!returnChecked || returnTrack == null || returnTrack.distance == 0) {
        // Either closure is clearly out of reach with steps to spare, or
        // the return was not routable within budget — keep the leg
        // (legacy behaviour) and let the next step / force-close handle
        // closure.
        recordAcceptedLegAttribution(result, accepted, step, trial, mixedSourceRouting,
          start.ilon, start.ilat, s.currentMwp);
        legCommitted = true;
        break;
      }

      // Recompute the closure numbers against the DETAILED leg distance
      // (the length decision above used the raw track).
      double closedDistance = s.totalDistance + returnTrack.distance;
      double error = Math.abs(closedDistance - desiredDistance) / desiredDistance;
      if (closedDistance > desiredDistance * (1 + tolerance)) {
        // The detail swap nudged the total over the cap — too long after all.
        result.addDiagnostic("step " + step + ": projected " + (int) closedDistance
          + "m exceeds desired " + (int) desiredDistance + "m after detailing, trying next candidate");
        tooLongSeen = true;
        removeVisitedEdges(accepted.track, visitedEdges);
        undoTentativeLeg(s, accepted);
        s.currentMwp = waypointStack.get(waypointStack.size() - 1);
        s.prevIlon = savedPrevIlon;
        s.prevIlat = savedPrevIlat;
        continue;
      }

      // Phase 2 v3: detail the closing return leg before either snapshot
      // or final commit — both paths feed the quality gate which needs
      // per-edge MessageData. Also re-detail when the current best
      // fallback was gate-rejected, so we keep searching for a
      // gate-accepted closure even at higher error.
      boolean needDetail = (s.bestFallback == null || error < s.bestFallback.error)
        || (error <= tolerance)
        || (s.bestFallback != null && !s.bestFallback.gateAccepted);
      if (needDetail) {
        // Same fidelity-enforced detailing as committed forward legs: a
        // failed retrack on the closing leg used to ship raw chord geometry
        // (no fallback at all here). The reroute fallback needs anti-reuse
        // poisoning against the path actually COMMITTED: returnRef was
        // built before the accepted leg was detailed (and possibly
        // rerouted by the fidelity fallback), so rebuild the ref from the
        // now-detailed segments — otherwise a return reroute could freely
        // retrace a fidelity-rerouted leg the stale ref doesn't contain.
        OsmTrack detailedReturnRef = buildRefTrack(segments);
        returnTrack = detailWithFallback("greedy-return-detail-fallback",
          returnTrack, s.currentMwp, startMwp, detailedReturnRef, stepDeadline);
      }

      // Build the closed loop and evaluate the production gate once (only
      // meaningful when the leg was detailed); reuse the verdict for both
      // fallback selection and the within-tolerance close decision.
      OsmTrack finalTrack = null;
      String reject = null;
      if (needDetail) {
        finalTrack = mergeSegmentsDetoured(segments, returnTrack);
        reportSeamGaps(segments, returnTrack, result);
        RoundTripQualityResult verdict = qualityGateVerdict(finalTrack, desiredDistance);
        reject = (verdict == null) ? "no track" : (verdict.isAccepted() ? null : verdict.getRejectionReason());
        // Geometry-fidelity guard on the closing leg: when even the
        // detailWithFallback reroute could not produce faithful geometry,
        // do not close on it — route the rejection through the existing
        // undo-and-retry machinery instead of shipping chord geometry.
        if (reject == null && detailFidelityTooLow(returnTrack)) {
          reject = "return leg geometry fidelity too low (chord "
            + LoopQualityMetrics.maxSingleNullEdgeMeters(returnTrack) + "m, missing meta "
            + formatPct(RoundTripQualityGate.missingMetadataFraction(returnTrack)) + ")";
        }
        int severity = fallbackSeverity(verdict);
        // Prefer the soundest fallback (accepted > rideable corridor > chaos)
        // even at a higher geometric error; among equal-soundness candidates
        // keep the lowest error. Ranking by error alone could latch a
        // low-error chaotic (self-intersecting) loop over a usable corridor.
        if (s.bestFallback == null
            || isBetterFallback(severity, error, s.bestFallback.severity, s.bestFallback.error)) {
          s.bestFallback = snapshotFallback(s, finalTrack, returnTrack, error, severity);
        }
      }

      // Within tolerance → close the loop
      if (error <= tolerance) {
        if (reject != null) {
          result.addDiagnostic("closed loop rejected at step " + step
            + ": " + reject + ", trying next candidate");
          s.closureRejections++;
          if (poolHealth != null) poolHealth.recordClosureRejection();
          removeVisitedEdges(accepted.track, visitedEdges);
          undoTentativeLeg(s, accepted);
          s.currentMwp = waypointStack.get(waypointStack.size() - 1);
          s.prevIlon = savedPrevIlon;
          s.prevIlat = savedPrevIlat;
          continue;
        }

        recordAcceptedLegAttribution(result, accepted, step, trial, mixedSourceRouting,
          start.ilon, start.ilat, s.currentMwp);
        addVisitedEdges(returnTrack, visitedEdges, s.totalDistance);
        segments.add(returnTrack);
        s.totalDistance += returnTrack.distance; // keep consistent with segments
        populateResult(result, finalTrack, waypointStack, startMwp, segments, desiredDistance, startDirection);
        result.setTotalDistanceMeters((int) closedDistance);
        result.setWithinTolerance(true);
        result.addDiagnostic("loop closed at step " + step
          + ", total=" + (int) closedDistance + "m"
          + ", error=" + String.format("%.1f%%", error * 100));
        stampExit(s);
        return TrialOutcome.CLOSED;
      }

      // Between (1-tol) and (1+tol) but not within tol → too short:
      // keep the leg and continue with the next step.
      recordAcceptedLegAttribution(result, accepted, step, trial, mixedSourceRouting,
        start.ilon, start.ilat, s.currentMwp);
      legCommitted = true;
      break;
    }

    return legCommitted ? TrialOutcome.COMMITTED
      : (tooLongSeen ? TrialOutcome.EXHAUSTED_TOO_LONG : TrialOutcome.EXHAUSTED);
  }

  /**
   * Outcome evaluator for a plan that never closed within tolerance: adopt the
   * soundest fallback snapshot, else force-close, else report no loop. Every
   * path stamps exit telemetry.
   */
  private RoundTripResult finishPlan(GreedyPlanSession s) {
    final RoundTripResult result = s.result;
    final List<OsmTrack> segments = s.segments;
    final List<MatchedWaypoint> waypointStack = s.waypointStack;
    final MatchedWaypoint startMwp = s.startMwp;
    final double desiredDistance = s.desiredDistance;
    final double startDirection = s.startDirection;
    if (s.bestFallback != null) {
      // Restore the counters captured with the snapshot — the live fields
      // describe the abandoned plan state, not the geometry being shipped.
      s.acceptedIsoLegs = s.bestFallback.isoLegs;
      s.acceptedNonIsoLegs = s.bestFallback.nonIsoLegs;
      s.acceptedQuotaInjectedLegs = s.bestFallback.quotaInjectedLegs;
      populateResult(result, s.bestFallback.track, s.bestFallback.waypointStack,
        startMwp, s.bestFallback.legTracks, desiredDistance, startDirection);
      result.setTotalDistanceMeters(s.bestFallback.track.distance);
      result.setWithinTolerance(false);
      RoundTripQualityResult verdict = qualityGateVerdict(s.bestFallback.track, desiredDistance);
      String reject = (verdict == null || verdict.isAccepted()) ? null : verdict.getRejectionReason();
      String reason = "best error=" + String.format("%.1f%%", s.bestFallback.error * 100);
      // Keep-when-forced: the soundest loop the planner could find is a rideable
      // same-way-back corridor and nothing clean exists (else s.bestFallback would
      // be rank-0 accepted). Don't degrade it into oblivion — flag it so the
      // request gate accepts the forced corridor (disclosed) instead of dropping
      // the route or shipping a chaotic alternative.
      boolean forcedCorridor = s.bestFallback.severity == 1 && isForcedCorridorVerdict(verdict);
      result.setForcedCorridorAccepted(forcedCorridor);
      if (forcedCorridor) {
        result.setFallbackReason("forced corridor (no clean alternative): " + reject + "; " + reason);
      } else {
        result.setFallbackReason(reject == null ? reason : DEGRADED_FALLBACK_PREFIX + reject + "; " + reason);
      }
      stampExit(s);
      return result;
    }

    // Last resort: force-close. Allow up to 10s here even past the planner
    // deadline — without a closing leg the planner has nothing usable to
    // return. The grace is bounded by the REQUEST deadline (plus a small
    // salvage margin): pre-budget-threading this grace was uncapped and let a
    // plan overrun the request budget it never knew about.
    if (!segments.isEmpty()) {
      long forceCloseDeadline = Math.max(s.deadline, System.currentTimeMillis() + SUB_ROUTE_TIMEOUT_MS);
      if (externalDeadline != Long.MAX_VALUE) {
        forceCloseDeadline = Math.min(forceCloseDeadline,
          externalDeadline + FORCE_CLOSE_GRACE_PAST_BUDGET_MS);
      }
      OsmTrack returnTrack = timedFindTrack("greedy-force-close",
        s.currentMwp, startMwp, buildRefTrack(segments), forceCloseDeadline);
      s.returnChecksPerformed++;
      if (returnTrack != null && returnTrack.distance > 0) {
        returnTrack = router.retrackForDetail(returnTrack, s.currentMwp, startMwp, null);
        segments.add(returnTrack);
        OsmTrack finalTrack = mergeSegmentsDetoured(segments, null);
        reportSeamGaps(segments, null, result);
        populateResult(result, finalTrack, waypointStack, startMwp, segments, desiredDistance, startDirection);
        result.setTotalDistanceMeters(finalTrack.distance);
        result.setWithinTolerance(false);
        String reject = qualityGateReason(finalTrack, desiredDistance);
        result.setFallbackReason(reject == null ? "forced closure" : DEGRADED_FALLBACK_PREFIX + reject + "; forced closure");
        stampExit(s);
        return result;
      }
    }

    result.setFallbackReason("could not build any loop");
    stampExit(s);
    return result;
  }

  /** Exit stamps shared by every plan() outcome path: the budget-pressure
   *  diagnostic plus the counter telemetry, in the historical order. */
  private void stampExit(GreedyPlanSession s) {
    stampBudgetDiagnostic(s);
    stampTelemetry(s.result, s.planStart, s.candidatesGenerated, s.candidatesRouted,
      s.returnChecksPerformed, s.routedIso, s.routedNonIso,
      s.acceptedIsoLegs, s.acceptedNonIsoLegs);
  }

  private static double closedDistanceError(double totalDistance, int returnDistance, double desiredDistance) {
    return desiredDistance > 0
      ? Math.abs(totalDistance + returnDistance - desiredDistance) / desiredDistance : 0;
  }

  private static boolean sameNodeSequence(OsmTrack a, OsmTrack b) {
    if (a.nodes == null || b.nodes == null || a.nodes.size() != b.nodes.size()) {
      return false;
    }
    for (int i = 0; i < a.nodes.size(); i++) {
      OsmPathElement x = a.nodes.get(i);
      OsmPathElement y = b.nodes.get(i);
      if (x.getILon() != y.getILon() || x.getILat() != y.getILat()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Route the closing leg under the full anti-reuse penalty; only when that return
   * crosses the committed path (the teardrop fingerprint), re-route with the
   * refTrack penalty relaxed ({@link #RETURN_VARIANT_FACTORS}) and pick the best
   * variant by (crossings, reuse fraction, distance error) lexicographically.
   * Variants retracing more than {@link #MAX_VARIANT_REUSE_FRACTION} are discarded.
   * The clean common case costs zero extra Dijkstras.
   */
  private OsmTrack routeReturnWithVariants(List<OsmTrack> segments, OsmTrack returnRef,
                                           MatchedWaypoint fromMwp, MatchedWaypoint toMwp,
                                           long deadline, RoundTripResult result,
                                           double totalDistance, double desiredDistance, int step) {
    OsmTrack base = timedFindTrack("greedy-return", fromMwp, toMwp, returnRef, deadline);
    if (base == null || base.distance <= 0) {
      return base;
    }
    List<OsmPathElement> prefix =
      segments.isEmpty() ? null : mergeSegmentsNoMap(segments, null).nodes;
    int baseCrossings = countTentativeSelfIntersections(prefix, base);
    if (baseCrossings == 0) {
      return base;
    }

    List<OsmTrack> variants = new ArrayList<>();
    List<double[]> scores = new ArrayList<>(); // {factor, crossings, reuseFraction, distError}
    variants.add(base);
    scores.add(new double[]{1.0, baseCrossings, reuseFraction(base, returnRef),
      closedDistanceError(totalDistance, base.distance, desiredDistance)});

    RoutingContext rc = ctx.routingContext();
    for (double factor : RETURN_VARIANT_FACTORS) {
      OsmTrack variant;
      double saved = rc.refTrackCostFactor;
      try {
        rc.refTrackCostFactor = factor;
        variant = timedFindTrack("greedy-return-relaxed", fromMwp, toMwp, returnRef, deadline);
      } finally {
        rc.refTrackCostFactor = saved;
      }
      if (variant == null || variant.distance <= 0 || sameNodeSequence(variant, base)) {
        continue;
      }
      double reuse = reuseFraction(variant, returnRef);
      if (reuse > MAX_VARIANT_REUSE_FRACTION) {
        continue;
      }
      variants.add(variant);
      scores.add(new double[]{factor, countTentativeSelfIntersections(prefix, variant),
        reuse, closedDistanceError(totalDistance, variant.distance, desiredDistance)});
    }

    int best = 0;
    for (int i = 1; i < scores.size(); i++) {
      double[] a = scores.get(i);
      double[] b = scores.get(best);
      if (a[1] != b[1] ? a[1] < b[1] : (a[2] != b[2] ? a[2] < b[2] : a[3] < b[3])) {
        best = i;
      }
    }
    if (best != 0) {
      double[] s = scores.get(best);
      String msg = "return variant factor=" + s[0] + " wins: crossings " + baseCrossings
        + " -> " + (int) s[1] + ", reuse " + (int) (s[2] * 100) + "%, distErr "
        + Math.round(s[3] * 100) + "%";
      result.addDiagnostic("step " + step + ": " + msg);
      io.logInfo("greedy " + msg);
    }
    return variants.get(best);
  }

  private OsmTrack detailAcceptedTrack(ScoredRoute accepted, MatchedWaypoint fromMwp,
                                       OsmTrack refTrack, long deadline) {
    return detailWithFallback("greedy-sub-detail-fallback",
      accepted.track, fromMwp, accepted.toMwp, refTrack, deadline);
  }

  /**
   * Detail-retrack {@code leg}; when fidelity is too low (retrack fell back to raw
   * geometry — see {@link #detailFidelityTooLow}), re-route once and retrack that.
   * Returns the best track; callers re-check fidelity and decide to commit, retry,
   * or accept best-effort.
   */
  private OsmTrack detailWithFallback(String name, OsmTrack leg, MatchedWaypoint fromMwp,
                                      MatchedWaypoint toMwp, OsmTrack refTrack, long deadline) {
    OsmTrack detailed = router.retrackForDetail(leg, fromMwp, toMwp, refTrack);
    if (!detailFidelityTooLow(detailed)) {
      return detailed;
    }

    OsmTrack rerouted = timedFindTrack(name, fromMwp, toMwp, refTrack, deadline);
    if (rerouted == null || rerouted.distance == 0) {
      return detailed;
    }
    return router.retrackForDetail(rerouted, fromMwp, toMwp, refTrack);
  }

  public OsmTrack timedFindTrack(String name, MatchedWaypoint from, MatchedWaypoint to,
                                  OsmTrack refTrack, long deadline) {
    long now = System.currentTimeMillis();
    long remaining = deadline - now;
    if (remaining < MIN_FIND_TRACK_MS) {
      io.logInfo(name + ": deadline exceeded, skipping (remaining " + remaining + "ms)");
      return null;
    }
    // Distance-scaled per-call cap (see FIND_TRACK_BASE_BUDGET_MS): a short
    // candidate leg is never allowed to burn the flat 10s worst case.
    double airKm = (from != null && to != null && from.crosspoint != null && to.crosspoint != null)
      ? CheapRuler.distance(from.crosspoint.ilon, from.crosspoint.ilat,
          to.crosspoint.ilon, to.crosspoint.ilat) / 1000.0
      : Double.MAX_VALUE;
    long scaledCap = airKm == Double.MAX_VALUE ? SUB_ROUTE_TIMEOUT_MS
      : Math.min(SUB_ROUTE_TIMEOUT_MS,
          FIND_TRACK_BASE_BUDGET_MS + (long) (FIND_TRACK_BUDGET_MS_PER_AIR_KM * airKm));
    long budget = Math.min(scaledCap, remaining);
    try {
      return router.findTrackTimed(name, from, to, refTrack, budget);
    } catch (IllegalArgumentException | RoutingIslandException e) {
      // A watchdog kill surfaces as IllegalArgumentException; propagate it so
      // plan() aborts immediately instead of burning the remaining attempts
      // re-throwing-and-swallowing the same kill on every subsequent leg.
      if (router.isTerminated()) {
        throw e;
      }
      // Treat an islanded / unroutable leg as "no track for this leg" (same as
      // retrackForDetail does) so the planner falls back to its best-so-far loop
      // instead of letting the exception abort plan() and discard all telemetry.
      io.logInfo(name + ": no track (" + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
      return null;
    }
  }

  /**
   * Whether a detailed leg is unfit to commit, on two scopes:
   * <ul>
   *   <li><b>Chord fingerprint (all profiles)</b> — a long null-tag edge means the
   *       detail pass fell back to raw geometry, shipping a straight cut across
   *       terrain (the "beeline"). Many SHORT null edges are fine, so the
   *       fingerprint, not the fraction, is the criterion: a profile-agnostic
   *       fraction roughly doubled matrix runtime via needless gravel reroutes.</li>
   *   <li><b>Metadata coverage (paved only)</b> — the gate's hostility check needs
   *       verifiable tags; unverifiable distance over the ceiling is a paved
   *       safety concern.</li>
   * </ul>
   */
  private boolean detailFidelityTooLow(OsmTrack track) {
    if (LoopQualityMetrics.maxSingleNullEdgeMeters(track) > MAX_UNDETAILED_EDGE_METERS) {
      return true;
    }
    return pavedProfile
      && RoundTripQualityGate.missingMetadataFraction(track) > RoundTripQualityGate.MAX_HOSTILE_FRACTION;
  }

  private static String formatPct(double fraction) {
    return String.format("%.1f%%", fraction * 100.0);
  }

  // --- Waypoint matching ---

  /**
   * Profile-aware variant of {@link #matchPoint} for candidate vias: delegates to
   * {@code RoutingEngine#profileAwareMatchPoint} (probe rings, cost-factor scored),
   * same null-on-failure contract, falling back to the plain nearest match on
   * throw or miss so candidate handling is never stricter than before.
   */
  private MatchedWaypoint matchCandidatePointProfileAware(int ilon, int ilat) {
    try {
      MatchedWaypoint mwp = router.profileAwareMatchPoint(ilon, ilat, "greedy_to", 2000);
      if (mwp != null) return mwp;
    } catch (Exception e) {
      io.logInfo("matchCandidatePointProfileAware failed: " + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
    return matchPoint(ilon, ilat, "greedy_to");
  }

  private MatchedWaypoint matchPoint(int ilon, int ilat, String name) {
    try {
      router.resetCache(false);
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(ilon, ilat);
      mwp.name = name;
      List<MatchedWaypoint> mwpList = new ArrayList<>();
      mwpList.add(mwp);
      router.matchWaypointsToNodes(mwpList, 2000);
      if (mwp.crosspoint == null || mwp.node1 == null || mwp.node2 == null) {
        return null;
      }
      return mwp;
    } catch (Exception e) {
      // Return null on ANY failure so every caller's graceful recovery still
      // works: the start site (~line 211) gives up this attempt, the candidate
      // loop (~321) skips this candidate and tries the next, and the next-step
      // site (~532) falls back to the accepted waypoint. Do NOT rethrow — a
      // single missing-data candidate point must not abort the whole leg. The
      // cause (incl. data-availability IllegalArgumentException from NodesCache)
      // is logged so it is not silently lost when info logging is enabled.
      io.logInfo("matchPoint(" + name + ") failed: " + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      return null;
    }
  }

  // --- Track management ---

  private OsmTrack buildRefTrack(List<OsmTrack> segments) {
    if (segments.isEmpty()) return null;
    return mergeSegments(segments, null);
  }

  /**
   * Count self-intersections of the committed prefix + one candidate leg.
   *
   * <p>SAFE-4: {@code committedPrefixNodes} is the merged committed segments' node
   * list, built once per attempt and shared read-only. We copy it and append only
   * this candidate's nodes (replicating {@link #appendTrack}'s first-node dedupe),
   * so the sequence is element-identical to
   * {@code mergeSegmentsNoMap(segments, candidate)} and the count is bit-identical.
   * The shared list is never mutated.
   *
   * <p>SAFE-1: the tentative track is consumed only by
   * {@link RoundTripQualityGate#countSelfIntersections}, which reads
   * {@code track.nodes} exclusively, so no {@code nodesMap} build is needed.
   */
  private int countTentativeSelfIntersections(List<OsmPathElement> committedPrefixNodes,
                                              OsmTrack candidateSegment) {
    if (candidateSegment == null || candidateSegment.nodes == null
        || candidateSegment.nodes.size() < 2) {
      return 0;
    }
    if (committedPrefixNodes == null || committedPrefixNodes.isEmpty()) {
      return RoundTripQualityGate.countSelfIntersections(candidateSegment);
    }
    OsmTrack tentative = new OsmTrack();
    tentative.nodes = new ArrayList<>(
      committedPrefixNodes.size() + candidateSegment.nodes.size());
    tentative.nodes.addAll(committedPrefixNodes);
    appendNodesDeduped(tentative.nodes, candidateSegment.nodes);
    return RoundTripQualityGate.countSelfIntersections(tentative);
  }

  /**
   * Append {@code source} onto {@code targetNodes}, skipping the first source node
   * when it duplicates the current tail — the exact node-dedupe {@link #appendTrack}
   * performs (distance/ascend/cost irrelevant; the only consumer reads nodes).
   */
  // Package-private for unit testing the dedupe contract (SAFE-4 parity).
  static void appendNodesDeduped(List<OsmPathElement> targetNodes,
                                 List<OsmPathElement> source) {
    boolean first = true;
    for (OsmPathElement node : source) {
      if (first && !targetNodes.isEmpty()) {
        OsmPathElement last = targetNodes.get(targetNodes.size() - 1);
        if (last.getILon() == node.getILon() && last.getILat() == node.getILat()) {
          first = false;
          continue;
        }
      }
      first = false;
      targetNodes.add(node);
    }
  }

  /**
   * Leg-junction seam gap above which the merged loop carries a splice defect.
   * Adjacent legs share their junction node by construction, so a larger jump
   * means some machinery (via relocation, cached-leg reuse, repair splice) glued
   * non-adjacent endpoints, shipping a silent straight edge no marker sees.
   */
  static final int MAX_SEAM_GAP_METERS = 100;

  /**
   * Leg-junction contiguity check. Returns one description per seam whose endpoints
   * differ by more than {@link #MAX_SEAM_GAP_METERS}. Detection-only (the
   * beeline-gate lesson: a geometric hard gate fired 1283x/run on legitimate
   * chords) — callers log, never reject; by construction any hit is a planner bug.
   */
  static List<String> seamGapsMeters(List<OsmTrack> segments, OsmTrack finalSegment) {
    List<String> gaps = new ArrayList<>();
    OsmPathElement prevTail = null;
    int leg = 0;
    List<OsmTrack> all = new ArrayList<>(segments);
    if (finalSegment != null) {
      all.add(finalSegment);
    }
    for (OsmTrack seg : all) {
      leg++;
      if (seg == null || seg.nodes == null || seg.nodes.isEmpty()) {
        continue;
      }
      OsmPathElement head = seg.nodes.get(0);
      if (prevTail != null
          && (prevTail.getILon() != head.getILon() || prevTail.getILat() != head.getILat())) {
        int gap = prevTail.calcDistance(head);
        if (gap > MAX_SEAM_GAP_METERS) {
          gaps.add("seam before leg " + leg + ": " + gap + "m jump between leg endpoints");
        }
      }
      prevTail = seg.nodes.get(seg.nodes.size() - 1);
    }
    return gaps;
  }

  /** Log + attach diagnostics for any seam gaps in the final loop assembly. */
  private void reportSeamGaps(List<OsmTrack> segments, OsmTrack finalSegment, RoundTripResult result) {
    for (String gap : seamGapsMeters(segments, finalSegment)) {
      result.addDiagnostic("seam-contiguity: " + gap);
      if (io != null) {
        io.logInfo("greedy seam-contiguity defect: " + gap);
      }
    }
  }

  /**
   * Concatenate {@code segments} (then optional {@code finalSegment}) into one
   * track WITHOUT the node lookup map. Use this when only reading the node
   * sequence; callers doing {@code containsNode}/{@code nodesMap} lookups need
   * {@link #mergeSegments}.
   */
  private OsmTrack mergeSegmentsNoMap(List<OsmTrack> segments, OsmTrack finalSegment) {
    OsmTrack merged = new OsmTrack();
    for (OsmTrack seg : segments) {
      appendTrack(merged, seg);
    }
    if (finalSegment != null) {
      appendTrack(merged, finalSegment);
    }
    return merged;
  }

  private OsmTrack mergeSegments(List<OsmTrack> segments, OsmTrack finalSegment) {
    OsmTrack merged = mergeSegmentsNoMap(segments, finalSegment);
    merged.buildMap();
    return merged;
  }

  /**
   * Like {@link #mergeSegments} but also carries each leg's detour data onto the
   * merged loop, so the result has the {@code detourMap}
   * {@link OsmTrack#processVoiceHints} needs. Metadata-only merge — node geometry
   * is identical to {@link #mergeSegments}, so a gate-validated track stays valid.
   * Used only for the final result track.
   */
  private OsmTrack mergeSegmentsDetoured(List<OsmTrack> segments, OsmTrack finalSegment) {
    OsmTrack merged = new OsmTrack();
    for (OsmTrack seg : segments) {
      appendTrack(merged, seg);
      merged.mergeDetoursFrom(seg);
    }
    if (finalSegment != null) {
      appendTrack(merged, finalSegment);
      merged.mergeDetoursFrom(finalSegment);
    }
    merged.buildMap();
    return merged;
  }

  private void appendTrack(OsmTrack target, OsmTrack source) {
    if (source.nodes == null) return;
    boolean first = true;
    for (OsmPathElement node : source.nodes) {
      if (first && !target.nodes.isEmpty()) {
        OsmPathElement last = target.nodes.get(target.nodes.size() - 1);
        if (last.getILon() == node.getILon() && last.getILat() == node.getILat()) {
          first = false;
          continue;
        }
      }
      first = false;
      target.nodes.add(node);
    }
    target.distance += source.distance;
    target.ascend += source.ascend;
    target.cost += source.cost;
  }

  // --- Visited edge tracking (ref-counted) ---

  private void addVisitedEdges(OsmTrack track, VisitedEdgeStore edges,
                               double trackStartCumDist) {
    if (track.nodes == null || track.nodes.size() < 2) return;
    double cumDist = trackStartCumDist;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = a.calcDistance(b);
      long key = LoopGeometry.edgeKey(a, b);
      if (edges.count(key) == 0) {
        // First visit ever — record the segment midpoint as the first-visit
        // cumulative distance, used downstream for boundary-proximity weighting.
        edges.setFirstPos(key, cumDist + segLen / 2);
      }
      edges.increment(key);
      cumDist += segLen;
    }
  }

  private void removeVisitedEdges(OsmTrack track, VisitedEdgeStore edges) {
    if (track.nodes == null || track.nodes.size() < 2) return;
    for (int i = 1; i < track.nodes.size(); i++) {
      long key = LoopGeometry.edgeKey(track.nodes.get(i - 1), track.nodes.get(i));
      int count = edges.count(key);
      if (count == 0) continue;
      if (count <= 1) {
        edges.remove(key);
      } else {
        edges.decrement(key);
        // firstPos stays — earlier visit(s) still present in the route.
      }
    }
  }

  /**
   * Position-weighted distance-share reuse ratio: sum of (segment-length ×
   * position-penalty) over reused edges / total length. Matches
   * {@link LoopQualityMetrics}'s distance-weighted definition when
   * {@link #BOUNDARY_PROXIMITY_FRAC}=0, plus a boundary-proximity multiplier —
   * reuse within {@link #BOUNDARY_PROXIMITY_FRAC} of loop start/end gets full
   * weight, mid-loop reuse {@link #MID_LOOP_REUSE_WEIGHT} (back-and-forth near
   * start/end is more annoying than mid-loop).
   *
   * @param segLens SAFE-5 precomputed per-segment distances, or {@code null} to
   *                compute inline; when non-null must equal {@code calcDistance}
   *                for every segment.
   */
  private double computeTrackVisitedRatio(OsmTrack track, VisitedEdgeStore edges,
                                          double trackStartCumDist, double desiredDistance,
                                          int[] segLens) {
    if (edges.isEmpty() || track.nodes == null || track.nodes.size() < 2) return 0.0;
    double total = 0;
    double weightedReuse = 0;
    double cumDist = trackStartCumDist;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = (segLens != null) ? segLens[i - 1] : a.calcDistance(b);
      double midPos = cumDist + segLen / 2;
      total += segLen;
      long key = LoopGeometry.edgeKey(a, b);
      // A present key always has its firstPos recorded (setFirstPos precedes
      // increment on first visit), so this reproduces the former
      // containsKey-count + non-null-firstPos path exactly; firstPos may be
      // 0.0 (1m first edge) and is still "present" via the occupancy flag.
      if (edges.containsKey(key)) {
        double posWeight = boundaryProximityWeight(edges.firstPos(key), midPos, desiredDistance);
        weightedReuse += segLen * posWeight;
      }
      cumDist += segLen;
    }
    return total > 0 ? weightedReuse / total : 0.0;
  }

  /** Fraction of desired distance defining "near start or end" for back-and-forth weighting. */
  private static final double BOUNDARY_PROXIMITY_FRAC = 0.20;
  /** Reuse weight for mid-loop overlap (vs 1.0 for near-boundary). */
  private static final double MID_LOOP_REUSE_WEIGHT = 0.5;

  /**
   * 1.0 when either {@code firstPos} or {@code currentPos} is within
   * {@link #BOUNDARY_PROXIMITY_FRAC} of loop start (0) or end (desiredDistance);
   * {@link #MID_LOOP_REUSE_WEIGHT} when both are mid-loop. Boundary retraces are
   * visible/annoying; mid-loop crossings are often unavoidable.
   *
   * <p>Deliberately neutral per-edge weights: an earlier attempt to push the
   * scenic-stem-vs-backtrack distinction down here regressed constrained networks
   * (Dreieich — raising the mid-loop penalty pushed the planner off the only paved
   * loops onto profile-rejected track terrain). The final {@link ReuseClassifier}
   * gate makes that semantic call; this is only a steering hint.
   */
  static double boundaryProximityWeight(double firstPos, double currentPos, double desiredDistance) {
    if (desiredDistance <= 0) return 1.0;
    double firstFrac = firstPos / desiredDistance;
    double currentFrac = currentPos / desiredDistance;
    double firstBoundary = Math.min(Math.max(0, firstFrac), Math.max(0, 1 - firstFrac));
    double currentBoundary = Math.min(Math.max(0, currentFrac), Math.max(0, 1 - currentFrac));
    double minBoundary = Math.min(firstBoundary, currentBoundary);
    return (minBoundary < BOUNDARY_PROXIMITY_FRAC) ? 1.0 : MID_LOOP_REUSE_WEIGHT;
  }

  /**
   * SAFE-5: per-segment integer distances of {@code track}
   * ({@code result[i-1] == nodes[i-1].calcDistance(nodes[i])}). Shared by
   * {@link #computeTrackVisitedRatio} and
   * {@link RoundTripQualityGate#worstContiguousCostlyMetersForScorer} so the
   * {@link CheapRuler} distance is computed once.
   */
  private static int[] segmentDistances(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) {
      return new int[0];
    }
    int[] lens = new int[track.nodes.size() - 1];
    for (int i = 1; i < track.nodes.size(); i++) {
      lens[i - 1] = track.nodes.get(i - 1).calcDistance(track.nodes.get(i));
    }
    return lens;
  }



  /**
   * Convert the waypoint stack to a closed-loop list of OsmNodeNamed
   * [start, wp1, …, closing]; the closing point copies start to form the return leg.
   */
  private List<OsmNodeNamed> buildLoopWaypoints(List<MatchedWaypoint> stack) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    // First waypoint = road-snapped start position (crosspoint, not raw user position).
    // Using the crosspoint avoids beeline segments when the user's click position
    // is far from a road (park, water, etc.).
    MatchedWaypoint startMwp = stack.get(0);
    OsmNodeNamed from = new OsmNodeNamed(new OsmNode(
      startMwp.crosspoint.getILon(), startMwp.crosspoint.getILat()));
    from.name = "from";
    wps.add(from);
    // Intermediate waypoints from the stack (skip first which is start)
    for (int i = 1; i < stack.size(); i++) {
      MatchedWaypoint mwp = stack.get(i);
      OsmNodeNamed via = new OsmNodeNamed(new OsmNode(
        mwp.crosspoint.getILon(), mwp.crosspoint.getILat()));
      via.name = "via" + i;
      wps.add(via);
    }
    // Closing waypoint = same road-snapped start position
    OsmNodeNamed to = new OsmNodeNamed(new OsmNode(
      startMwp.crosspoint.getILon(), startMwp.crosspoint.getILat()));
    to.name = "to";
    wps.add(to);
    return wps;
  }

  /**
   * Pre-matched waypoints for the final routing pass, preserving
   * node1/node2/crosspoint from greedy matching so doRouting() skips re-matching
   * and reuses the same road segments. Start and closing waypoints are re-matched
   * from the original start MWP.
   */
  List<MatchedWaypoint> buildMatchedWaypoints(
    List<MatchedWaypoint> stack, MatchedWaypoint startMwp) {

    List<MatchedWaypoint> mwps = new ArrayList<>();

    // Start point — use original match
    MatchedWaypoint fromMwp = copyMatchedWaypoint(startMwp, "from");
    mwps.add(fromMwp);

    // Intermediate waypoints — preserve exact matching from greedy planning
    for (int i = 1; i < stack.size(); i++) {
      MatchedWaypoint mwp = stack.get(i);
      MatchedWaypoint viaMwp = copyMatchedWaypoint(mwp, "via" + i);
      // Planner-placed via, not a user waypoint: the engine's via-pinned spur
      // cleanup (removeMicroDetours / isNearGeneratedWaypoint) keys on this
      // flag — the WAYPOINT algorithm's "rt*" name convention does not apply
      // to greedy vias, so without the flag the relaxed-ratio and teardrop
      // bands never activate on greedy-adopted loops.
      viaMwp.generated = true;
      mwps.add(viaMwp);
    }

    // Closing point — same match as start
    MatchedWaypoint toMwp = copyMatchedWaypoint(startMwp, "to");
    mwps.add(toMwp);

    return mwps;
  }

  MatchedWaypoint copyMatchedWaypoint(MatchedWaypoint src, String name) {
    MatchedWaypoint copy = new MatchedWaypoint();
    copy.node1 = new OsmNode(src.node1.ilon, src.node1.ilat);
    copy.node2 = new OsmNode(src.node2.ilon, src.node2.ilat);
    // Snap to a graph node — mid-edge crosspoints cause leg gaps because
    // routing reaches the nearest node, not the interpolated position.
    OsmNode snapped = snapToNearest(src.crosspoint, copy.node1, copy.node2);
    copy.crosspoint = new OsmNode(snapped.ilon, snapped.ilat);
    // waypoint == crosspoint keeps RoutingEngine#matchWaypointsToNodes from
    // taking the dynamic beeline-insertion path (gated on snap > catchingRange).
    copy.waypoint = new OsmNode(snapped.ilon, snapped.ilat);
    copy.name = name;
    // Round-trip no-beeline invariant: greedy points must never be DIRECT.
    copy.wpttype = MatchedWaypoint.WAYPOINT_TYPE_SHAPING;
    return copy;
  }

  private OsmNode snapToNearest(OsmNode crosspoint, OsmNode node1, OsmNode node2) {
    int d1 = crosspoint.calcDistance(node1);
    int d2 = crosspoint.calcDistance(node2);
    return d1 <= d2 ? node1 : node2;
  }

  private DirectionPreference nearestDirectionPreference(double bearing) {
    bearing = CheapAngleMeter.normalize(bearing);
    DirectionPreference best = DirectionPreference.ANY;
    double minDiff = Double.MAX_VALUE;
    for (DirectionPreference dp : DirectionPreference.values()) {
      if (dp == DirectionPreference.ANY) continue;
      double diff = CheapAngleMeter.getDifferenceFromDirection(dp.bearing, bearing);
      if (diff < minDiff) {
        minDiff = diff;
        best = dp;
      }
    }
    return best;
  }

  /**
   * Combine the routed scorer score with cost-per-meter so selection weighs both
   * route shape (reuse, distance, feasibility) and road quality (cost).
   */
  static double combinedRoutedScore(double scorerScore, double costPerMeter) {
    return scorerScore + COST_PER_METER_WEIGHT * costPerMeter;
  }

  /**
   * Capture an immutable view of the fallback candidate so later mutations of
   * {@code segments} / {@code waypointStack} do not desync the track from the
   * recorded waypoints and leg list.
   */
  /**
   * Fallback-selection rule: a candidate closed loop replaces the incumbent when
   * it is gate-accepted and the incumbent is not (any error), or — same gate
   * verdict — when its geometric error is lower.
   *
   * <p><b>Two-state only:</b> this overload maps {@code accepted=false} to chaos
   * rank (2) and cannot express the middle tier (severity 1 = sound corridor);
   * callers needing the full three-tier preference must use the {@code int}
   * overload below.
   */
  static boolean isBetterFallback(boolean candidateAccepted, double candidateError,
                                  boolean incumbentAccepted, double incumbentError) {
    return isBetterFallback(candidateAccepted ? 0 : 2, candidateError,
      incumbentAccepted ? 0 : 2, incumbentError);
  }

  /**
   * Prefer the lower soundness rank (accepted &gt; sound corridor &gt; chaos);
   * among equal ranks, the lower geometric error. Keeps a rideable same-way-back
   * loop as fallback instead of latching a low-error but chaotic loop.
   */
  static boolean isBetterFallback(int candidateSeverity, double candidateError,
                                  int incumbentSeverity, double incumbentError) {
    if (candidateSeverity != incumbentSeverity) {
      return candidateSeverity < incumbentSeverity;
    }
    return candidateError < incumbentError;
  }

  private Snapshot snapshotFallback(GreedyPlanSession s, OsmTrack track, OsmTrack returnTrack,
                                    double error, int severity) {
    Snapshot snap = new Snapshot();
    snap.track = track;
    snap.waypointStack = new ArrayList<>(s.waypointStack);
    snap.legTracks = new ArrayList<>(s.segments);
    snap.legTracks.add(returnTrack);
    snap.error = error;
    snap.severity = severity;
    snap.gateAccepted = severity == 0;
    // Capture the attribution counters WITH the geometry: the tentative leg in
    // this snapshot may be undone right after, and a plan that ships the
    // snapshot must report the counters of the shipped loop, not of the
    // abandoned plan state.
    snap.isoLegs = s.acceptedIsoLegs;
    snap.nonIsoLegs = s.acceptedNonIsoLegs;
    snap.quotaInjectedLegs = s.acceptedQuotaInjectedLegs;
    return snap;
  }

  /** Fallback snapshot of a not-within-tolerance closed loop (package-private:
   *  {@link GreedyPlanSession#bestFallback} holds the best one per plan). */
  static final class Snapshot {
    OsmTrack track;
    List<MatchedWaypoint> waypointStack;
    List<OsmTrack> legTracks;
    double error;
    boolean gateAccepted;
    /** Fallback soundness rank — see {@link #fallbackSeverity}. */
    int severity;
    /** Leg-attribution counters at snapshot time (they describe this geometry). */
    int isoLegs;
    int nonIsoLegs;
    int quotaInjectedLegs;
  }

  /**
   * A routed candidate. Package-private so unit tests can construct instances and
   * verify candidate-list ordering.
   */
  static final class ScoredRoute {
    OsmTrack track;
    MatchedWaypoint toMwp;
    double routeDistance;
    double visitedRatio;
    /** True iff this leg was selected from an iso-derived candidate. */
    boolean fromIsoCandidate;
    /** True iff the candidate only held its routed slot via source-quota injection. */
    boolean fromQuotaInjection;
    /** True iff the routed re-score's return estimate was oracle-backed (vs EMA fallback). */
    boolean oracleBackedReturn;
    /**
     * Final routed score (lower is better) after {@link #combinedRoutedScore} and
     * the partial self-intersection penalty. Sorts the per-step candidate list.
     */
    double routedScore;
    /** Index of this candidate in the per-step trial loop (0-based). */
    int candidateIndex;
    /** Tentative self-intersections of the routed leg against committed segments. */
    int tentativeSelfIntersections;
    /**
     * Longest contiguous hostile stretch in the routed leg (meters), via
     * {@link RoundTripQualityGate#worstContiguousHostileMetersPaved}. Sentinel
     * {@code -1} on non-paved profiles where the predicate would over-flag.
     */
    int routedLegWorstHostileMeters;
  }

  /**
   * Sort routed candidates ascending by {@link ScoredRoute#routedScore} (lower =
   * better). Stable, so equal scores keep insertion order (the legacy
   * first-best-wins tie-break). Package-private for unit testing.
   */
  static void sortByRoutedScore(List<ScoredRoute> candidates) {
    Collections.sort(candidates, BY_ROUTED_SCORE);
  }
}
