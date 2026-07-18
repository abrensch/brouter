package btools.router.roundtrip;

/**
 * The round-trip subsystem's seam to the routing engine, composed of four
 * roles: {@link LegRouter}, {@link RoundTripRequestState}, {@link EngineIO},
 * and {@link EngineContext}. Consumers take only the roles they use; the
 * {@link RoundTripOrchestrator} holds the full composite. The production
 * adapter is {@code RoutingEngine#roundTripOps()}; method names mirror the
 * engine members they delegate to.
 */
public interface RoundTripEngineOps
  extends LegRouter, RoundTripRequestState, EngineIO, EngineContext {

  /**
   * Run the engine's routing pipeline over the current waypoint list and
   * return its outcome (the engine's own result fields are captured after
   * the run).
   */
  RoutingOutcome doRouting(long budgetMs);

  /** Release the engine's routing caches. */
  void cleanupRoutingResources();
}
