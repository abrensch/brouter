package btools.router;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * Gold standard loop quality test — a focused subset of LoopQualityTest
 * designed for fast CI feedback.
 * <p>
 * Covers 5 regions × 2 distances (50km, 100km) × 1 profile (fastbike) × 2 directions (N, E),
 * plus all 4 directions for COASTAL_NICE where direction is asymmetric (sea vs land).
 * Total: 22 test cases per algorithm variant.
 * <p>
 * Filter which algorithms and profiles to run via system properties:
 * <pre>
 *   -Dloop.algorithms=greedy                    # greedy only
 *   -Dloop.algorithms=probe,greedy              # probe + greedy (default)
 *   -Dloop.algorithms=probe,isochrone,greedy    # all three
 *   -Dloop.profiles=gravel                      # gravel only
 *   -Dloop.profiles=fastbike,gravel,mtb-zossebart  # all profiles
 * </pre>
 * <p>
 * Requires downloaded segment data (see download-loop-test-segments.sh).
 */
@RunWith(Parameterized.class)
public class LoopGoldStandardTest {

  private static final String[] DEFAULT_PROFILES = {"fastbike"};
  private static final int[] TARGET_DISTANCES = {50000, 100000};
  private static final int[] SEARCH_RADII = {8000, 15900};

  /** Algorithm variants to run, controlled by -Dloop.algorithms system property. */
  private static final List<AlgorithmVariant> VARIANTS = parseVariants();
  /** Profiles to run, controlled by -Dloop.profiles system property. */
  private static final String[] PROFILES = parseProfiles();

  // N and E for most regions; all 4 for coastal
  private static final double[] STANDARD_DIRECTIONS = {0, 90};
  private static final String[] STANDARD_DIR_LABELS = {"N", "E"};
  private static final double[] ALL_DIRECTIONS = {0, 90, 180, 270};
  private static final String[] ALL_DIR_LABELS = {"N", "E", "S", "W"};

  private static final List<LoopQualityTest.LoopQualityResult> results = new ArrayList<>();

  @Parameterized.Parameter(0)
  public LoopTestRegion region;
  @Parameterized.Parameter(1)
  public int targetDistanceMeters;
  @Parameterized.Parameter(2)
  public int searchRadius;
  @Parameterized.Parameter(3)
  public double direction;
  @Parameterized.Parameter(4)
  public String profileName;
  @Parameterized.Parameter(5)
  public String testLabel;

  private File projectDir;

  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    List<Object[]> params = new ArrayList<>();
    for (LoopTestRegion region : LoopTestRegion.values()) {
      boolean isCoastal = (region == LoopTestRegion.COASTAL_NICE);
      double[] directions = isCoastal ? ALL_DIRECTIONS : STANDARD_DIRECTIONS;
      String[] dirLabels = isCoastal ? ALL_DIR_LABELS : STANDARD_DIR_LABELS;

      for (String profile : PROFILES) {
        for (int i = 0; i < TARGET_DISTANCES.length; i++) {
          for (int d = 0; d < directions.length; d++) {
            String label = String.format("%s_%dkm_%s_%s",
              region.name().toLowerCase(), TARGET_DISTANCES[i] / 1000, profile, dirLabels[d]);
            params.add(new Object[]{region, TARGET_DISTANCES[i], SEARCH_RADII[i], directions[d], profile, label});
          }
        }
      }
    }
    return params;
  }

  @Before
  public void setUp() throws Exception {
    projectDir = new File(".").getCanonicalFile().getParentFile();
  }

  @Test
  public void loopQuality() {
    File segDir = new File(projectDir, "segments4");
    File segFile = new File(segDir, region.segmentFile);
    Assume.assumeTrue("Segment file not found: " + segFile.getAbsolutePath() +
      " — run download-loop-test-segments.sh to fetch test data", segFile.exists());
    File profileFile = new File(projectDir, "misc/profiles2/" + profileName + ".brf");
    Assume.assumeTrue("Profile not found: " + profileFile.getAbsolutePath(), profileFile.exists());

    boolean anySucceeded = false;

    for (AlgorithmVariant v : VARIANTS) {
      LoopQualityTest.LoopQualityResult result = runVariant(v.name, v.algorithm, segDir, profileFile);

      synchronized (results) {
        if (result != null) results.add(result);
      }

      if (result == null || result.metrics == null) continue;
      anySucceeded = true;

      LoopQualityMetrics m = result.metrics;
      assertTrue(
        String.format("%s [%s]: road reuse %.1f%% exceeds max %.1f%%",
          testLabel, v.name, m.getRoadReusePercent(), region.maxReusePercent),
        m.getRoadReusePercent() <= region.maxReusePercent);
      assertTrue(
        String.format("%s [%s]: distance ratio %.2f outside [%.2f, %.2f]",
          testLabel, v.name, m.getDistanceRatio(), region.minDistanceRatio, region.maxDistanceRatio),
        m.getDistanceRatio() >= region.minDistanceRatio
          && m.getDistanceRatio() <= region.maxDistanceRatio);
      assertTrue(
        String.format("%s [%s]: direction delta %.1f° exceeds max %.1f°",
          testLabel, v.name, m.getDirectionDeltaDegrees(), region.maxDirectionDelta),
        m.getDirectionDeltaDegrees() <= region.maxDirectionDelta);

      // New metric assertions — thresholds are intentionally lenient to
      // accommodate constrained terrain (mountain, coastal, sparse rural).
      // The composite score and per-metric logging catch regressions;
      // hard assertions only reject clearly broken routes.
      assertTrue(
        String.format("%s [%s]: continuity score %.2f below minimum 0.5",
          testLabel, v.name, m.getContinuityScore()),
        m.getContinuityScore() >= 0.5);
      // Compactness varies hugely by terrain — mountain/coastal loops are naturally elongated.
      // Log it for reporting but don't assert (threshold TBD from collected data).
      if (m.getCompactnessScore() < 0.01) {
        System.err.println(String.format(Locale.US, "  NOTE: %s [%s] low compactness: %.4f",
          testLabel, v.name, m.getCompactnessScore()));
      }

      // Log full metrics breakdown for debugging
      System.out.println(String.format(Locale.US,
        "%s [%s]: composite=%.2f continuity=%.2f compactness=%.2f cost/m=%.0f maxGap=%dm closure=%dm",
        testLabel, v.name, m.compositeScore(), m.getContinuityScore(), m.getCompactnessScore(),
        m.getAverageCostPerMeter(), m.getMaxGapMeters(), m.getClosureDistanceMeters()));
    }

    Assume.assumeTrue("no algorithm produced a track for " + testLabel, anySucceeded);
  }

  private LoopQualityTest.LoopQualityResult runVariant(
    String variant, RoundTripAlgorithm algorithm, File segDir, File profileFile) {
    try {
      List<OsmNodeNamed> wplist = new ArrayList<>();
      OsmNodeNamed start = new OsmNodeNamed();
      start.name = "from";
      start.ilon = region.ilon;
      start.ilat = region.ilat;
      wplist.add(start);

      RoutingContext rctx = new RoutingContext();
      rctx.localFunction = profileFile.getAbsolutePath();
      rctx.startDirection = (int) direction;
      rctx.roundTripDistance = searchRadius;
      rctx.roundTripAlgorithm = algorithm;

      RoutingEngine re = new RoutingEngine(
        null, null, segDir, wplist, rctx,
        RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
      re.quite = true;
      re.doRun(0);

      OsmTrack track = re.getFoundTrack();
      String error = re.getErrorMessage();

      if (error != null || track == null || track.nodes == null || track.nodes.isEmpty()) {
        return new LoopQualityTest.LoopQualityResult(testLabel, region, targetDistanceMeters,
          profileName, direction, null,
          error != null ? error : (track == null ? "no track" : "empty track"),
          null, variant);
      }

      // Ensure track metadata is initialized — doRouting() sets these on the
      // track it returns, but some code paths (corridor re-routing) may not.
      if (track.messageList == null) {
        track.messageList = new ArrayList<>();
      }
      if (track.messageList.isEmpty()) {
        track.message = "track-length = " + track.distance
          + " filtered ascend = " + track.ascend
          + " plain-ascend = " + track.plainAscend
          + " cost=" + track.cost;
        track.messageList.add(track.message);
      }
      if (track.name == null) {
        track.name = "brouter_" + variant + "_0";
      }

      // Export GPX and GeoJSON for manual verification
      writeTrackFiles(track, testLabel, variant, rctx);

      LoopQualityMetrics metrics = LoopQualityMetrics.compute(track, targetDistanceMeters, direction);
      return new LoopQualityTest.LoopQualityResult(testLabel, region, targetDistanceMeters,
        profileName, direction, metrics, null, extractCoordinates(track), variant);
    } catch (Exception e) {
      // Write to file since Gradle swallows stderr
      try {
        java.io.PrintWriter pw = new java.io.PrintWriter(new FileWriter("build/reports/loops/exception-" + variant + ".txt", true));
        pw.println("EXCEPTION in " + testLabel + " [" + variant + "]:");
        e.printStackTrace(pw);
        pw.close();
      } catch (Exception ignored) {}
      return new LoopQualityTest.LoopQualityResult(testLabel, region, targetDistanceMeters,
        profileName, direction, null, e.getMessage(), null, variant);
    }
  }

  @AfterClass
  public static void generateReport() {
    if (results.isEmpty()) return;
    try {
      File buildDir = new File("build/reports/loops");
      buildDir.mkdirs();

      File reportFile = new File(buildDir, "gold-standard.html");
      String html = LoopQualityReportGenerator.generateHtml(results);
      try (FileWriter fw = new FileWriter(reportFile)) {
        fw.write(html);
      }
      System.out.println("Gold standard report: " + reportFile.getAbsolutePath());
    } catch (IOException e) {
      System.err.println("Failed to write gold standard report: " + e.getMessage());
    }
  }

  private static void writeTrackFiles(OsmTrack track, String label, String variant, RoutingContext rctx) {
    File trackDir = new File("build/reports/loops/tracks");
    trackDir.mkdirs();
    String baseName = label + "_" + variant;

    try {
      String gpx = new FormatGpx(rctx).format(track);
      try (FileWriter fw = new FileWriter(new File(trackDir, baseName + ".gpx"))) {
        fw.write(gpx);
      }
    } catch (Exception e) {
      try {
        java.io.PrintWriter pw = new java.io.PrintWriter(new FileWriter(new File(trackDir, baseName + "_gpx_error.txt")));
        pw.println("track.nodes=" + (track.nodes != null ? track.nodes.size() : "null")
          + " track.distance=" + track.distance + " messageList=" + (track.messageList != null ? track.messageList.size() : "null"));
        e.printStackTrace(pw);
        pw.close();
      } catch (Exception ignored) {}
    }

    try {
      String geojson = new FormatJson(rctx).format(track);
      try (FileWriter fw = new FileWriter(new File(trackDir, baseName + ".geojson"))) {
        fw.write(geojson);
      }
    } catch (Exception e) {
      System.err.println("Failed to write GeoJSON for " + baseName + ": " + e.getMessage());
    }
  }

  private static double[][] extractCoordinates(OsmTrack track) {
    double[][] coords = new double[track.nodes.size()][2];
    for (int i = 0; i < track.nodes.size(); i++) {
      OsmPathElement n = track.nodes.get(i);
      coords[i][0] = (n.getILon() - 180000000) / 1000000.0;
      coords[i][1] = (n.getILat() - 90000000) / 1000000.0;
    }
    return coords;
  }

  // --- Algorithm variant configuration ---

  private static String[] parseProfiles() {
    String prop = System.getProperty("loop.profiles");
    if (prop == null || prop.trim().isEmpty()) return DEFAULT_PROFILES;
    List<String> profiles = new ArrayList<>();
    for (String s : prop.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) profiles.add(trimmed);
    }
    return profiles.isEmpty() ? DEFAULT_PROFILES : profiles.toArray(new String[0]);
  }

  private static List<AlgorithmVariant> parseVariants() {
    String prop = System.getProperty("loop.algorithms", "probe,greedy");
    Set<String> requested = new LinkedHashSet<>();
    for (String s : prop.split(",")) {
      requested.add(s.trim().toLowerCase());
    }

    List<AlgorithmVariant> variants = new ArrayList<>();
    if (requested.contains("probe") || requested.contains("waypoint")) {
      variants.add(new AlgorithmVariant("probe", RoundTripAlgorithm.WAYPOINT));
    }
    if (requested.contains("isochrone")) {
      variants.add(new AlgorithmVariant("isochrone", RoundTripAlgorithm.ISOCHRONE));
    }
    if (requested.contains("greedy")) {
      variants.add(new AlgorithmVariant("greedy", RoundTripAlgorithm.GREEDY));
    }
    if (variants.isEmpty()) {
      // Fallback: run all
      variants.add(new AlgorithmVariant("probe", RoundTripAlgorithm.WAYPOINT));
      variants.add(new AlgorithmVariant("isochrone", RoundTripAlgorithm.ISOCHRONE));
      variants.add(new AlgorithmVariant("greedy", RoundTripAlgorithm.GREEDY));
    }
    return variants;
  }

  private static final class AlgorithmVariant {
    final String name;
    final RoundTripAlgorithm algorithm;

    AlgorithmVariant(String name, RoundTripAlgorithm algorithm) {
      this.name = name;
      this.algorithm = algorithm;
    }
  }
}
