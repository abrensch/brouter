package btools.router.roundtrip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import btools.router.OsmNodeNamed;
import btools.router.ProfileCache;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

/**
 * Selection table for {@link RoundTripOrchestrator#resolveLadder}: which tier
 * strategy each algorithm resolves to, across samewayback and resource
 * constraints — including the silent AUTO→BOUNDED downgrade on constrained
 * devices and the samewayback fallback to the waypoint tier. Pure resolution,
 * no routing: the engine never runs, so no segment data is needed.
 */
public class RoundTripLadderResolutionTest {

  private static final double RADIUS = 4800;

  private RoundTripOrchestrator orchestrator(boolean samewayback, int memoryclass, int vias) {
    // The profile is never parsed on this path (resolution only, no routing),
    // so classifyProfileClass sees UNKNOWN, which resolveAuto accepts.
    return orchestrator(samewayback, memoryclass, vias, "trekking.brf", false);
  }

  private RoundTripOrchestrator orchestrator(boolean samewayback, int memoryclass, int vias,
                                             String profile, boolean parseProfile) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    for (int i = 0; i <= vias; i++) {
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = i == 0 ? "from" : ("via" + i);
      n.ilon = 188720000 + i;
      n.ilat = 140000000;
      wps.add(n);
    }
    RoutingContext rc = new RoutingContext();
    rc.allowSamewayback = samewayback;
    rc.memoryclass = memoryclass;
    // A real profile path — the engine constructor derives its base folder
    // from it. With parseProfile the profile's globals (validFor*) are loaded
    // so classifyProfileClass sees the real profile class.
    File projectDir;
    try {
      projectDir = new File(".").getCanonicalFile().getParentFile();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    rc.localFunction = new File(projectDir, "misc/profiles2/" + profile).getAbsolutePath();
    RoutingEngine re = new RoutingEngine(null, null, new File(projectDir, "unused-segments"), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    if (parseProfile) {
      ProfileCache.parseProfile(rc);
    }
    return new RoundTripOrchestrator(re.roundTripOps());
  }

  private RoundTripOrchestrator.Rung resolveSingle(RoundTripOrchestrator o, RoundTripAlgorithm algo) {
    List<RoundTripOrchestrator.Rung> ladder = o.resolveLadder(algo, RADIUS, 90);
    assertEquals("ladder is currently always a single rung", 1, ladder.size());
    assertNotNull(ladder.get(0).slice);
    return ladder.get(0);
  }

  @Test
  public void qualityResolvesToCompetitionAtMaxPreset() {
    RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(false, 256, 0), RoundTripAlgorithm.QUALITY);
    assertTrue("QUALITY runs the competition", r.strategy instanceof AutoCompetitionStrategy);
    assertEquals("QUALITY", r.slice.label);
    assertEquals("QUALITY is the competition pinned to MAX",
      RoundTripEffortPolicy.Preset.MAX, r.slice.effortPolicy.preset);
  }

  @Test
  public void qualityWithSamewaybackFallsBackToWaypointTier() {
    RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(true, 256, 0), RoundTripAlgorithm.QUALITY);
    assertTrue("planners don't honor samewayback — waypoint tier",
      r.strategy instanceof FastStrategy);
    assertEquals(RoundTripAlgorithm.WAYPOINT, r.slice.algo);
  }

  @Test
  public void autoUnconstrainedResolvesToCompetition() {
    RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(false, 256, 0), RoundTripAlgorithm.AUTO);
    assertTrue(r.strategy instanceof AutoCompetitionStrategy);
    assertEquals("AUTO", r.slice.label);
    assertEquals(RoundTripEffortPolicy.Preset.STANDARD, r.slice.effortPolicy.preset);
  }

  @Test
  public void autoOnConstrainedDeviceDowngradesToBoundedTier() {
    // memoryclass at/below CONSTRAINED_MEMORYCLASS_MAX resolves the BOUNDED
    // preset — and the ladder must route that to the bounded TIER, not run
    // the full competition with a smaller budget.
    RoundTripOrchestrator.Rung r = resolveSingle(
      orchestrator(false, RoundTripEffortPolicy.CONSTRAINED_MEMORYCLASS_MAX, 0),
      RoundTripAlgorithm.AUTO);
    assertTrue("constrained AUTO runs the bounded tier", r.strategy instanceof BoundedStrategy);
    assertEquals("AUTO(bounded)", r.slice.label);
    assertEquals(RoundTripEffortPolicy.Preset.BOUNDED, r.slice.effortPolicy.preset);
  }

  @Test
  public void balancedResolvesToBoundedTier() {
    RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(false, 256, 0), RoundTripAlgorithm.BALANCED);
    assertTrue(r.strategy instanceof BoundedStrategy);
    assertEquals("BALANCED", r.slice.label);
    assertEquals(RoundTripEffortPolicy.Preset.BOUNDED, r.slice.effortPolicy.preset);
  }

  @Test
  public void greedyAlgorithmsResolveToGreedyTier() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(false, 256, 0), algo);
      assertTrue(algo + " runs the greedy tier", r.strategy instanceof GreedyStrategy);
      assertEquals(algo, r.slice.algo);
    }
  }

  @Test
  public void greedyWithSamewaybackFallsBackToWaypointTier() {
    RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(true, 256, 0), RoundTripAlgorithm.GREEDY);
    assertTrue(r.strategy instanceof FastStrategy);
    assertEquals(RoundTripAlgorithm.WAYPOINT, r.slice.algo);
  }

  @Test
  public void autoOnFastMotorProfileResolvesToWaypointTier() {
    // Evidence 2026-07-19 (Basel, car-vario, 50-180km): the isochrone frontier
    // starves on motor cost scales (4 nodes expanded; the calibration cap of
    // ISO_BUDGET_CAP_FACTOR x searchRadius assumes bike cost-per-meter), both
    // planners return "could not build any loop", and the competition adopts
    // its own WAYPOINT candidate at ~3x the wall clock. AUTO therefore resolves
    // straight to the waypoint tier for FAST_MOTOR; the explicit planner tiers stay
    // available for future car calibration work.
    RoundTripOrchestrator.Rung r = resolveSingle(
      orchestrator(false, 256, 0, "car-vario.brf", true), RoundTripAlgorithm.AUTO);
    assertTrue("fast-motor AUTO runs the waypoint tier", r.strategy instanceof FastStrategy);
    assertEquals(RoundTripAlgorithm.WAYPOINT, r.slice.algo);
    assertEquals("AUTO(fastmotor)", r.slice.label);
  }

  @Test
  public void explicitPlannerTiersStayAvailableOnFastMotorProfiles() {
    // The FAST_MOTOR short-circuit applies only to AUTO ("choose for me"). Explicit
    // tier requests keep running what was asked — the path for calibration work.
    RoundTripOrchestrator.Rung greedy = resolveSingle(
      orchestrator(false, 256, 0, "car-vario.brf", true), RoundTripAlgorithm.GREEDY);
    assertTrue(greedy.strategy instanceof GreedyStrategy);
    RoundTripOrchestrator.Rung quality = resolveSingle(
      orchestrator(false, 256, 0, "car-vario.brf", true), RoundTripAlgorithm.QUALITY);
    assertTrue(quality.strategy instanceof AutoCompetitionStrategy);
  }

  @Test
  public void autoOnParsedBikeProfileStillResolvesToCompetition() {
    // Control: a parsed bike profile classifies as BIKE and must keep today's
    // resolution — guards the FAST_MOTOR condition against becoming too broad.
    RoundTripOrchestrator.Rung r = resolveSingle(
      orchestrator(false, 256, 0, "trekking.brf", true), RoundTripAlgorithm.AUTO);
    assertTrue(r.strategy instanceof AutoCompetitionStrategy);
    assertEquals("AUTO", r.slice.label);
  }

  @Test
  public void waypointAndIsochroneResolveToFastTier() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.ISOCHRONE}) {
      RoundTripOrchestrator.Rung r = resolveSingle(orchestrator(false, 256, 0), algo);
      assertTrue(algo + " runs the waypoint tier", r.strategy instanceof FastStrategy);
      assertEquals(algo, r.slice.algo);
    }
  }

  /**
   * The single-finalization contract: EVERY strategy — including the AUTO
   * competition — leaves its outcome on the request (track XOR error) for the
   * orchestrator's one shared finalization; no strategy finalizes itself.
   * Observed here on an engine with no segment data: every AUTO child
   * candidate fails fast, the competition publishes a clean error on the
   * request (not a thrown exception, not a silently-empty outcome), and the
   * shared pipeline's early error-return handles it. The success side is
   * exercised by every fixture-backed AUTO run (double decoration or a
   * double-gated winner would fail the message assertions in those suites).
   */
  @Test
  public void autoCompetitionLeavesOutcomeForSharedFinalization() {
    RoundTripOrchestrator o = orchestrator(false, 256, 0);
    RoundTripOrchestrator.Rung rung = resolveSingle(o, RoundTripAlgorithm.AUTO);
    assertTrue(rung.strategy instanceof AutoCompetitionStrategy);
    rung.strategy.attempt(o.request, rung.slice);
    assertTrue("failed AUTO competition must publish a clean error on the request",
      o.request.error != null && !o.request.error.isEmpty());
    assertNull("no track alongside the error (track XOR error)", o.request.track);
  }
}
