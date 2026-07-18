package btools.router.roundtrip;

import btools.util.CheapAngleMeter;

import java.util.Locale;

/**
 * Per-plan health model of the ISO_GREEDY start-centered candidate pool: a
 * monotonically-decreasing trust score in {@code [0, 1]} answering "is the pool
 * trustworthy right now?"
 *
 * <p>ISO_GREEDY plans against a pool captured by ONE start-centered isochrone
 * expansion. That prior is powerful when the pool represents the reachable
 * terrain, and harmful when it is thin, bunched in a corridor, or no longer
 * representative of the loop's current lobe. The score is built from static pool
 * shape ({@link PoolShape}, measured once) and dynamic in-plan evidence recorded
 * by {@link GreedyRoundTripPlanner}: graph-native candidates winning mixed-source
 * routed comparisons, iso legs undone by rejections, accepted vias bunching in
 * one angular sector, closure failures, and return estimates falling back from
 * the oracle to the global EMA.
 *
 * <p>The score maps to a {@link State} ladder with two demotion effects the
 * planner applies from the NEXT candidate decision on:
 * <ul>
 *   <li>{@link State#DEGRADED} — iso influence is reduced: iso-pool candidates
 *       lose their prior-based scoring terms and compete on geometry alone, and
 *       the graph-native routed-slot quota grows by one seat.</li>
 *   <li>{@link State#UNHEALTHY} — the planner goes graph-native-only for the
 *       remaining steps (the internal plain-GREEDY fallback). This is the
 *       "refresh" response: re-running the start-centered expansion would
 *       reproduce the same pool (the staleness is positional, not temporal),
 *       while per-step graph-native expansion re-samples the loop's current
 *       lobe.</li>
 * </ul>
 *
 * <p>The score only ever decreases (deductions are capped per signal), so a
 * demotion is sticky for the rest of the plan — flip-flopping back would
 * re-trust a pool the plan just proved stale. Weights are heuristics calibrated
 * for 3-6 step plans; the full loop-quality matrix is the authoritative
 * recalibration pass.
 *
 * <p>One instance per {@link GreedyRoundTripPlanner#plan} run. Null on plain
 * GREEDY / graph-native-only providers — every planner hook is null-guarded,
 * keeping GREEDY bit-identical.
 */
public final class IsoPoolHealth {

  /** Score below which iso influence is reduced (prior terms stripped, quota +1). */
  static final double DEGRADED_BELOW = 0.55;
  /** Score below which the planner goes graph-native-only (internal GREEDY fallback). */
  static final double UNHEALTHY_BELOW = 0.30;
  /** Max deduction for few distinct sectors (full at ≤{@link #SECTORS_POOR}, none at ≥{@link #SECTORS_GOOD}). */
  static final double W_SECTORS = 0.22;

  // ---- Static shape deductions (applied once, from PoolShape) --------------
  // Calibration anchor: the weakest pool buildCandidateProvider admits to the
  // blend (4 sectors, 180° span, one contour, no oracle) must start clearly
  // DEGRADED (0.50) — that shape IS the corridor-adjacent pool the influence
  // reduction exists for — while any single static weakness stays HEALTHY and
  // UNHEALTHY stays reachable only through in-plan evidence.
  static final int SECTORS_GOOD = 10;
  /** References the provider's admission floor so the 0.50 calibration anchor
   *  tracks the blend admission filter structurally. */
  static final int SECTORS_POOR = IsochroneCandidateProvider.MIN_DISTINCT_BUCKETS;
  /** Max deduction for a narrow angular span (full at ≤{@link #SPAN_POOR_DEG}°, none at 360°). */
  static final double W_SPAN = 0.18;
  static final double SPAN_POOR_DEG = IsochroneCandidateProvider.MIN_ANGULAR_SPAN_DEG;
  /** Deduction when the pool samples a single cost contour (no depth spread). */
  static final double W_SINGLE_CONTOUR = 0.05;
  /** Deduction when no return-distance oracle could be calibrated from the expansion. */
  static final double W_NO_ORACLE = 0.05;
  /** Per mixed-source routed comparison won by a graph-native candidate. */
  static final double W_GRAPH_NATIVE_WIN = 0.16;

  // ---- Dynamic evidence deductions (each capped) ---------------------------
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
   * fallback share above 50%, once ≥{@link #MIN_RETURN_ESTIMATES} exist (small
   * samples are noise). An oracle that cannot answer where the plan goes is not
   * covering the loop's lobe.
   */
  static final double W_EMA_SHARE = 0.10;
  static final int MIN_RETURN_ESTIMATES = 8;
  /** Sector granularity for the accepted-via bunching signal (45° sectors). */
  static final int ACCEPTED_SECTOR_COUNT = 8;

  public IsoPoolHealth(PoolShape shape) {
    this.shape = shape;
    this.staticDeduction = staticDeduction(shape);
  }

  /** 45°-sector index in [0, {@link #ACCEPTED_SECTOR_COUNT}) for a bearing in degrees. */
  static int sectorOf(double bearingDeg) {
    double norm = CheapAngleMeter.normalize(bearingDeg);
    return Math.min(ACCEPTED_SECTOR_COUNT - 1,
      (int) (norm / (360.0 / ACCEPTED_SECTOR_COUNT)));
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
  /**
   * Latched EMA-share deduction (high-water mark). The live share can recover,
   * but the sticky-score contract requires this term to stay latched or a
   * DEGRADED demotion would silently revert mid-plan. The aggregate score is
   * monotone only because every term is: the counters grow and this one latches
   * — any future non-monotone signal must latch the same way.
   */
  private double emaShareDeduction;
  /** Bit i set = an accepted via already landed in 45°-sector i. */
  private int acceptedSectorMask;

  /**
   * Record which source won a mixed-source routed comparison. Only graph-native
   * wins deduct — direct evidence the pool hid a better local alternative; an
   * extra deduction applies when the winner only reached its routed slot via the
   * source quota (the pool had actively outranked it in phase 1).
   */
  void recordRoutedComparison(boolean isoWon, boolean winnerWasQuotaInjected) {
    if (isoWon) return;
    graphNativeWins++;
    if (winnerWasQuotaInjected) quotaInjectedWins++;
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

  /** An iso-sourced tentative leg was undone (too long / detail / closure reject). */
  void recordIsoLegRejection() {
    isoLegRejections++;
  }

  /**
   * A leg committed with its via at the given bearing from the loop start.
   * Repeated acceptance in one 45° sector is the bunching signature — a healthy
   * loop visits a fresh sector nearly every step.
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

  /** A closed-loop candidate was rejected by the quality gate. */
  void recordClosureRejection() {
    closureRejections++;
  }

  /** A return-distance estimate was answered by the oracle (true) or the EMA fallback (false). */
  void recordReturnEstimate(boolean oracleBacked) {
    if (oracleBacked) oracleEstimates++;
    else emaEstimates++;
    int estimates = oracleEstimates + emaEstimates;
    if (estimates >= MIN_RETURN_ESTIMATES) {
      double emaShare = emaEstimates / (double) estimates;
      double d = W_EMA_SHARE * clamp01((emaShare - 0.5) / 0.5);
      if (d > emaShareDeduction) {
        emaShareDeduction = d;
      }
    }
  }

  /** Current health score in [0, 1]; monotonically non-increasing over a plan. */
  double score() {
    double d = staticDeduction;
    d += Math.min(CAP_GRAPH_NATIVE_WIN, W_GRAPH_NATIVE_WIN * graphNativeWins);
    d += Math.min(CAP_QUOTA_INJECTED_WIN, W_QUOTA_INJECTED_WIN * quotaInjectedWins);
    d += Math.min(CAP_ISO_LEG_REJECTION, W_ISO_LEG_REJECTION * isoLegRejections);
    d += Math.min(CAP_SECTOR_REPEAT, W_SECTOR_REPEAT * sectorRepeats);
    d += Math.min(CAP_CLOSURE_REJECTION, W_CLOSURE_REJECTION * closureRejections);
    d += emaShareDeduction;
    return clamp01(1.0 - d);
  }

  public State state() {
    double s = score();
    if (s < UNHEALTHY_BELOW) return State.UNHEALTHY;
    if (s < DEGRADED_BELOW) return State.DEGRADED;
    return State.HEALTHY;
  }

  // ---- Score and state ------------------------------------------------------

  /** Whether iso-pool prior terms should be stripped (DEGRADED or worse). */
  boolean influenceReduced() {
    return state() != State.HEALTHY;
  }

  /** Whether the planner should run graph-native-only steps (internal GREEDY fallback). */
  boolean graphNativeOnly() {
    return state() == State.UNHEALTHY;
  }

  /** Compact one-line summary for diagnostics / AUTO winner-attribution. */
  public String describe() {
    return String.format(Locale.US,
      "score=%.2f/%s [%s] graphWins=%d(quota=%d) isoRejects=%d sectorRepeats=%d"
        + " closureRejects=%d returnEst=%d/%d oracle",
      score(), state(), shape.describe(), graphNativeWins, quotaInjectedWins,
      isoLegRejections, sectorRepeats, closureRejections,
      oracleEstimates, oracleEstimates + emaEstimates);
  }

  /** Trust ladder for the iso pool; see class doc for the demotion effects. */
  public enum State {HEALTHY, DEGRADED, UNHEALTHY}

  /**
   * Immutable pool-shape metrics, measured once per pool from the filtered
   * {@link IsochroneCandidateProvider} plus oracle availability. Shared across
   * the subRouteCount ladder (each rung gets a fresh {@link IsoPoolHealth}).
   */
  public static final class PoolShape {
    final int poolSize;
    final int distinctSectors;
    final double angularSpanDeg;
    final int contourLevels;
    final boolean oracleAvailable;

    public PoolShape(int poolSize, int distinctSectors, double angularSpanDeg,
              int contourLevels, boolean oracleAvailable) {
      this.poolSize = poolSize;
      this.distinctSectors = distinctSectors;
      this.angularSpanDeg = angularSpanDeg;
      this.contourLevels = contourLevels;
      this.oracleAvailable = oracleAvailable;
    }

    public String describe() {
      return "pool=" + poolSize + " sectors=" + distinctSectors
        + " span=" + (int) angularSpanDeg + "deg contours=" + contourLevels
        + " oracle=" + (oracleAvailable ? "yes" : "no");
    }
  }
}
