package btools.router;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import btools.router.roundtrip.RoundTripAlgorithm;
import btools.router.roundtrip.RoundTripResult;

/**
 * Round-trip performance budget suite (work units + latency contracts).
 *
 * <p>Motivation (2026-07): AUTO's 3-5x cost regression vs the 1.7.9 round trip
 * shipped behind a fully green quality suite — nothing measured computation.
 * This suite pins the two things that ARE robustly assertable:
 *
 * <ul>
 *   <li><b>Work units</b> — {@code linksProcessed} (Dijkstra link expansions,
 *       aggregated across AUTO child engines) and the planner's routed
 *       candidate count. Hardware-independent; the engine is deterministic for
 *       identical requests, but budget-gated retry ladders adapt to machine
 *       speed, so ceilings carry a generous anti-flap margin (~2x calibrated
 *       peak) and catch order-of-magnitude blowups, not percent drift.</li>
 *   <li><b>The BALANCED latency contract</b> — the tier promises
 *       {@code min(request budget, 8s)} per slice, worst case two slices
 *       (planner + WAYPOINT fallback). Asserted with CI-load grace.</li>
 * </ul>
 *
 * <p>Wall-clock for all cells is written to
 * {@code build/reports/perf/roundtrip-perf.jsonl} for trend-watching, never
 * asserted (except the BALANCED contract). The Android-constraint axis re-runs
 * key cells with {@code memoryclass=32} — the honest, reproducible slice of
 * "simulate a phone": small cache, tight budget.
 *
 * <p>Measurement hygiene: engines run with a null {@code outfileBase} —
 * doRouting's output-file alternative-cycling (a rerun that reproduces the
 * same track advances to the NEXT alternative) makes any file-writing rerun
 * time a different route.
 */
@RunWith(Parameterized.class)
public class RoundTripPerfBudgetTest {

  /** BALANCED contract: two 8s slices plus 50% CI-load grace. */
  private static final long BALANCED_WALL_CAP_MS = 24_000;

  /** Set -Dperf.calibrate=true to record metrics without asserting ceilings. */
  private static final boolean CALIBRATE = Boolean.getBoolean("perf.calibrate");

  @Parameterized.Parameter(0)
  public LoopTestRegion region;
  @Parameterized.Parameter(1)
  public int loopMeters;
  @Parameterized.Parameter(2)
  public String profileName;
  @Parameterized.Parameter(3)
  public RoundTripAlgorithm algorithm;
  @Parameterized.Parameter(4)
  public int memoryClass;
  @Parameterized.Parameter(5)
  public int maxLinksProcessed;
  @Parameterized.Parameter(6)
  public int maxCandidatesRouted; // -1 = not asserted (no planner result)
  @Parameterized.Parameter(7)
  public String label;

  @Parameterized.Parameters(name = "{7}")
  public static Collection<Object[]> cases() {
    // Ceilings calibrated 2026-07-11 (M-series dev box, segments 2026-06),
    // set at ~2-3x the observed values (links are deterministic per request;
    // budget-gated ladders adapt to machine speed, hence the margin). A breach means an
    // order-of-magnitude work regression, not noise. Recalibrate with
    // -Dperf.calibrate=true after intentional planner-cost changes.
    return Arrays.asList(new Object[][]{
      // Small-loop class (afischerdev's CLI scenario scale)
      {LoopTestRegion.BASEL, 15_000, "fastbike", RoundTripAlgorithm.WAYPOINT, 64, 200_000, -1, "basel_15km_fastbike_FAST"},
      {LoopTestRegion.BASEL, 15_000, "fastbike", RoundTripAlgorithm.BALANCED, 64, 8_000_000, 60, "basel_15km_fastbike_BALANCED"},
      {LoopTestRegion.BASEL, 15_000, "fastbike", RoundTripAlgorithm.AUTO, 64, 9_000_000, -1, "basel_15km_fastbike_AUTO"},
      // Standard class
      {LoopTestRegion.BASEL, 100_000, "fastbike", RoundTripAlgorithm.BALANCED, 64, 1_000_000, 120, "basel_100km_fastbike_BALANCED"},
      {LoopTestRegion.BASEL, 100_000, "fastbike", RoundTripAlgorithm.AUTO, 64, 2_500_000, -1, "basel_100km_fastbike_AUTO"},
      // Max-effort tier: both planners always + top-K 4/6 + doubled plan budget
      {LoopTestRegion.BASEL, 100_000, "fastbike", RoundTripAlgorithm.QUALITY, 64, 6_000_000, -1, "basel_100km_fastbike_QUALITY"},
      // Sentinel terrain under the bounded tier
      {LoopTestRegion.MALLORCA, 30_000, "gravel", RoundTripAlgorithm.BALANCED, 64, 1_000_000, 90, "mallorca_30km_gravel_BALANCED"},
      // Alpine worst case (the 20.7s AUTO cell of the 2026-07 sweep)
      {LoopTestRegion.GARMISCH, 50_000, "gravel", RoundTripAlgorithm.AUTO, 64, 1_500_000, -1, "garmisch_50km_gravel_AUTO"},
      {LoopTestRegion.GARMISCH, 50_000, "gravel", RoundTripAlgorithm.WAYPOINT, 64, 300_000, -1, "garmisch_50km_gravel_FAST"},
      // Android-constraint axis: memoryclass=32, budget must still hold
      {LoopTestRegion.BASEL, 100_000, "fastbike", RoundTripAlgorithm.BALANCED, 32, 1_000_000, 120, "basel_100km_fastbike_BALANCED_mem32"},
      {LoopTestRegion.MALLORCA, 30_000, "gravel", RoundTripAlgorithm.BALANCED, 32, 1_000_000, 90, "mallorca_30km_gravel_BALANCED_mem32"},
    });
  }

  @Test
  public void staysWithinWorkAndLatencyBudget() throws Exception {
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
    rctx.startDirection = 90; // fixed: no random start bearing in a perf cell
    rctx.roundTripLength = loopMeters;
    rctx.roundTripAlgorithm = algorithm;
    rctx.memoryclass = memoryClass;

    RoutingEngine re = new RoutingEngine(null, null, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    long t0 = System.currentTimeMillis();
    re.doRun(0);
    long wallMs = System.currentTimeMillis() - t0;

    int links = re.getLinksProcessed();
    RoundTripResult rr = re.getLastRoundTripResult();
    int candRouted = rr == null ? -1 : rr.getCandidatesRouted();
    OsmTrack track = re.getFoundTrack();

    report(projectDir, wallMs, links, candRouted, track);

    assertNotNull(label + ": no track (" + re.getErrorMessage() + ")", track);
    if (CALIBRATE) {
      System.out.println("PERF-CALIBRATE " + label + " wallMs=" + wallMs
        + " links=" + links + " candRouted=" + candRouted
        + " dist=" + track.distance);
      return;
    }
    assertTrue(label + ": linksProcessed " + links + " exceeds work ceiling "
      + maxLinksProcessed + " — an order-of-magnitude cost regression",
      links <= maxLinksProcessed);
    if (maxCandidatesRouted >= 0 && candRouted >= 0) {
      assertTrue(label + ": candidatesRouted " + candRouted + " exceeds ceiling "
        + maxCandidatesRouted, candRouted <= maxCandidatesRouted);
    }
    if (algorithm == RoundTripAlgorithm.BALANCED) {
      assertTrue(label + ": BALANCED took " + wallMs + "ms — breaks the bounded-tier "
        + "latency contract (cap " + BALANCED_WALL_CAP_MS + "ms = 2 slices + grace)",
        wallMs <= BALANCED_WALL_CAP_MS);
    }
  }

  private void report(File projectDir, long wallMs, int links, int candRouted,
                      OsmTrack track) {
    try {
      File dir = new File("build/reports/perf");
      dir.mkdirs();
      File out = new File(dir, "roundtrip-perf.jsonl");
      try (Writer w = new FileWriter(out, StandardCharsets.UTF_8, true)) {
        w.write(String.format(Locale.US,
          "{\"label\":\"%s\",\"algo\":\"%s\",\"mem\":%d,\"wallMs\":%d,"
            + "\"links\":%d,\"candRouted\":%d,\"dist\":%d,\"target\":%d}%n",
          label, algorithm, memoryClass, wallMs, links, candRouted,
          track == null ? -1 : track.distance, loopMeters));
      }
    } catch (Exception e) {
      // Reporting must never fail the perf cell.
      System.err.println("perf report write failed: " + e);
    }
  }
}
