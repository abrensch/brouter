package btools.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import btools.router.roundtrip.RoundTripAlgorithm;
import btools.router.roundtrip.RoundTripQualityGate;
import btools.router.roundtrip.RoundTripQualityResult;

/**
 * Safety net for the child-verdict transport: before the AUTO competition
 * consumes a child engine's published gate verdict
 * ({@link RoutingEngine#getLastRoundTripQuality()}) instead of re-gating the
 * child's track in the parent, this test proves — empirically, per candidate
 * algorithm — that the published verdict is IDENTICAL to the verdict the
 * parent's re-gate would compute on the same track. The historical comment in
 * {@code runChildCandidate} claimed "in practice both agree"; this pins that
 * claim so any future divergence (profile-classification drift, ferry or
 * forced-corridor flag mismatch, desired-distance skew) fails here with a
 * field-level diff instead of silently changing AUTO's ranking.
 *
 * <p>Children are simulated exactly the way {@code runChildCandidate} builds
 * them: fresh engine, decoration suppressed, lenient quality policy. The
 * parent-style recompute mirrors {@code evaluateRoundTripGate}: same expected
 * distance (2*PI*radius), same paved classification (pinned per profile by
 * {@code PavedProfileProbeTest}), {@code allowSamewayback} = the planner's
 * forced-corridor marker, explicit-via off, ferries off (the fixture default).
 *
 * <p>Also pins the publication contract itself: a request that never reaches
 * the gate (fastbike GREEDY on this fixture builds no loop) publishes null.
 */
public class ChildVerdictTransportEquivalenceTest {

  private static final int RADIUS = 1000;

  private static RoutingEngine runChildLike(String profile, RoundTripAlgorithm algo,
                                            int direction) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = 180000000 + (int) (8.72 * 1000000 + 0.5);
    start.ilat = 90000000 + (int) (50.0 * 1000000 + 0.5);
    wps.add(start);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = new java.io.File(RoundTripFixture.projectDir(),
      "misc/profiles2/" + profile + ".brf").getAbsolutePath();
    rc.startDirection = direction;
    rc.roundTripDistance = RADIUS;
    rc.roundTripAlgorithm = algo;
    rc.roundTripStrictQuality = false;
    rc.roundTripSuppressDecoration = true; // exactly how AUTO builds children
    RoutingEngine re = new RoutingEngine(null, null,
      new java.io.File(RoundTripFixture.projectDir(),
        "brouter-map-creator/build/resources/test/tmp/segments"),
      wps, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(0);
    return re;
  }

  private static void assertVerdictEquivalence(String profile, boolean paved,
                                               RoundTripAlgorithm algo, int direction) {
    RoutingEngine child = runChildLike(profile, algo, direction);
    OsmTrack track = child.getFoundTrack();
    String label = profile + "/" + algo + "/" + direction;
    Assert.assertNotNull(label + ": fixture must produce a track", track);
    RoundTripQualityResult published = child.getLastRoundTripQuality();
    Assert.assertNotNull(label + ": a gated request must publish its verdict", published);

    boolean forcedCorridor = child.getLastRoundTripResult() != null
      && child.getLastRoundTripResult().isForcedCorridorAccepted();
    RoundTripQualityResult recomputed = RoundTripQualityGate.evaluate(
      track, 2 * Math.PI * RADIUS, paved,
      /*allowSamewayback*/ forcedCorridor, /*explicitViaMode*/ false, /*allowFerries*/ false);

    Assert.assertEquals(label + ": accepted", recomputed.isAccepted(), published.isAccepted());
    Assert.assertEquals(label + ": shape", recomputed.getShape(), published.getShape());
    Assert.assertEquals(label + ": rejection reason",
      recomputed.getRejectionReason(), published.getRejectionReason());
    Assert.assertEquals(label + ": disclosures",
      recomputed.getDisclosures(), published.getDisclosures());
    // toString covers the numeric surface too (reuse ratio, stem/spur meters,
    // tier) — a full-fidelity field diff without widening test visibility.
    Assert.assertEquals(label + ": full verdict", recomputed.toString(), published.toString());
  }

  @Test
  public void greedyChildVerdictMatchesParentRecompute() {
    assertVerdictEquivalence("gravel", false, RoundTripAlgorithm.GREEDY, 90);
    assertVerdictEquivalence("gravel", false, RoundTripAlgorithm.GREEDY, 270);
  }

  @Test
  public void isoGreedyChildVerdictMatchesParentRecompute() {
    assertVerdictEquivalence("gravel", false, RoundTripAlgorithm.ISO_GREEDY, 90);
  }

  @Test
  public void waypointChildVerdictMatchesParentRecompute() {
    // The legacy fallback candidate — no planner result, so the transport must
    // work for engines that only ever publish through the quality field.
    assertVerdictEquivalence("trekking", false, RoundTripAlgorithm.WAYPOINT, 90);
  }

  @Test
  public void ungatedRequestPublishesNullVerdict() {
    // fastbike GREEDY on this fixture cannot build a loop: the request errors
    // before the gate runs, so the publication must be null — the consumer's
    // signal to fall back to its own evaluation (defensive path only).
    RoutingEngine child = runChildLike("fastbike", RoundTripAlgorithm.GREEDY, 90);
    Assert.assertNull("no track for fastbike GREEDY on the fixture", child.getFoundTrack());
    Assert.assertNull("a request that never reached the gate publishes no verdict",
      child.getLastRoundTripQuality());
  }
}
