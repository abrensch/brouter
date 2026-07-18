package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;

/**
 * All mutable state of ONE {@link GreedyRoundTripPlanner#plan} invocation —
 * the "per-plan session" of the internal planner split (code-review finding
 * #2). Pure state holder: every field is written by the planner's plan-phase
 * methods ({@code planStep} / candidate round / trial loop / outcome tails),
 * never by anything outside the planner. Planner CONFIGURATION (scorer,
 * provider, budgets, pool health, oracle) deliberately stays on
 * {@link GreedyRoundTripPlanner} — a session is one plan run over that fixed
 * configuration.
 *
 * <p>Historically this state was split between ~25 {@code plan()} locals and
 * 7 planner instance fields; the instance fields silently made a planner
 * single-use (a second {@code plan()} call would inherit stale counters).
 * Centralizing them here makes the per-plan lifetime explicit and each
 * extracted phase's reads/writes visible at the field level.
 */
final class GreedyPlanSession {

  // ---- Request (immutable for the session) --------------------------------
  final OsmNodeNamed start;
  final double desiredDistance;
  final double startDirection;

  // ---- Budget (immutable for the session) ---------------------------------
  final long planStart;
  final long planBudgetMs;
  final long deadline;
  /** Reduced deadline for non-late steps — the closure reserve (see plan()). */
  final long earlyDeadline;
  final double subTarget;

  // ---- Outcome under construction -----------------------------------------
  final RoundTripResult result = new RoundTripResult();

  // ---- Committed-plan state ------------------------------------------------
  /**
   * SAFE-3: primitive open-addressing store replacing the former
   * {@code HashMap<Long,Integer>} reuse counts + {@code HashMap<Long,Double>}
   * first-visit positions. The two were always maintained in lock-step, so they
   * fold into one boxing-free table. It is only ever point-queried (never
   * iterated), so slot layout cannot affect any routing decision.
   */
  final VisitedEdgeStore visitedEdges = new VisitedEdgeStore();
  final List<OsmTrack> segments = new ArrayList<>();
  double totalDistance;
  MatchedWaypoint startMwp;
  MatchedWaypoint currentMwp;
  final List<MatchedWaypoint> waypointStack = new ArrayList<>();
  GreedyRoundTripPlanner.Snapshot bestFallback;
  DirectionPreference dirPref = DirectionPreference.ANY;
  /** Loop-scale radius (desiredDistance / 4) for the scorer's distance terms. */
  double searchRadius;
  /** Previous via's coordinates (scoring reference); -1 before the first leg. */
  int prevIlon = -1;
  int prevIlat = -1;
  /**
   * Air-to-road factor, adaptive per plan (see
   * {@code GreedyRoundTripPlanner.MAX_INDIRECTNESS_EST}): starts at the
   * calibrated baseline, learns from each routed leg.
   */
  double indirectnessEst;
  /**
   * Closed-loop gate rejections this plan — the distress brake for the
   * heading-persistence term. In half-plane-blocked geographies (sea/lake) the
   * way home IS the way out, and rewarding "keep heading" steers vias into the
   * dead-end corridor — observed on coastal_nice_100km_gravel as a grind of
   * same-way-back closure rejections ending in a 43-crossing weave. Closure
   * rejections are the planner's own distress signal: after
   * {@code HEADING_BRAKE_REJECTIONS} of them, the term is disabled for the
   * rest of the plan. No terrain modeling — the indirectness gate provably
   * missed this class (coastal legs are direct; estimate stayed 1.4-1.6).
   */
  int closureRejections;

  // ---- Telemetry counters --------------------------------------------------
  int candidatesGenerated;
  int candidatesRouted;
  int returnChecksPerformed;
  /**
   * Auto-quality-redesign §132: routed candidates broken down by source —
   * start-iso vs non-start-iso ("nonIso" = per-step graph-native and legacy
   * radial candidates in production GREEDY). Source is identified via the
   * {@code CandidatePoint#costFromStart} sentinel: a start-iso candidate has
   * {@code costFromStart != NO_ISO_COST}. "Routed" counts every candidate the
   * planner ran through Dijkstra; the accepted-legs counters below count only
   * those that became part of the final loop. Low-iso-usage classification
   * should use ACCEPTED legs, not routed.
   */
  int routedIso;
  int routedNonIso;
  int acceptedIsoLegs;
  int acceptedNonIsoLegs;
  /** Accepted legs whose candidate held its routed slot only via quota injection. */
  int acceptedQuotaInjectedLegs;
  /** First step at which iso influence was reduced; -1 = never demoted. */
  int poolDemotedAtStep = -1;
  /** First step of graph-native-only (internal GREEDY fallback) mode; -1 = never. */
  int graphNativeOnlyAtStep = -1;
  /** Return estimates answered by the oracle vs the EMA fallback. */
  int oracleEstimates;
  int fallbackEstimates;

  GreedyPlanSession(OsmNodeNamed start, double desiredDistance, double startDirection,
                    long planStart, long planBudgetMs, long deadline, long earlyDeadline,
                    int subRouteCount, double initialIndirectness) {
    this.start = start;
    this.desiredDistance = desiredDistance;
    this.startDirection = startDirection;
    this.planStart = planStart;
    this.planBudgetMs = planBudgetMs;
    this.deadline = deadline;
    this.earlyDeadline = earlyDeadline;
    this.subTarget = desiredDistance / subRouteCount;
    this.searchRadius = desiredDistance / 4.0;
    this.indirectnessEst = initialIndirectness;
  }
}
