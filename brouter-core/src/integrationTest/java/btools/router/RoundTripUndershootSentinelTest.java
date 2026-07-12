package btools.router;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Sentinel for the return-oracle premature-contraction class (2026-07).
 *
 * <p>The 548-cell corpus A/B found four 30km cells whose forced ISO_GREEDY
 * loops collapsed from distR 0.86-0.97 to 0.59-0.66 when sector-resolved
 * return estimates fed candidate SCORING: in elevation-asymmetric starts
 * (costly climb out, cheap direct ride back) the over-estimated returns made
 * correctly-placed candidates look like overshoots, so placement contracted
 * step by step and the loop closed ~40% short. The oracle is now scoped to
 * the return-check skip decision only (over-estimates there are
 * self-correcting — they merely trigger the real return Dijkstra earlier).
 *
 * <p>These four cells sit in terrain where the failure mode is
 * "cost-expensive but distance-short" (steady climbs on direct roads), the
 * exact opposite of the oracle's win terrain ("expensive because long":
 * walls, few crossings). The general region quality bands cannot guard this —
 * their distance-ratio floors (0.4-0.5) deliberately tolerate terrain-forced
 * undershoot, so a 0.6-class collapse passes them silently (it did: the
 * corpus crashes lived behind a green 915-test suite). This sentinel pins the
 * healthy ratios directly, with anti-flap margin below the weakest observed
 * healthy value (0.86): any future estimate/scoring change that re-introduces
 * contraction fails here loudly and names the mechanism.
 */
@RunWith(Parameterized.class)
public class RoundTripUndershootSentinelTest {

  /** Anti-flap floor: healthy values are 0.86-0.97; the crash class was 0.59-0.66. */
  private static final double MIN_DIST_RATIO = 0.75;

  @Parameterized.Parameter(0)
  public LoopTestRegion region;
  @Parameterized.Parameter(1)
  public String profileName;
  @Parameterized.Parameter(2)
  public double direction;
  @Parameterized.Parameter(3)
  public String label;

  @Parameterized.Parameters(name = "{3}")
  public static Collection<Object[]> cases() {
    return Arrays.asList(new Object[][]{
      {LoopTestRegion.ANNECY, "gravel", 180.0, "annecy_30km_gravel_S"},
      {LoopTestRegion.CRETE_SENESI, "gravel", 270.0, "crete_senesi_30km_gravel_W"},
      {LoopTestRegion.MALLORCA, "fastbike", 0.0, "mallorca_30km_fastbike_N"},
      {LoopTestRegion.MALLORCA, "gravel", 270.0, "mallorca_30km_gravel_W"},
    });
  }

  private static final int TARGET_METERS = 30000;
  private static final int SEARCH_RADIUS = 4800;

  @Test
  public void thirtyKmLoopDoesNotContract() throws Exception {
    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    LoopTestSegments.ensureRegion(segDir, region);
    File profileFile = new File(projectDir, "misc/profiles2/" + profileName + ".brf");
    Assume.assumeTrue("Profile not found: " + profileFile, profileFile.exists());

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = region.ilon;
    start.ilat = region.ilat;
    wplist.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = (int) direction;
    rctx.roundTripDistance = SEARCH_RADIUS;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.ISO_GREEDY;
    rctx.roundTripStrictQuality = false; // judge the shipped-style track

    RoutingEngine re = new RoutingEngine(null, null, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.doRun(0);

    assertNotNull(label + ": forced ISO_GREEDY produced no track ("
      + re.getErrorMessage() + ")", re.getFoundTrack());
    LoopQualityMetrics m = LoopQualityMetrics.compute(re.getFoundTrack(), TARGET_METERS, direction);
    assertTrue(label + ": distance ratio " + String.format(java.util.Locale.US, "%.2f", m.getDistanceRatio())
        + " below sentinel floor " + MIN_DIST_RATIO
        + " — the return-estimate contraction class is back (see class javadoc)",
      m.getDistanceRatio() >= MIN_DIST_RATIO);
  }
}
