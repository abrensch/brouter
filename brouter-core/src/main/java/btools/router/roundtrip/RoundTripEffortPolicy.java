package btools.router.roundtrip;

import java.util.Locale;

/**
 * Resolved effort configuration for one round-trip request. BALANCED pins
 * {@link #BOUNDED_PRESET}, QUALITY pins {@link #MAX_PRESET}; AUTO
 * {@link #resolveAuto resolves} a preset from request context.
 *
 * <p>Resolution changes behavior on two evidence-backed rules: constrained
 * resources (short request budget or small memoryclass) resolve to BOUNDED
 * effort, and fast-motorized profiles (car, motorbike — `validForCars`)
 * resolve AUTO straight to the waypoint tier (decided in the orchestrator's
 * ladder — the planner candidate pools are bike-calibrated and build no loops
 * on motor cost scales). Length classes are only classified and logged (via
 * {@link #rationale}) so future rules land on recorded evidence;
 * fast-motorized profiles additionally get a provisional-quality advisory, as
 * no loop-quality corpus covers them yet. E-bike profiles declare
 * {@code validForBikes} and stay in the bike class.
 */
public final class RoundTripEffortPolicy {

  /** Loop length (meters) bounds for {@link LengthClass}. */
  static final int SMALL_LOOP_MAX_M = 20_000;
  static final int LONG_LOOP_MIN_M = 100_000;
  static final int XL_LOOP_MIN_M = 200_000;
  /**
   * Resource thresholds for the BOUNDED rule. memoryclass 48 catches genuinely
   * memory-constrained devices (server default is 64; modern phones report far
   * more). A budget at or below 10s cannot fund the full competition, so BOUNDED
   * returns a disclosed best-effort loop instead of a half-run competition.
   */
  static final int CONSTRAINED_MEMORYCLASS_MAX = 48;
  static final long CONSTRAINED_BUDGET_MAX_MS = 10_000;
  /** BALANCED tier / constrained-resources AUTO: reduced top-K, hard per-slice budget, no retry ladders. */
  public static final RoundTripEffortPolicy BOUNDED_PRESET = new RoundTripEffortPolicy(
    Preset.BOUNDED, 2, 3, 1.0, 8_000, true, false, "BALANCED tier preset");
  /** Today's AUTO effort. */
  public static final RoundTripEffortPolicy STANDARD_PRESET = new RoundTripEffortPolicy(
    Preset.STANDARD, 3, 5, 1.0, 0, false, false, "standard AUTO effort");
  /** QUALITY tier: both planners always compete, wider routed top-K, doubled plan budget. */
  public static final RoundTripEffortPolicy MAX_PRESET = new RoundTripEffortPolicy(
    Preset.MAX, 4, 6, 2.0, 0, false, true, "QUALITY tier preset");
  public final Preset preset;
  /** Routed candidates per planner step (each is a full Dijkstra leg). */
  public final int topKNormal;
  /** Routed candidates on late steps / after a failed attempt. */
  public final int topKLate;
  /** Multiplier on the planner's internal plan deadline. */
  public final double planBudgetScale;
  /** Hard wall-clock clamp per dispatch slice; 0 = no tier clamp. */
  public final long tierBudgetMs;
  /** Skip the Phase 2.1 axis retry and the ISO_GREEDY→GREEDY recursion. */
  public final boolean skipRetryLayers;
  /** Run the plain-GREEDY competitor unconditionally (not health-gated). */
  public final boolean runGreedyAlways;
  /** Human-readable resolution reason; logged once per request. */
  public final String rationale;

  /**
   * Resolve AUTO's effort from request context: constrained resources give
   * BOUNDED, everything else STANDARD, with the context classes recorded in the
   * rationale.
   */
  public static RoundTripEffortPolicy resolveAuto(ProfileClass profileClass, LengthClass lengthClass,
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

  public static LengthClass classifyLength(double loopMeters) {
    if (loopMeters < SMALL_LOOP_MAX_M) return LengthClass.SMALL;
    if (loopMeters >= XL_LOOP_MIN_M) return LengthClass.XL;
    if (loopMeters >= LONG_LOOP_MIN_M) return LengthClass.LONG;
    return LengthClass.STANDARD;
  }

  /**
   * Classify from the profile's validFor* globals (name-independent). Fast-motorized
   * wins over bike for hybrid declarations so its provisional-quality advisory
   * is not lost.
   */
  public static ProfileClass classifyProfile(boolean validForFoot, boolean validForBikes,
                                      boolean validForCars) {
    if (validForCars) return ProfileClass.FAST_MOTOR;
    if (validForBikes) return ProfileClass.BIKE;
    if (validForFoot) return ProfileClass.FOOT;
    return ProfileClass.UNKNOWN;
  }

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

  public enum Preset {BOUNDED, STANDARD, MAX}

  /** Coarse profile family, read from the profile's own validFor* globals. */
  public enum ProfileClass {FOOT, BIKE, FAST_MOTOR, UNKNOWN}

  /** Coarse request-size family (v1: logged in the rationale, no tuning). */
  public enum LengthClass {SMALL, STANDARD, LONG, XL}
}
