package btools.router;

import java.util.Locale;

/**
 * Per-plan health model of the ISO_GREEDY start-centered candidate pool
 * (issue #26 — "make ISO_GREEDY absorb plain GREEDY fallback").
 *
 * <p>ISO_GREEDY plans against a pool captured by ONE start-centered isochrone
 * expansion. That prior is powerful when the pool represents the reachable
 * terrain (valleys, coastlines, return difficulty), and harmful when it is
 * thin, bunched in a corridor, or no longer representative of the loop's
 * current lobe. This class turns "is the pool trustworthy right now?" into an
 * explicit, monotonically-decreasing score in {@code [0, 1]} built from:
 *
 * <ul>
 *   <li><b>Static pool shape</b> ({@link PoolShape}, measured once after the
 *       pool filter): distinct angular sectors, angular span, contour spread,
 *       and whether a {@link ReturnDistanceOracle} could be calibrated.</li>
 *   <li><b>Dynamic in-plan evidence</b> (recorded by
 *       {@link GreedyRoundTripPlanner} as the plan unfolds): graph-native
 *       candidates winning mixed-source routed comparisons (worse when the
 *       source quota had to inject the winner), iso-sourced legs undone by
 *       length/detail/closure rejections, accepted vias bunching in one
 *       angular sector, closure-check failures, and return estimates falling
 *       back from the oracle to the global EMA.</li>
 * </ul>
 *
 * <p>The score maps to a {@link State} ladder with two demotion effects the
 * planner applies from the NEXT candidate decision on:
 *
 * <ul>
 *   <li>{@link State#DEGRADED} — iso influence is reduced: iso-pool candidates
 *       lose their prior-based scoring terms (density bonus, contour-depth
 *       preference, iso-hostility estimate) and compete on geometry alone,
 *       and the graph-native routed-slot quota grows by one seat.</li>
 *   <li>{@link State#UNHEALTHY} — the planner goes graph-native-only for the
 *       remaining steps (the internal plain-GREEDY fallback): fresh per-step
 *       expansions replace the stale pool entirely. This is deliberately the
 *       "refresh" response — re-running the start-centered expansion would
 *       reproduce the same pool (the staleness is positional, not temporal),
 *       while the per-step graph-native expansion IS the fresh, targeted
 *       re-sampling of the loop's current lobe.</li>
 * </ul>
 *
 * <p>The score only ever decreases (deductions are capped per signal), so a
 * demotion is sticky for the remainder of the plan — by the time the evidence
 * has accumulated, flip-flopping back would re-trust a pool the plan just
 * proved stale. Weights are heuristics calibrated for 3-6 step plans and
 * sanity-checked on a 25-scenario forced-ISO_GREEDY sweep (Garmisch/Lozère/
 * Girona/Dreieich × 30-80km × all directions, 2026-07-07 tiles, base vs this
 * change): demotions fired on 9/25 stress cases and changed 3 shipped loops —
 * all in Garmisch, the documented plain-GREEDY-win region, all gate-accepted,
 * with distance error and cost/m improving (RCS −0.002…+0.011) — while every
 * control scenario stayed byte-identical. A later issue-#26 absorption pass
 * tightened repeated graph-native wins: three honest graph-native routed wins
 * now cross the DEGRADED bar for a rich pool, while the cap still prevents
 * graph-native wins alone from forcing graph-native-only mode. The full
 * loop-quality matrix remains the authoritative recalibration pass.
 *
 * <p>One instance per {@link GreedyRoundTripPlanner#plan} run (the planner is
 * built fresh per ladder rung). Null on plain GREEDY / graph-native-only
 * providers — every planner hook is null-guarded, keeping GREEDY bit-identical.
 */
final class IsoPoolHealth {

  /** Trust ladder for the iso pool; see class doc for the demotion effects. */
  enum State {HEALTHY, DEGRADED, UNHEALTHY}

  /** Score below which iso influence is reduced (prior terms stripped, quota +1). */
  static final double DEGRADED_BELOW = 0.55;
  /** Score below which the planner goes graph-native-only (internal GREEDY fallback). */
  static final double UNHEALTHY_BELOW = 0.30;

  // ---- Static shape deductions (applied once, from PoolShape) --------------
  // Calibration anchor: the weakest pool buildCandidateProvider admits to the
  // blend (4 sectors, 180° span, one contour, no oracle) must start clearly
  // DEGRADED (0.50) — that shape IS the corridor-adjacent pool the influence
  // reduction exists for — while any single static weakness stays HEALTHY and
  // UNHEALTHY stays reachable only through in-plan evidence.
  /** Max deduction for few distinct sectors (full at ≤{@link #SECTORS_POOR}, none at ≥{@link #SECTORS_GOOD}). */
  static final double W_SECTORS = 0.22;
  static final int SECTORS_GOOD = 10;
  static final int SECTORS_POOR = 4;
  /** Max deduction for a narrow angular span (full at ≤{@link #SPAN_POOR_DEG}°, none at 360°). */
  static final double W_SPAN = 0.18;
  static final double SPAN_POOR_DEG = 180.0;
  /** Deduction when the pool samples a single cost contour (no depth spread). */
  static final double W_SINGLE_CONTOUR = 0.05;
  /** Deduction when no return-distance oracle could be calibrated from the expansion. */
  static final double W_NO_ORACLE = 0.05;

  // ---- Dynamic evidence deductions (each capped) ---------------------------
  /** Per mixed-source routed comparison won by a graph-native candidate. */
  static final double W_GRAPH_NATIVE_WIN = 0.16;
  static final double CAP_GRAPH_NATIVE_WIN = 0.48;
  /** Extra per graph-native win where the source quota had to inject the winner. */
  static final double W_QUOTA_INJECTED_WIN = 0.06;
  static final double CAP_QUOTA_INJECTED_WIN = 0.12;
  /** Per iso-sourced leg undone by a length/detail/closure rejection. */
  static final double W_ISO_LEG_REJECTION = 0.04;
  static final double CAP_ISO_LEG_REJECTION = 0.12;
  /** Per accepted via landing in an already-used angular sector (bunching). */
  static final double W_SECTOR_REPEAT = 0.08;
  static final double CAP_SECTOR_REPEAT = 0.16;
  /** Per closed-loop gate rejection (the planner's own distress signal). */
  static final double W_CLOSURE_REJECTION = 0.05;
  static final double CAP_CLOSURE_REJECTION = 0.10;
  /**
   * Max deduction for EMA-fallback-dominated return estimates: scales with the
   * fallback share above 50%, and only once {@link #MIN_RETURN_ESTIMATES}
   * estimates exist (small samples are noise). A pool whose oracle cannot
   * answer where the plan actually goes is not covering the loop's lobe.
   */
  static final double W_EMA_SHARE = 0.10;
  static final int MIN_RETURN_ESTIMATES = 8;

  /** Sector granularity for the accepted-via bunching signal (45° sectors). */
  static final int ACCEPTED_SECTOR_COUNT = 8;

  /**
   * Immutable pool-shape metrics, measured once per pool from the filtered
   * {@link IsochroneCandidateProvider} plus oracle availability. Shared across
   * the subRouteCount ladder (each rung gets a fresh {@link IsoPoolHealth}
   * around the same shape).
   */
  static final class PoolShape {
    final int poolSize;
    final int distinctSectors;
    final double angularSpanDeg;
    final int contourLevels;
    final boolean oracleAvailable;

    PoolShape(int poolSize, int distinctSectors, double angularSpanDeg,
              int contourLevels, boolean oracleAvailable) {
      this.poolSize = poolSize;
      this.distinctSectors = distinctSectors;
      this.angularSpanDeg = angularSpanDeg;
      this.contourLevels = contourLevels;
      this.oracleAvailable = oracleAvailable;
    }

    String describe() {
      return "pool=" + poolSize + " sectors=" + distinctSectors
        + " span=" + (int) angularSpanDeg + "deg contours=" + contourLevels
        + " oracle=" + (oracleAvailable ? "yes" : "no");
    }
  }

  private final PoolShape shape;
  private final double staticDeduction;

  private int graphNativeWins;
  private int quotaInjectedWins;
  private int isoLegRejections;
  private int sectorRepeats;
  private int closureRejections;
  private int oracleEstimates;
  private int emaEstimates;
  /** Bit i set = an accepted via already landed in 45°-sector i. */
  private int acceptedSectorMask;

  IsoPoolHealth(PoolShape shape) {
    this.shape = shape;
    this.staticDeduction = staticDeduction(shape);
  }

  private static double staticDeduction(PoolShape s) {
    double d = 0;
    d += W_SECTORS * clamp01((SECTORS_GOOD - s.distinctSectors)
      / (double) (SECTORS_GOOD - SECTORS_POOR));
    d += W_SPAN * clamp01((360.0 - s.angularSpanDeg) / (360.0 - SPAN_POOR_DEG));
    if (s.contourLevels <= 1) d += W_SINGLE_CONTOUR;
    if (!s.oracleAvailable) d += W_NO_ORACLE;
    return d;
  }

  private static double clamp01(double v) {
    return Math.max(0.0, Math.min(1.0, v));
  }

  // ---- Dynamic evidence hooks (called by the planner) ----------------------

  /**
   * A step whose routed top-K mixed both sources committed a leg: record which
   * source won the routed-truth comparison. Only graph-native wins deduct —
   * they are direct evidence the pool was hiding a better local alternative;
   * an extra deduction applies when the winner only got its routed slot via
   * the source quota (the pool had actively outranked it in phase 1).
   */
  void recordRoutedComparison(boolean isoWon, boolean winnerWasQuotaInjected) {
    if (isoWon) return;
    graphNativeWins++;
    if (winnerWasQuotaInjected) quotaInjectedWins++;
  }

  /** An iso-sourced tentative leg was undone (too long / detail / closure reject). */
  void recordIsoLegRejection() {
    isoLegRejections++;
  }

  /**
   * A leg committed with its via at the given bearing from the loop start.
   * Repeated acceptance in one 45° sector is the bunching signature — a loop
   * sweeping around the start should visit a fresh sector nearly every step.
   */
  void recordAcceptedLegBearing(double bearingFromStartDeg) {
    int sector = sectorOf(bearingFromStartDeg);
    int bit = 1 << sector;
    if ((acceptedSectorMask & bit) != 0) {
      sectorRepeats++;
    } else {
      acceptedSectorMask |= bit;
    }
  }

  /** 45°-sector index in [0, {@link #ACCEPTED_SECTOR_COUNT}) for a bearing in degrees. */
  static int sectorOf(double bearingDeg) {
    double norm = bearingDeg % 360.0;
    if (norm < 0) norm += 360.0;
    return Math.min(ACCEPTED_SECTOR_COUNT - 1,
      (int) (norm / (360.0 / ACCEPTED_SECTOR_COUNT)));
  }

  /** A closed-loop candidate was rejected by the quality gate. */
  void recordClosureRejection() {
    closureRejections++;
  }

  /** A return-distance estimate was answered by the oracle (true) or the EMA fallback (false). */
  void recordReturnEstimate(boolean oracleBacked) {
    if (oracleBacked) oracleEstimates++;
    else emaEstimates++;
  }

  // ---- Score and state ------------------------------------------------------

  /** Current health score in [0, 1]; monotonically non-increasing over a plan. */
  double score() {
    double d = staticDeduction;
    d += Math.min(CAP_GRAPH_NATIVE_WIN, W_GRAPH_NATIVE_WIN * graphNativeWins);
    d += Math.min(CAP_QUOTA_INJECTED_WIN, W_QUOTA_INJECTED_WIN * quotaInjectedWins);
    d += Math.min(CAP_ISO_LEG_REJECTION, W_ISO_LEG_REJECTION * isoLegRejections);
    d += Math.min(CAP_SECTOR_REPEAT, W_SECTOR_REPEAT * sectorRepeats);
    d += Math.min(CAP_CLOSURE_REJECTION, W_CLOSURE_REJECTION * closureRejections);
    int estimates = oracleEstimates + emaEstimates;
    if (estimates >= MIN_RETURN_ESTIMATES) {
      double emaShare = emaEstimates / (double) estimates;
      d += W_EMA_SHARE * clamp01((emaShare - 0.5) / 0.5);
    }
    return clamp01(1.0 - d);
  }

  State state() {
    double s = score();
    if (s < UNHEALTHY_BELOW) return State.UNHEALTHY;
    if (s < DEGRADED_BELOW) return State.DEGRADED;
    return State.HEALTHY;
  }

  /** Whether iso-pool prior terms should be stripped (DEGRADED or worse). */
  boolean influenceReduced() {
    return state() != State.HEALTHY;
  }

  /** Whether the planner should run graph-native-only steps (internal GREEDY fallback). */
  boolean graphNativeOnly() {
    return state() == State.UNHEALTHY;
  }

  /** Compact one-line summary for diagnostics / AUTO winner-attribution. */
  String describe() {
    return String.format(Locale.US,
      "score=%.2f/%s [%s] graphWins=%d(quota=%d) isoRejects=%d sectorRepeats=%d"
        + " closureRejects=%d returnEst=%d/%d oracle",
      score(), state(), shape.describe(), graphNativeWins, quotaInjectedWins,
      isoLegRejections, sectorRepeats, closureRejections,
      oracleEstimates, oracleEstimates + emaEstimates);
  }
}
