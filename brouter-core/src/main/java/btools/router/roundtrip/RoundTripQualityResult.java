package btools.router.roundtrip;

import btools.router.RoutingContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable structured result from {@link RoundTripQualityGate#evaluate}:
 * accept/reject verdict plus a semantic {@link #shape} description, so callers
 * can disclose what the cyclist is getting (clean loop, lollipop, out-and-back)
 * rather than a bare accept/reject.
 *
 * <p>Field invariants:
 * <ul>
 *   <li>{@link #shape} is always set, even when rejected — it captures what the
 *       route IS, not what was accepted.</li>
 *   <li>{@link #rejectionReason} is non-null iff {@link #accepted} is false.</li>
 *   <li>{@link #totalReuseRatio} is in [0, 1].</li>
 *   <li>{@link #maxContiguousReuseMeters}: longest single contiguous retraced
 *       stretch — the most cyclist-visible reuse, wherever it sits.</li>
 *   <li>{@link #terminalStemReuseMeters}: reuse accepted as a short unavoidable
 *       stem near start/end (e.g. a hotel-to-village access road).</li>
 *   <li>{@link #scenicSpurReuseMeters}: reuse accepted as a terminal spur (road
 *       to the cape/pass/dead-end); 0 for STRICT_LOOP.</li>
 *   <li>{@link #disclosures}: human-readable facts the cyclist should know
 *       (e.g. "contains retraced scenic spur: 4.2km"). Non-null, possibly empty.</li>
 * </ul>
 */
public final class RoundTripQualityResult {

  /**
   * Why a route was rejected; the engine treats the two tiers differently.
   * Only meaningful when {@link #isAccepted()} is false.
   * <ul>
   *   <li><b>STRUCTURAL</b> — broken / not a rideable loop (no track, too few
   *       nodes, un-routable beeline, ferry without opt-in, didn't close).
   *       Always hard-rejected: nothing usable to offer.</li>
   *   <li><b>QUALITY</b> — rideable but suboptimal (off distance target,
   *       self-crossing, profile-hostile surface, mid-route backtracking).
   *       Returned with an advisory warning by default; strict mode
   *       ({@link RoutingContext#roundTripStrictQuality}) hard-rejects.</li>
   * </ul>
   */
  public enum RejectionTier { STRUCTURAL, QUALITY }

  private final boolean accepted;
  private final RouteShape shape;
  private final String rejectionReason;
  private final RejectionTier rejectionTier;
  private final double totalReuseRatio;
  private final int maxContiguousReuseMeters;
  private final int terminalStemReuseMeters;
  private final int scenicSpurReuseMeters;
  private final List<String> disclosures;
  private final LoopAnalysis loopAnalysis;

  private RoundTripQualityResult(Builder b) {
    this.accepted = b.accepted;
    this.shape = b.shape;
    this.rejectionReason = b.rejectionReason;
    this.rejectionTier = b.rejectionTier;
    this.totalReuseRatio = b.totalReuseRatio;
    this.maxContiguousReuseMeters = b.maxContiguousReuseMeters;
    this.terminalStemReuseMeters = b.terminalStemReuseMeters;
    this.scenicSpurReuseMeters = b.scenicSpurReuseMeters;
    this.disclosures = (b.disclosures == null || b.disclosures.isEmpty())
      ? Collections.emptyList()
      : Collections.unmodifiableList(new ArrayList<>(b.disclosures));
    this.loopAnalysis = b.loopAnalysis;
  }

  public boolean isAccepted() { return accepted; }
  public RouteShape getShape() { return shape; }
  public String getRejectionReason() { return rejectionReason; }
  /** Rejection tier (STRUCTURAL vs QUALITY); only meaningful when not accepted. */
  public RejectionTier getRejectionTier() { return rejectionTier; }
  double getTotalReuseRatio() { return totalReuseRatio; }
  int getMaxContiguousReuseMeters() { return maxContiguousReuseMeters; }
  int getTerminalStemReuseMeters() { return terminalStemReuseMeters; }
  int getScenicSpurReuseMeters() { return scenicSpurReuseMeters; }
  public List<String> getDisclosures() { return disclosures; }

  /**
   * The gate's own crossing count for the evaluated track (via the shared
   * {@link LoopAnalysis}), or -1 when the gate rejected before reaching that
   * scan (result discarded anyway) or the caller built this result directly.
   * Downstream consumers that re-derive the same count for the same track
   * ({@link RouteChoiceScore}, the shipped-crossings advisory) should read
   * this instead of re-scanning — one full-track crossing scan per track.
   */
  int getSelfIntersections() {
    return loopAnalysis == null ? -1 : loopAnalysis.selfIntersections;
  }

  /**
   * The full shared crossing analysis the gate computed for this track, or
   * {@code null} when the gate rejected before the scan. Carries what the
   * count alone cannot: the small-loop classification and crossing locations
   * the scorer's lasso surcharge and map markers need.
   */
  LoopAnalysis getLoopAnalysis() { return loopAnalysis; }

  /**
   * Copy with {@link #getLoopAnalysis()} replaced — the gate computes the
   * analysis once per evaluated track and stamps it onto whichever result path
   * (classified or explicit-via-adjusted) ends up returned; tests pass
   * {@code null} to build an unstamped twin.
   */
  RoundTripQualityResult withLoopAnalysis(LoopAnalysis analysis) {
    Builder b = builder()
      .accepted(accepted)
      .shape(shape)
      .totalReuseRatio(totalReuseRatio)
      .maxContiguousReuseMeters(maxContiguousReuseMeters)
      .terminalStemReuseMeters(terminalStemReuseMeters)
      .scenicSpurReuseMeters(scenicSpurReuseMeters)
      .loopAnalysis(analysis);
    if (accepted) {
      if (rejectionReason != null) {
        throw new IllegalStateException("accepted result must not have a rejectionReason");
      }
    } else {
      b.reject(rejectionTier, rejectionReason);
    }
    for (String d : disclosures) b.addDisclosure(d);
    return b.build();
  }

  @Override
  public String toString() {
    return "RoundTripQualityResult["
      + "accepted=" + accepted
      + ", shape=" + shape
      + (rejectionReason != null ? ", reason=" + rejectionReason : "")
      + (!accepted && rejectionTier != null ? ", tier=" + rejectionTier : "")
      + ", reuseRatio=" + String.format(java.util.Locale.US, "%.2f", totalReuseRatio)
      + ", maxContiguous=" + maxContiguousReuseMeters + "m"
      + ", stem=" + terminalStemReuseMeters + "m"
      + ", spur=" + scenicSpurReuseMeters + "m"
      + (disclosures.isEmpty() ? "" : ", disclosures=" + disclosures)
      + "]";
  }

  public static Builder builder() { return new Builder(); }

  public static final class Builder {
    private boolean accepted;
    private RouteShape shape = RouteShape.STRICT_LOOP;
    private String rejectionReason;
    // STRUCTURAL is only a placeholder for accepted results (where the tier is
    // never read). A rejected result MUST set the tier explicitly — build()
    // enforces it — so a new reject site can't silently default to hard-reject.
    private RejectionTier rejectionTier = RejectionTier.STRUCTURAL;
    private boolean rejectionTierSet = false;
    private double totalReuseRatio;
    private int maxContiguousReuseMeters;
    private int terminalStemReuseMeters;
    private int scenicSpurReuseMeters;
    private List<String> disclosures;
    private LoopAnalysis loopAnalysis;

    public Builder accepted(boolean v) { this.accepted = v; return this; }
    public Builder shape(RouteShape v) { this.shape = v; return this; }
    public Builder rejectionReason(String v) { this.rejectionReason = v; return this; }
    public Builder rejectionTier(RejectionTier v) { this.rejectionTier = v; this.rejectionTierSet = true; return this; }

    /**
     * Reject with an explicit tier and reason in one call. Preferred over the
     * separate setters: the tier is a required argument and cannot be forgotten,
     * so a QUALITY rejection can never silently fall back to a STRUCTURAL
     * hard-reject. Set {@link #shape} separately.
     */
    public Builder reject(RejectionTier tier, String reason) {
      this.accepted = false;
      this.rejectionTier = tier;
      this.rejectionTierSet = true;
      this.rejectionReason = reason;
      return this;
    }
    public Builder totalReuseRatio(double v) { this.totalReuseRatio = v; return this; }
    public Builder maxContiguousReuseMeters(int v) { this.maxContiguousReuseMeters = v; return this; }
    public Builder terminalStemReuseMeters(int v) { this.terminalStemReuseMeters = v; return this; }
    public Builder scenicSpurReuseMeters(int v) { this.scenicSpurReuseMeters = v; return this; }
    Builder loopAnalysis(LoopAnalysis v) { this.loopAnalysis = v; return this; }
    public Builder addDisclosure(String d) {
      if (d == null) return this;
      if (disclosures == null) disclosures = new ArrayList<>();
      disclosures.add(d);
      return this;
    }
    public RoundTripQualityResult build() {
      // Defensive: rejected results must carry a reason; accepted results must not.
      if (!accepted && (rejectionReason == null || rejectionReason.isEmpty())) {
        throw new IllegalStateException("rejected result must have a rejectionReason");
      }
      if (accepted && rejectionReason != null) {
        throw new IllegalStateException("accepted result must not have a rejectionReason");
      }
      // A rejected result must state its tier explicitly (STRUCTURAL vs QUALITY)
      // so the engine's lenient/strict policy is never decided by a forgotten
      // default. Use reject(tier, reason) or rejectionTier(tier).
      if (!accepted && !rejectionTierSet) {
        throw new IllegalStateException("rejected result must set a rejectionTier "
          + "(use reject(tier, reason) or rejectionTier(tier))");
      }
      return new RoundTripQualityResult(this);
    }
  }
}
