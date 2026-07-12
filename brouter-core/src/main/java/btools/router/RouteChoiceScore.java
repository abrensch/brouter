package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Soft ranking score for choosing among accepted round-trip candidates.
 *
 * <p>This is a deliberately separate concern from {@link RoundTripQualityGate}.
 * The gate makes the hard accept/reject decision (beelines, broken closure,
 * profile-hostile surfaces, accidental retraces). Any candidate that fails
 * the gate is excluded from scoring entirely. Among candidates that DO pass,
 * the score ranks them — it does not gate.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>numeric score + a reason breakdown the caller can surface for
 *       debugging or explanation;</li>
 *   <li>hard-coded production-neutral preferred bands, no dependency on
 *       {@code LoopTestRegion} or benchmark constants;</li>
 *   <li>direction is a weak factor only — it cannot dominate severe reuse,
 *       closure, or distance issues;</li>
 *   <li>profile-specific cost/m is a soft preference;</li>
 *   <li>scores the final {@link OsmTrack} only, not pre-final planner state;</li>
 *   <li>penalises route-shape disclosures that indicate surprising
 *       behavior (LOLLIPOP and OUT_AND_BACK are still acceptable
 *       but rank below clean STRICT_LOOP all else equal).</li>
 * </ul>
 *
 * <p>The score is in {@code [0, 1]}. Higher is better. AUTO should use
 * {@link #score(OsmTrack, double, String, RoundTripQualityResult, double)} so
 * the requested direction participates as the weak direction factor; the
 * four-argument overload is retained for callers that do not have a requested
 * direction. The returned {@link Verdict} carries both the numeric score and
 * the per-component contributions.
 */
public final class RouteChoiceScore {

  // ---- Component weights -------------------------------------------------
  // Sum to 1.0 across the positive contributions. Direction is intentionally
  // small (5%) so it cannot dominate. The shape-penalty is subtracted on top
  // of the positive sum, so a clean STRICT_LOOP can score above 1.0 in raw
  // terms — clamped at the end.

  // Weights sum to 1.0. 2026-06-15 rebalance (user directive): "distance can be tolerated — an
  // appealing route should score higher." Distance dropped 0.25→0.18 AND made tolerant (flat band,
  // below), road-character lifted 0.07→0.17, cost/m down 0.08→0.05. Net: a clean, appealing loop now
  // out-ranks one that buys a few % more distance by detouring through a town (e.g. basel_80km_E,
  // where AUTO used to ship the Lörrach-dipping iso_greedy over the city-skirting greedy).
  /** Distance closeness to requested (now tolerant — see the flat band in #1). */
  static final double W_DISTANCE   = 0.18;
  /** Road reuse / retrace penalty (low reuse = high score). */
  static final double W_REUSE      = 0.20;
  /** Closure quality (small closure gap = high score). */
  static final double W_CLOSURE    = 0.10;
  /** Continuity / max-gap (no synthetic beelines + small max-gap). */
  static final double W_CONTINUITY = 0.15;
  /** Compactness within reasonable range. */
  static final double W_COMPACTNESS = 0.10;
  /**
   * Road-CHARACTER preference (profile-aware highway desirability from the route's tags) — the
   * "would a real cyclist want to ride this" dimension. Validated against the user's eye on the
   * Basel/Freiburg matrix (residential-avoiding loops score higher). Now the dominant soft term so
   * an appealing route out-ranks a marginally-better-distance one.
   */
  static final double W_CHARACTER  = clamp01x(0.17, 0.30);
  /** Profile-specific cost/m soft preference. */
  static final double W_COSTM      = 0.05;
  /** Direction delta — weak; capped so it cannot dominate. */
  static final double W_DIRECTION  = 0.05;

  private static double clamp01x(double v, double hi) { return Math.max(0.0, Math.min(hi, v)); }
  /** Shape disclosure penalty: LOLLIPOP/SCENIC down-weight vs STRICT_LOOP. */
  static final double SHAPE_PENALTY_LOLLIPOP = 0.05;
  static final double SHAPE_PENALTY_OUT_AND_BACK   = 0.15;
  /**
   * Self-intersection penalty per crossing. Crossings are a route-shape defect
   * the cyclist sees but no other RCS term captures (reuse measures co-linear
   * retrace, not transverse crossings). Subtracted per crossing so AUTO prefers
   * the cleaner of two otherwise-comparable candidates.
   *
   * <p>Measured (full loop-quality matrix, offline AUTO simulation, 2026-06):
   * today's AUTO ships 12.8% crossed loops; this term at 0.08/crossing drops that
   * to 3.7% (the residual where BOTH greedy and iso_greedy cross, so no clean
   * alternative exists) with no reuse/distance regression — it works by letting
   * the selector see crossings and pick the already-existing cleaner candidate.
   *
   * <p>Ranking-only: RCS never gates acceptance ({@link RoundTripQualityGate}
   * does), so this cannot cause a no-route. Tunable for the gold-standard
   *. The gate caps accepted loops at
   * {@link RoundTripQualityGate#MAX_SELF_INTERSECTIONS}, so the penalty is bounded.
   */
  static final double SHAPE_PENALTY_PER_SELF_INTERSECTION =
    0.08;

  /**
   * Lasso surcharge — shape term #9b (loop-review backlog item 3).
   * {@link LoopQualityMetrics#crossingPoints} classifies each crossing by its
   * enclosed arc: ≤ {@link LoopQualityMetrics#SMALL_LOOP_MAX_ARC_METERS} means
   * a small detour loop / lasso the cyclist actually rides around — a worse,
   * more visible defect than a far-apart structural outbound-vs-return
   * crossing. ADDITIVE on top of #9's per-crossing penalty so #9's measured
   * calibration (shipped crossings 12.8%→3.7%) holds unchanged for structural
   * crossings.
   *
   * <p><b>Size-faded</b> (user label, Basel 80km fastbike E, 2026-06-11: a
   * detour loop "big enough" to ride is fine): per-lasso severity =
   * {@code 1 − enclosedArc / SMALL_LOOP_MAX_ARC_METERS}, so a 400m circle
   * pays ~the full surcharge (effective ~2.5× a structural crossing — the
   * middle of the review's 2-3× band) while a near-cap 3.5km loop pays
   * almost nothing. Ranking-only like #9 (excluded from
   * {@link Verdict#qualityScore()}).
   */
  static final double SHAPE_PENALTY_LASSO_EXTRA =
    0.12;

  /**
   * Near-revisit / "teardrop" penalty — shape term #10. Catches the defect that
   * {@link #SHAPE_PENALTY_PER_SELF_INTERSECTION} (#9) MISSES: a loop that returns to
   * within {@link #NEAR_REVISIT_EPS_M} of an earlier point and rejoins (a "small loop back
   * to the same point") without a <em>transverse</em> crossing — a clean teardrop, which
   * {@link RoundTripQualityGate#countSelfIntersections} (a strict segment-cross test) scores
   * as 0. Measured motivating case: Basel 30 km gravel ships an ISO_GREEDY loop with a 7.3 km
   * teardrop to Ettingen (countSelfIntersections=0) while a teardrop-free GREEDY alternative
   * exists; AUTO couldn't see the defect so it shipped on distance.
   *
   * <p>Severity-weighted by excursion arc (Σ min(1, arc / {@link #NEAR_REVISIT_NORM_M})),
   * NOT a flat per-span count, so a 7 km teardrop outweighs a 600 m wiggle — this is what
   * keeps the penalty from promoting a clean-but-much-shorter loop over a good-distance one
   * with only a minor near-revisit.
   *
   * <p>Ranking-only and excluded from {@link Verdict#qualityScore()} exactly like #9: it lets
   * AUTO pick the cleaner of two comparable candidates, but a terrain-forced teardrop shipping
   * as best-available is not double-penalised in the soft quality floor. The default is provisional pending the corpus A/B calibration.
   */
  static final double SHAPE_PENALTY_PER_TEARDROP =
    0.12;
  /** Near-revisit detector thresholds (mirror the probe; 10 km cap catches Basel's 7.3 km). */
  static final double NEAR_REVISIT_EPS_M = 60.0;
  static final double NEAR_REVISIT_MIN_ARC_M = 600.0;
  static final double NEAR_REVISIT_MAX_ARC_M = 10000.0;
  /** Excursion arc (m) at which a teardrop is "full severity" (1.0). */
  static final double NEAR_REVISIT_NORM_M = 5000.0;
  /** A near-revisit span covering more than this fraction of the perimeter is the loop's own
   *  start≈end closure (only reachable on loops shorter than the arc cap), not a teardrop. */
  static final double CLOSURE_EXCLUSION_FRACTION = 0.85;

  // ---- Profile-typical cost-per-meter bands ------------------------------
  // Used to compute the cost/m component. A route on roads the profile
  // actively prefers (cost/m at or below the lower bound) scores 1.0; above
  // the upper bound scores 0; linearly interpolated in between.
  //
  // These values mirror the existing {@link LoopQualityMetrics#computeCostMatchScore}
  // bands but per-profile, because gravel/MTB have higher cost-per-meter
  // baselines on their preferred surfaces (a 1.5 cost/m gravel route may
  // be on perfect terrain; the same value on fastbike means rough roads).

  static double[] costMBand(String profileName) {
    if (profileName == null) return new double[]{1.5, 4.0};
    String n = profileName.toLowerCase(Locale.ROOT);
    // "road" must match as a whole word, not a substring: profiles like
    // "Trekking-SmallRoads" are NOT paved-friendly racing profiles. The same
    // whole-token name convention classifies the bike family in
    // RoadCharacterScore.family. (RoundTripQualityGate decides paved-ness from the
    // profile cost model now, not the name, so it is deliberately not referenced here.)
    if (n.contains("fastbike") || n.matches(".*\\broad\\b.*") || n.contains("racing")) {
      return new double[]{1.2, 3.0};   // tight band: paved-friendly profiles
    }
    if (n.contains("gravel")) {
      return new double[]{2.0, 5.0};
    }
    if (n.contains("mtb")) {
      return new double[]{4.0, 9.0};
    }
    if (n.contains("trekking")) {
      return new double[]{1.5, 4.0};
    }
    return new double[]{1.5, 4.0};     // default — matches LoopQualityMetrics
  }

  // ---- Verdict -----------------------------------------------------------

  /** A single component contribution. */
  public static final class Reason {
    public final String label;
    public final double weight;
    public final double rawValue;       // the underlying metric value
    public final double scoreContribution; // weight * normalised, in [-1, weight]

    Reason(String label, double weight, double rawValue, double scoreContribution) {
      this.label = label;
      this.weight = weight;
      this.rawValue = rawValue;
      this.scoreContribution = scoreContribution;
    }

    @Override
    public String toString() {
      return String.format(Locale.US, "%s (raw=%.3f, weight=%.2f, +%.3f)",
        label, rawValue, weight, scoreContribution);
    }
  }

  /** Result of scoring one candidate. */
  public static final class Verdict {
    private final double score;
    private final double qualityScore;
    private final List<Reason> reasons;

    Verdict(double score, double qualityScore, List<Reason> reasons) {
      this.score = score;
      this.qualityScore = qualityScore;
      this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
    }

    /** Ranking score, including the self-intersection penalty. AUTO uses this to
     *  prefer the cleaner of two otherwise-comparable candidates. */
    public double score() { return score; }

    /**
     * Multi-dimension quality score EXCLUDING the self-intersection penalty —
     * for soft quality-floor checks (e.g. the loop-quality suite's MIN_RCS_PASS).
     * Crossings are already hard-gated by {@link RoundTripQualityGate}
     * (≤ MAX_SELF_INTERSECTIONS), so the soft floor must not double-count them;
     * {@link #score()} keeps the penalty for ranking. All other shape penalties
     * (LOLLIPOP/OUT_AND_BACK) remain in both, as they are not hard-gated.
     */
    public double qualityScore() { return qualityScore; }
    public List<Reason> reasons() { return reasons; }

    /** Multi-line human-readable breakdown. Suitable for logging. */
    public String describe() {
      StringBuilder sb = new StringBuilder(256);
      sb.append(String.format(Locale.US, "score=%.3f%n", score));
      sb.append("reasons:%n".replace("%n", "\n"));
      for (Reason r : reasons) {
        sb.append("  ").append(r).append('\n');
      }
      return sb.toString();
    }

    @Override
    public String toString() {
      return String.format(Locale.US, "RouteChoiceScore[%.3f, %d reasons]",
        score, reasons.size());
    }
  }

  private RouteChoiceScore() {}

  // ---- Public API --------------------------------------------------------

  /**
   * Score a candidate. The candidate must already have passed
   * {@link RoundTripQualityGate} ({@code qualityGate.isAccepted() == true});
   * scoring a rejected candidate is meaningless — return a zero-score Verdict.
   *
   * @param track             the final routed track for this candidate
   * @param requestedDistance the loop distance the cyclist requested (meters)
   * @param profileName       the active profile name (used for cost/m bands)
   * @param qualityGate       the gate verdict (used for shape penalty + disclosure
   *                          penalty); may be null in which case shape defaults
   *                          to STRICT_LOOP and no disclosure penalty applies
   */
  public static Verdict score(OsmTrack track, double requestedDistance,
                              String profileName, RoundTripQualityResult qualityGate) {
    return score(track, requestedDistance, profileName, qualityGate, 0);
  }

  public static Verdict score(OsmTrack track, double requestedDistance,
                              String profileName, RoundTripQualityResult qualityGate,
                              double requestedDirection) {
    return score(track, requestedDistance, profileName, qualityGate, requestedDirection, true);
  }

  /**
   * Best-effort ranking variant: scores a gate-REJECTED track on its real
   * geometry (bypasses the rejected-gate zero guard) while still consuming the
   * gate's shape for the disclosure penalty (term #8). Used when AUTO must rank
   * degraded candidates that all failed the gate — passing {@code null} instead
   * would also bypass the guard, but silently lose the LOLLIPOP/OUT_AND_BACK
   * penalty and rank a rejected corridor as if it were a strict loop.
   */
  public static Verdict scoreBestEffort(OsmTrack track, double requestedDistance,
                                        String profileName, RoundTripQualityResult qualityGate,
                                        double requestedDirection) {
    return score(track, requestedDistance, profileName, qualityGate, requestedDirection, false);
  }

  private static Verdict score(OsmTrack track, double requestedDistance,
                               String profileName, RoundTripQualityResult qualityGate,
                               double requestedDirection, boolean rejectedGateZeroes) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) {
      List<Reason> empty = new ArrayList<>();
      empty.add(new Reason("no track", 0, 0, 0));
      return new Verdict(0.0, 0.0, empty);
    }
    if (rejectedGateZeroes && qualityGate != null && !qualityGate.isAccepted()) {
      List<Reason> empty = new ArrayList<>();
      empty.add(new Reason("gate rejected: " + qualityGate.getRejectionReason(),
        0, 0, 0));
      return new Verdict(0.0, 0.0, empty);
    }

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, (int) requestedDistance, requestedDirection);
    List<Reason> reasons = new ArrayList<>(8);
    double total = 0;

    // 1. Distance closeness — TOLERANT (user directive: distance can be tolerated). Flat 1.0 within
    //    ±15% of target, then decays to 0.0 at ±50%. So a clean 0.95× loop and an on-target 0.98×
    //    loop score identically on distance, and road-character decides between them — an appealing
    //    route is no longer out-ranked just because it is a few % short.
    double distDelta = Math.abs(m.getDistanceRatio() - 1.0);
    double distScore = distDelta <= 0.15 ? 1.0 : 1.0 - Math.min(1.0, (distDelta - 0.15) / 0.35);
    double distContrib = W_DISTANCE * distScore;
    reasons.add(new Reason("distance ratio " + fmt(m.getDistanceRatio())
      + " (preferred band [0.5, 1.5])", W_DISTANCE, m.getDistanceRatio(), distContrib));
    total += distContrib;

    // 2. Reuse. 1.0 at 0%, 0.0 at ≥ 50% reuse.
    double reuseScore = 1.0 - Math.min(1.0, m.getRoadReusePercent() / 50.0);
    double reuseContrib = W_REUSE * reuseScore;
    reasons.add(new Reason("road reuse " + fmt(m.getRoadReusePercent()) + "%",
      W_REUSE, m.getRoadReusePercent(), reuseContrib));
    total += reuseContrib;

    // 3. Closure. 1.0 at 0m, 0.0 at ≥ 400m (the existing MAX_CLOSURE_METERS).
    double closureScore = 1.0 - Math.min(1.0,
      m.getClosureDistanceMeters() / (double) RoundTripQualityGate.MAX_CLOSURE_METERS);
    double closureContrib = W_CLOSURE * closureScore;
    reasons.add(new Reason("closure " + m.getClosureDistanceMeters() + "m",
      W_CLOSURE, m.getClosureDistanceMeters(), closureContrib));
    total += closureContrib;

    // 4. Continuity (no synthetic beelines) + maxGap.
    double maxGapScore = 1.0 - Math.min(1.0, m.getMaxGapMeters() / 1500.0);
    double contScore = 0.75 * m.getContinuityScore() + 0.25 * maxGapScore;
    double contContrib = W_CONTINUITY * contScore;
    reasons.add(new Reason("continuity " + fmt(m.getContinuityScore())
      + " (maxGap " + m.getMaxGapMeters() + "m)",
      W_CONTINUITY, m.getContinuityScore(), contContrib));
    total += contContrib;

    // 5. Compactness — convex hull area vs ideal-circle area. Already in [0,1].
    double compactContrib = W_COMPACTNESS * m.getCompactnessScore();
    reasons.add(new Reason("compactness " + fmt(m.getCompactnessScore()),
      W_COMPACTNESS, m.getCompactnessScore(), compactContrib));
    total += compactContrib;

    // 6. cost/m within profile-typical band.
    double[] band = costMBand(profileName);
    double costM = m.getAverageCostPerMeter();
    double costMScore;
    if (costM <= band[0]) costMScore = 1.0;
    else if (costM >= band[1]) costMScore = 0.0;
    else costMScore = (band[1] - costM) / (band[1] - band[0]);
    double costMContrib = W_COSTM * costMScore;
    reasons.add(new Reason("cost/m " + fmt(costM)
      + " (preferred band [" + fmt(band[0]) + ", " + fmt(band[1]) + "])",
      W_COSTM, costM, costMContrib));
    total += costMContrib;

    // 6b. Road character — profile-aware highway desirability from the route's tags (not its cost,
    //     which would be circular for a residential-penalising profile). Captures the rider-eye
    //     "do I want to ride this" dimension that geometry terms miss.
    if (W_CHARACTER > 0) {
      double appeal = RoadCharacterScore.compute(track, profileName).appeal;
      double charContrib = W_CHARACTER * appeal;
      reasons.add(new Reason("road character " + fmt(appeal), W_CHARACTER, appeal, charContrib));
      total += charContrib;
    }

    // 7. Direction delta — weak. Score 1.0 at delta 0°, 0.0 at delta 180°.
    //    Direction is intentionally a small weight so it cannot dominate
    //    other factors.
    double dirScore = 1.0 - Math.min(1.0, m.getDirectionDeltaDegrees() / 180.0);
    double dirContrib = W_DIRECTION * dirScore;
    reasons.add(new Reason("direction delta " + fmt(m.getDirectionDeltaDegrees()) + "°",
      W_DIRECTION, m.getDirectionDeltaDegrees(), dirContrib));
    total += dirContrib;

    // 8. Shape disclosure penalty: cyclist sees the route shape; a clean
    //    STRICT_LOOP ranks above a LOLLIPOP above a OUT_AND_BACK
    //    all else equal. In strict mode INVALID_RETRACE is gate-rejected above
    //    and never reaches here; in best-effort mode it can — it carries no
    //    explicit penalty, but its ~50%+ reuse already zeroes the reuse term.
    if (qualityGate != null) {
      RouteShape shape = qualityGate.getShape();
      double penalty = 0;
      if (shape == RouteShape.LOLLIPOP) penalty = SHAPE_PENALTY_LOLLIPOP;
      else if (shape == RouteShape.OUT_AND_BACK) penalty = SHAPE_PENALTY_OUT_AND_BACK;
      if (penalty > 0) {
        reasons.add(new Reason("shape " + shape + " penalty",
          -penalty, 0, -penalty));
        total -= penalty;
      }
    }

    // 9. Self-intersection penalty. A route-shape defect the cyclist sees that
    //    no term above captures (reuse = co-linear retrace, not transverse
    //    crossings). Read directly from the track — the gate's count isn't
    //    exposed on the verdict. Lets AUTO pick the cleaner of two comparable
    //    candidates; measured to take shipped crossings 12.8%→3.7% (see constant).
    int selfIntersections = RoundTripQualityGate.countSelfIntersections(track);
    double selfIntPenalty = 0;
    if (selfIntersections > 0) {
      selfIntPenalty = SHAPE_PENALTY_PER_SELF_INTERSECTION * selfIntersections;
      reasons.add(new Reason("self-intersections " + selfIntersections,
        -selfIntPenalty, selfIntersections, -selfIntPenalty));
      total -= selfIntPenalty;
    }

    // 9b. Lasso surcharge: crossings whose enclosed sub-loop is short are
    //     small detour loops the cyclist rides around. Size-faded severity
    //     (see SHAPE_PENALTY_LASSO_EXTRA): a tiny circle pays ~full, a loop
    //     near the 4km cap pays almost nothing. Guarded on #9 so the
    //     O(n²-capped) classification scan only runs on tracks that have
    //     crossings at all.
    double lassoPenalty = 0;
    if (selfIntersections > 0 && track.nodes != null) {
      double lassoSeverity = 0;
      for (double[] x : LoopQualityMetrics.crossingPoints(track.nodes)) {
        double enclosed = x[2];
        if (enclosed <= LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS) {
          lassoSeverity += 1.0 - enclosed / LoopQualityMetrics.SMALL_LOOP_MAX_ARC_METERS;
        }
      }
      if (lassoSeverity > 0) {
        lassoPenalty = SHAPE_PENALTY_LASSO_EXTRA * lassoSeverity;
        reasons.add(new Reason("lasso crossings sev " + fmt(lassoSeverity),
          -lassoPenalty, lassoSeverity, -lassoPenalty));
        total -= lassoPenalty;
      }
    }

    // 10. Near-revisit / teardrop penalty. The shape defect #9 misses: a "small loop
    //     back to the same point" that never transversely crosses (countSelfIntersections=0).
    //     Severity = Σ min(1, arc/NORM) over teardrop spans, so a big excursion outweighs a
    //     minor wiggle. Ranking-only like #9 (added back into qualityScore below).
    double teardropSeverity = teardropSeverity(track);
    double teardropPenalty = 0;
    if (teardropSeverity > 0) {
      teardropPenalty = SHAPE_PENALTY_PER_TEARDROP * teardropSeverity;
      reasons.add(new Reason("near-revisit teardrop sev " + fmt(teardropSeverity),
        -teardropPenalty, teardropSeverity, -teardropPenalty));
      total -= teardropPenalty;
    }

    // Ranking score includes the self-intersection, lasso and teardrop penalties;
    // qualityScore excludes ALL THREE (they are ranking signals to pick the cleaner
    // alternative — a terrain-forced defect shipping as best-available must not
    // trip the soft floor).
    double clamped = Math.max(0.0, Math.min(1.0, total));
    double qualityClamped = Math.max(0.0, Math.min(1.0, total + selfIntPenalty + lassoPenalty + teardropPenalty));
    return new Verdict(clamped, qualityClamped, reasons);
  }

  /**
   * Sum of teardrop severities (Σ min(1, arc / {@link #NEAR_REVISIT_NORM_M})) over the
   * track's near-revisit spans, excluding the loop's own start≈end closure. Drives the
   * #10 shape penalty. See {@link LoopQualityMetrics#nearRevisitSpans}.
   */
  private static double teardropSeverity(OsmTrack track) {
    if (track == null || track.nodes == null) return 0;
    List<OsmPathElement> nodes = track.nodes;
    int n = nodes.size();
    if (n < 4) return 0;
    double[] cum = new double[n];
    for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + nodes.get(i - 1).calcDistance(nodes.get(i));
    double total = cum[n - 1];
    double sev = 0;
    for (int[] s : LoopQualityMetrics.nearRevisitSpans(nodes,
        NEAR_REVISIT_EPS_M, NEAR_REVISIT_MIN_ARC_M, NEAR_REVISIT_MAX_ARC_M)) {
      double arc = cum[s[1]] - cum[s[0]];
      if (total > 0 && arc > CLOSURE_EXCLUSION_FRACTION * total) continue; // loop closure, not a teardrop
      sev += Math.min(1.0, arc / NEAR_REVISIT_NORM_M);
    }
    return sev;
  }

  private static String fmt(double v) {
    return String.format(Locale.US, "%.2f", v);
  }
}
