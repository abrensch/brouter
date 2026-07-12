package btools.router;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Measurement probe for the isochrone cost-budget calibration on profiles the
 * loop-quality matrix cannot exercise. The region configs exclude mtb
 * everywhere (no test region has singletrack density, so its cost/m gate trips
 * regardless of loop shape) — but the calibration's target defect is loop
 * LENGTH collapse from a cost-starved candidate pool, which is measurable
 * without any surface-quality gate.
 *
 * <p>This is not a quality gate: it asserts only that a track is produced and
 * that its length did not collapse below half the request. The interesting
 * output is the printed distR + the "isochrone: calibrated cost budget" log
 * lines in the captured stdout — compare across builds when touching the
 * ISO_BUDGET_* machinery.
 */
public class IsoBudgetStarvedProfileProbeTest {

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  private static final double TARGET_LOOP_METERS = 60_000;

  @Test
  public void starvedProfileLoopLengthProbe() throws Exception {
    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    // Freiburg (Black Forest edge) and Basel (Jura foothills) — dense mixed
    // networks where mtb's high costfactors historically starved the pool.
    double[][] starts = {{7.852, 48.000}, {7.590, 47.560}};
    String[] startNames = {"freiburg", "basel"};
    // mtb = the starved profile under test; gravel = healthy-ish control.
    String[] profiles = {"mtb", "gravel"};

    int radius = (int) Math.round(TARGET_LOOP_METERS / (2 * Math.PI));
    StringBuilder failures = new StringBuilder();

    for (int s = 0; s < starts.length; s++) {
      for (String profile : profiles) {
        List<OsmNodeNamed> wplist = new ArrayList<>();
        OsmNodeNamed start = new OsmNodeNamed();
        start.name = "from";
        start.ilon = 180_000_000 + (int) (starts[s][0] * 1_000_000 + 0.5);
        start.ilat = 90_000_000 + (int) (starts[s][1] * 1_000_000 + 0.5);
        wplist.add(start);

        RoutingContext rctx = new RoutingContext();
        rctx.localFunction = new File(projectDir, "misc/profiles2/" + profile + ".brf")
          .getAbsolutePath();
        rctx.startDirection = 0;
        rctx.roundTripDistance = radius;
        rctx.roundTripAlgorithm = RoundTripAlgorithm.ISO_GREEDY;
        // Lenient: we measure SHAPE/length, not surface quality — mtb would
        // fail every cost/m gate in these regions regardless of loop shape.
        rctx.roundTripStrictQuality = false;

        String caseName = startNames[s] + "_" + profile;
        String outPath = new File(outputDir.getRoot(), caseName).getAbsolutePath();
        RoutingEngine re = new RoutingEngine(outPath, outPath, segDir, wplist, rctx,
          RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
        re.doRun(0);

        OsmTrack track = re.getFoundTrack();
        if (track == null) {
          System.out.println("PROBE " + caseName + ": NO TRACK error=" + re.getErrorMessage());
          failures.append(caseName).append(": no track; ");
          continue;
        }
        double distR = track.distance / TARGET_LOOP_METERS;
        System.out.println(String.format(Locale.US,
          "PROBE %s: target=%.0fm actual=%dm distR=%.2f",
          caseName, TARGET_LOOP_METERS, track.distance, distR));
        if (distR < 0.5) {
          failures.append(String.format(Locale.US,
            "%s: collapsed to distR=%.2f; ", caseName, distR));
        }
      }
    }

    assertEquals("loop-length collapse (the calibration's target defect)",
      "", failures.toString());
  }
}
