package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;


/**
 * Round-trip tier and dispatch selection — engine-side policy statics
 * (selectRoundTripAlgorithm, selectGreedySubRouteCount, greedySubRouteCountPlan,
 * selectIsoStartPolicy, greedySupports). These live here rather than with
 * GreedyRoundTripPlannerTest because they test package-private RoutingEngine
 * members, which stay engine-internal behind the RoundTripEngineOps seam.
 */
public class RoundTripTierSelectionTest {

  @Test
  public void autoSelectsGreedyForLargeRadius() {
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripOrchestrator.selectRoundTripAlgorithm(8000));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripOrchestrator.selectRoundTripAlgorithm(5000));
  }

  @Test
  public void autoFallbackSelectsGreedyForSmallRadius() {
    // Generated-loop AUTO resolution now runs inside doRoundTrip() via the
    // greedy-first candidate competition. The cheap helper is only a fallback
    // for unsupported/direct callers, and it must keep the same greedy default
    // across radii.
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripOrchestrator.selectRoundTripAlgorithm(4000));
    Assert.assertEquals(RoundTripAlgorithm.GREEDY, RoundTripOrchestrator.selectRoundTripAlgorithm(1500));
  }

  @Test
  public void greedySubRouteCountScalesWithDistanceAndProfile() {
    Assert.assertEquals(3, GreedyStrategy.selectGreedySubRouteCount(7000, "fastbike"));
    Assert.assertEquals(4, GreedyStrategy.selectGreedySubRouteCount(20000, "fastbike"));
    Assert.assertEquals(5, GreedyStrategy.selectGreedySubRouteCount(50000, "fastbike"));
    Assert.assertEquals(6, GreedyStrategy.selectGreedySubRouteCount(90000, "fastbike"));
    Assert.assertEquals(6, GreedyStrategy.selectGreedySubRouteCount(50000, "mtb"));
    Assert.assertEquals(6, GreedyStrategy.selectGreedySubRouteCount(90000, "mtb"));
  }

  @Test
  public void defaultSubRouteCountPlanStillTriesMoreStepsBeforeFewer() {
    Assert.assertArrayEquals(new int[]{5, 6, 4, 3},
      GreedyStrategy.greedySubRouteCountPlan(5, GreedyStrategy.IsoStartPolicy.BLEND));
  }

  @Test
  public void graphNativeOnlySubRouteCountPlanPrefersFewerStepsBeforeMore() {
    Assert.assertArrayEquals(new int[]{4, 5, 3, 6},
      GreedyStrategy.greedySubRouteCountPlan(5, GreedyStrategy.IsoStartPolicy.GRAPH_NATIVE_ONLY));
  }

  @Test
  public void isoStartPolicyKeepsRichPoolBlended() {
    IsoPoolHealth.PoolShape rich = new IsoPoolHealth.PoolShape(24, 12, 360.0, 4, true);
    Assert.assertEquals(GreedyStrategy.IsoStartPolicy.BLEND,
      GreedyStrategy.selectIsoStartPolicy(rich));
  }

  @Test
  public void isoStartPolicyKeepsWeakAdmittedPoolBlended() {
    // The weakest admitted shape scores exactly 0.50 = DEGRADED. The planner's
    // influence reduction (stripped priors + extra quota seat, active from
    // step 1 via the static deduction) is the calibrated response — a
    // graph-native-only start is reserved for UNHEALTHY, which static shape
    // alone cannot reach.
    IsoPoolHealth.PoolShape weak = new IsoPoolHealth.PoolShape(6, 4, 180.0, 1, false);
    Assert.assertEquals(GreedyStrategy.IsoStartPolicy.BLEND,
      GreedyStrategy.selectIsoStartPolicy(weak));
  }

  @Test
  public void isoStartPolicyStartsGraphNativeWhenPoolNotAdmitted() {
    Assert.assertEquals(GreedyStrategy.IsoStartPolicy.GRAPH_NATIVE_ONLY,
      GreedyStrategy.selectIsoStartPolicy(null));
  }

  @Test
  public void greedyRejectsAllowSamewayback() {
    // allowSamewayback semantics (out-and-back) are not implemented by greedy;
    // dispatch must fall back to the waypoint-based algorithm.
    Assert.assertFalse("greedy should not handle allowSamewayback",
      RoundTripOrchestrator.greedySupports(true, 1));
  }

  @Test
  public void greedyRejectsExtraUserViaPoints() {
    // User-supplied via points are not honored by greedy today; must fall back.
    Assert.assertFalse("greedy should not handle extra user via points",
      RoundTripOrchestrator.greedySupports(false, 2));
    Assert.assertFalse("greedy should not handle 3 user waypoints",
      RoundTripOrchestrator.greedySupports(false, 3));
  }

  @Test
  public void greedyAcceptsLoneStartWaypoint() {
    Assert.assertTrue("greedy supports a single start waypoint",
      RoundTripOrchestrator.greedySupports(false, 1));
  }

}
