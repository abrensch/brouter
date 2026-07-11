package btools.router;

import java.util.Locale;

/**
 * Resolved effort configuration for one round-trip request.
 *
 * <p>The user-facing tiers map to fixed presets — BALANCED pins
 * {@link #BOUNDED_PRESET}, QUALITY pins {@link #MAX_PRESET} — while AUTO
 * {@link #resolveAuto resolves} a preset from request context: profile class,
 * loop length class, and resources (memoryclass + request budget).
 *
 * <p>v1 resolution deliberately changes behavior only on the evidence-backed
 * rule: constrained resources (a short request budget or a small memoryclass)
 * resolve to BOUNDED effort — the direct answer to on-device latency reports,
 * where the full competition took 3-4x the pre-#903 logic. Profile and length
 * classes are classified and logged (the {@link #rationale} travels to the
 * request log) so future rules land on recorded evidence; motorized profiles
 * additionally get a provisional-quality advisory because no loop-quality
 * corpus covers them yet.
 */
final class RoundTripEffortPolicy {

  enum Preset { BOUNDED, STANDARD, MAX }

  /** Coarse profile family, read from the profile's own validFor* globals. */
  enum ProfileClass { FOOT, BIKE, MOTOR, UNKNOWN }

  /** Coarse request-size family (v1: logged in the rationale, no tuning). */
  enum LengthClass { SMALL, STANDARD, LONG, XL }

  /** Loop length (meters) bounds for {@link LengthClass}. */
  static final int SMALL_LOOP_MAX_M = 20_000;
  static final int LONG_LOOP_MIN_M = 100_000;
  static final int XL_LOOP_MIN_M = 200_000;

  /**
   * Resources thresholds for the BOUNDED resolution rule. memoryclass 48
   * catches genuinely memory-constrained devices (the server default is 64;
   * modern phones report far more, and get BOUNDED only via a short budget or
   * an explicit BALANCED). A request budget at or below 10s cannot fund the
   * full competition anyway — resolving BOUNDED returns a disclosed
   * best-effort loop instead of a half-run competition.
   */
  static final int CONSTRAINED_MEMORYCLASS_MAX = 48;
  static final long CONSTRAINED_BUDGET_MAX_MS = 10_000;

  /** BALANCED tier / constrained-resources AUTO: reduced top-K, hard per-slice budget, no retry ladders. */
  static final RoundTripEffortPolicy BOUNDED_PRESET = new RoundTripEffortPolicy(
    Preset.BOUNDED, 2, 3, 1.0, 8_000, true, false, "BALANCED tier preset");
  /** Today's AUTO effort. */
  static final RoundTripEffortPolicy STANDARD_PRESET = new RoundTripEffortPolicy(
    Preset.STANDARD, 3, 5, 1.0, 0, false, false, "standard AUTO effort");
  /** QUALITY tier: both planners always compete, wider routed top-K, doubled plan budget. */
  static final RoundTripEffortPolicy MAX_PRESET = new RoundTripEffortPolicy(
    Preset.MAX, 4, 6, 2.0, 0, false, true, "QUALITY tier preset");

  final Preset preset;
  /** Routed candidates per planner step (each is a full Dijkstra leg). */
  final int topKNormal;
  /** Routed candidates on late steps / after a failed attempt. */
  final int topKLate;
  /** Multiplier on the planner's internal plan deadline. */
  final double planBudgetScale;
  /** Hard wall-clock clamp per dispatch slice; 0 = no tier clamp. */
  final long tierBudgetMs;
  /** Skip the Phase 2.1 axis retry and the ISO_GREEDY→GREEDY recursion. */
  final boolean skipRetryLayers;
  /** Run the plain-GREEDY competitor unconditionally (not health-gated). */
  final boolean runGreedyAlways;
  /** Human-readable resolution reason; logged once per request. */
  final String rationale;

  private RoundTripEffortPolicy(Preset preset, int topKNormal, int topKLate,
                                double planBudgetScale, long tierBudgetMs,
                                boolean skipRetryLayers, boolean runGreedyAlways,
                                String rationale) {
    this.preset = preset;
    this.topKNormal = topKNormal;
    this.topKLate = topKLate;
    this.planBudgetScale = planBudgetScale;
    this.tierBudgetMs = tierBudgetMs;
    this.skipRetryLayers = skipRetryLayers;
    this.runGreedyAlways = runGreedyAlways;
    this.rationale = rationale;
  }

  private RoundTripEffortPolicy withRationale(String newRationale) {
    return new RoundTripEffortPolicy(preset, topKNormal, topKLate, planBudgetScale,
      tierBudgetMs, skipRetryLayers, runGreedyAlways, newRationale);
  }

  /**
   * Resolve AUTO's effort from request context. Constrained resources resolve
   * to BOUNDED (the evidence-backed rule); everything else keeps standard
   * effort, with the context classes recorded in the rationale for the log.
   */
  static RoundTripEffortPolicy resolveAuto(ProfileClass profileClass, LengthClass lengthClass,
                                           int memoryclass, long requestBudgetMs) {
    String context = String.format(Locale.US, "profile=%s length=%s memoryclass=%d budget=%s",
      profileClass, lengthClass, memoryclass,
      requestBudgetMs <= 0 ? "unbounded" : requestBudgetMs + "ms");
    boolean shortBudget = requestBudgetMs > 0 && requestBudgetMs <= CONSTRAINED_BUDGET_MAX_MS;
    boolean smallMemory = memoryclass > 0 && memoryclass <= CONSTRAINED_MEMORYCLASS_MAX;
    if (shortBudget || smallMemory) {
      return BOUNDED_PRESET.withRationale("AUTO resolved BOUNDED effort — constrained resources ("
        + (shortBudget ? "request budget cannot fund the full competition" : "memory-constrained device")
        + "; " + context + ")");
    }
    return STANDARD_PRESET.withRationale("AUTO resolved STANDARD effort (" + context + ")");
  }

  static LengthClass classifyLength(double loopMeters) {
    if (loopMeters < SMALL_LOOP_MAX_M) return LengthClass.SMALL;
    if (loopMeters >= XL_LOOP_MIN_M) return LengthClass.XL;
    if (loopMeters >= LONG_LOOP_MIN_M) return LengthClass.LONG;
    return LengthClass.STANDARD;
  }

  /**
   * Classify from the profile's validFor* globals (declared by every standard
   * profile; name-independent). Motorized wins over bike for hybrid declarations
   * because its provisional-quality advisory must not be lost.
   */
  static ProfileClass classifyProfile(boolean validForFoot, boolean validForBikes,
                                      boolean validForCars) {
    if (validForCars) return ProfileClass.MOTOR;
    if (validForBikes) return ProfileClass.BIKE;
    if (validForFoot) return ProfileClass.FOOT;
    return ProfileClass.UNKNOWN;
  }
}
