package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Evaluation sweep for adding mtb to the loop-quality matrix — measurement
 * only, no quality gates. Run explicitly with {@code -Dmtb.sweep=true}; the
 * default suite skips it.
 *
 * <p>Background: every {@link LoopTestRegion} excludes mtb, based on a June
 * 2026 verification that predates the isochrone cost-budget calibration. That
 * verification ran on the fixed {@code searchRadius × 4} budget, which starves
 * the mtb candidate pool (measured: Basel mtb 60km produced NO loop at all on
 * the fixed budget, distR 0.87 calibrated — see
 * ISO-BUDGET-CALIBRATION-FINDINGS.md). The loop-shape half of the exclusion
 * evidence is therefore stale; the surface-cost half (no singletrack density
 * in the current regions) still needs per-region measurement, which is what
 * this sweep provides.
 *
 * <p>Candidates: the three existing regions with the most plausible trail
 * networks (Freiburg, Garmisch, Girona) plus two classic MTB destinations on
 * tiles already in segments4 (Finale Ligure on E5_N40, Vosges/La Bresse on
 * E5_N45). Per the region-file contract, a candidate graduates into the
 * matrix only if the sweep shows clean loops — thresholds confirmed
 * empirically, not curve-fit.
 */
public class MtbRegionEvaluationSweepTest {

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  private static final class Candidate {
    final String name;
    final double lon;
    final double lat;

    Candidate(String name, double lon, double lat) {
      this.name = name;
      this.lon = lon;
      this.lat = lat;
    }
  }

  private static final Candidate[] CANDIDATES = {
    new Candidate("finale_ligure", 8.343, 44.170),
    new Candidate("vosges_labresse", 6.870, 48.006),
    new Candidate("freiburg", 7.852, 48.000),
    new Candidate("garmisch", 11.100, 47.500),
    new Candidate("girona", 2.8214, 41.9794),
  };

  private static final int[] LOOP_KM = {30, 60};
  private static final int[] DIRECTIONS = {0, 90, 180, 270};

  @Test
  public void sweep() throws Exception {
    Assume.assumeTrue("run explicitly with -Dmtb.sweep=true",
      Boolean.getBoolean("mtb.sweep"));

    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    File profileFile = new File(projectDir, "misc/profiles2/mtb.brf");

    for (Candidate c : CANDIDATES) {
      for (int km : LOOP_KM) {
        for (int dir : DIRECTIONS) {
          runCase(c, km, dir, segDir, profileFile);
        }
      }
    }
  }

  private void runCase(Candidate c, int km, int dir, File segDir, File profileFile)
      throws Exception {
    double targetMeters = km * 1000.0;
    int radius = (int) Math.round(targetMeters / (2 * Math.PI));
    String caseName = String.format(Locale.US, "%s_%dkm_mtb_%d", c.name, km, dir);

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = 180_000_000 + (int) (c.lon * 1_000_000 + 0.5);
    start.ilat = 90_000_000 + (int) (c.lat * 1_000_000 + 0.5);
    wplist.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = dir;
    rctx.roundTripDistance = radius;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO; // what production ships
    rctx.roundTripStrictQuality = false;               // measure, don't gate

    String outPath = new File(outputDir.getRoot(), caseName).getAbsolutePath();
    RoutingEngine re = new RoutingEngine(outPath, outPath, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.doRun(0);

    OsmTrack track = re.getFoundTrack();
    if (track == null) {
      System.out.println("SWEEP " + caseName + ": NO TRACK error=" + re.getErrorMessage());
      return;
    }

    LoopQualityMetrics m = LoopQualityMetrics.compute(track, (int) targetMeters, dir);
    // Surface cost/m: back out elevation cost so the number is comparable to
    // the gate bar (mirrors LoopQualityTestBase.surfaceCostPerMeter).
    long elevCost = 0;
    for (OsmPathElement n : track.nodes) {
      if (n.message != null) elevCost += n.message.linkelevationcost;
    }
    double surfCostPerM = Math.max(0, track.cost - elevCost) / (double) track.distance;
    double rcs = RouteChoiceScore.score(track, (int) targetMeters, "mtb", null, dir)
      .qualityScore();

    System.out.println(String.format(Locale.US,
      "SWEEP %s: distR=%.2f reuse=%.1f%% surfCost/m=%.2f cost/m=%.2f rcs=%.2f "
        + "composite=%.2f dirD=%.0f selfX=%d",
      caseName, m.getDistanceRatio(), m.getRoadReusePercent(), surfCostPerM,
      m.getAverageCostPerMeter(), rcs, m.compositeScore(),
      m.getDirectionDeltaDegrees(), m.getSelfIntersections()));
  }
}
