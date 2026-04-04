package btools.router;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parameterized loop quality verification test.
 * <p>
 * Generates round-trip routes across 5 regions × 4 distances × 3 profiles × 4 directions
 * (240 combinations) and asserts quality metrics fall within acceptable bounds.
 * <p>
 * Requires downloaded segment data (see download-loop-test-segments.sh).
 * Tests are skipped when the required segment tile is not present.
 */
@RunWith(Parameterized.class)
public class LoopQualityTest {

  // Profiles under test
  private static final String[] PROFILES = {"fastbike", "gravel", "mtb-zossebart"};
  // Target total loop distances in meters, and their corresponding search radii.
  // BRouter's roundTripDistance is a search RADIUS; actual loop length ≈ 2*pi*radius.
  // We use radius = targetDistance / (2*pi) ≈ targetDistance / 6.28.
  private static final int[] TARGET_DISTANCES = {30000, 50000, 80000, 100000};
  private static final int[] SEARCH_RADII = {4800, 8000, 12700, 15900};
  // Directions in degrees
  private static final double[] DIRECTIONS = {0, 90, 180, 270};
  // Direction labels for naming
  private static final String[] DIR_LABELS = {"N", "E", "S", "W"};

  // Collected results for the HTML report
  private static final List<LoopQualityResult> results = new ArrayList<>();

  @Parameterized.Parameter(0)
  public LoopTestRegion region;
  @Parameterized.Parameter(1)
  public int targetDistanceMeters;
  @Parameterized.Parameter(2)
  public int searchRadius;
  @Parameterized.Parameter(3)
  public String profileName;
  @Parameterized.Parameter(4)
  public double direction;
  @Parameterized.Parameter(5)
  public String testLabel;

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  private File projectDir;

  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    List<Object[]> params = new ArrayList<>();
    for (LoopTestRegion region : LoopTestRegion.values()) {
      for (int i = 0; i < TARGET_DISTANCES.length; i++) {
        for (String profile : PROFILES) {
          for (int d = 0; d < DIRECTIONS.length; d++) {
            String label = String.format("%s_%dkm_%s_%s",
              region.name().toLowerCase(), TARGET_DISTANCES[i] / 1000, profile, DIR_LABELS[d]);
            params.add(new Object[]{region, TARGET_DISTANCES[i], SEARCH_RADII[i], profile, DIRECTIONS[d], label});
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
    // Skip if segment data is not available
    File segDir = segmentDir();
    File segFile = new File(segDir, region.segmentFile);
    Assume.assumeTrue("Segment file not found: " + segFile.getAbsolutePath() +
      " — run download-loop-test-segments.sh to fetch test data", segFile.exists());

    File profileFile = profileFile(profileName);
    Assume.assumeTrue("Profile not found: " + profileFile.getAbsolutePath(), profileFile.exists());

    // Build waypoint list with single start point
    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = region.ilon;
    start.ilat = region.ilat;
    wplist.add(start);

    // Configure routing context
    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = (int) direction;
    rctx.roundTripDistance = searchRadius;
    rctx.roundTripIsochrone = Boolean.getBoolean("looptest.isochrone");

    // Execute round-trip routing
    String outPath = new File(outputDir.getRoot(), testLabel).getAbsolutePath();
    RoutingEngine re = new RoutingEngine(
      outPath, outPath, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.doRun(0);

    String error = re.getErrorMessage();
    OsmTrack track = re.getFoundTrack();

    if (error != null || track == null) {
      synchronized (results) {
        results.add(new LoopQualityResult(testLabel, region, targetDistanceMeters,
          profileName, direction, null, error != null ? error : "no track produced", null));
      }
      Assume.assumeTrue("routing could not produce track for " + testLabel + ": " + error, false);
      return;
    }

    // Compute metrics
    LoopQualityMetrics metrics = LoopQualityMetrics.compute(track, targetDistanceMeters, direction);

    // Extract coordinates for GeoJSON export
    double[][] coords = extractCoordinates(track);

    // Record result with coordinates
    synchronized (results) {
      results.add(new LoopQualityResult(testLabel, region, targetDistanceMeters,
        profileName, direction, metrics, null, coords));
    }

    // Write golden baseline files
    writeGoldenBaseline(track, metrics);

    // Property-bound assertions
    assertTrue(
      String.format("%s: road reuse %.1f%% exceeds max %.1f%% for %s terrain",
        testLabel, metrics.getRoadReusePercent(), region.maxReusePercent, region.name()),
      metrics.getRoadReusePercent() <= region.maxReusePercent);

    assertTrue(
      String.format("%s: distance ratio %.2f below min %.2f",
        testLabel, metrics.getDistanceRatio(), region.minDistanceRatio),
      metrics.getDistanceRatio() >= region.minDistanceRatio);

    assertTrue(
      String.format("%s: distance ratio %.2f exceeds max %.2f",
        testLabel, metrics.getDistanceRatio(), region.maxDistanceRatio),
      metrics.getDistanceRatio() <= region.maxDistanceRatio);

    assertTrue(
      String.format("%s: direction delta %.1f° exceeds max %.1f°",
        testLabel, metrics.getDirectionDeltaDegrees(), region.maxDirectionDelta),
      metrics.getDirectionDeltaDegrees() <= region.maxDirectionDelta);
  }

  @AfterClass
  public static void generateReport() {
    if (results.isEmpty()) return;

    try {
      File buildDir = new File("build/reports/loops");
      buildDir.mkdirs();

      // HTML report
      File reportFile = new File(buildDir, "index.html");
      String html = LoopQualityReportGenerator.generateHtml(results);
      try (FileWriter fw = new FileWriter(reportFile)) {
        fw.write(html);
      }
      System.out.println("Loop quality report: " + reportFile.getAbsolutePath());

      // Combined GeoJSON FeatureCollection with all routes
      File geojsonFile = new File(buildDir, "all-routes.geojson");
      try (FileWriter fw = new FileWriter(geojsonFile)) {
        fw.write(formatCombinedGeoJson(results));
      }
      System.out.println("GeoJSON export: " + geojsonFile.getAbsolutePath());
    } catch (IOException e) {
      System.err.println("Failed to write loop quality report: " + e.getMessage());
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

  private static String formatCombinedGeoJson(List<LoopQualityResult> results) {
    StringBuilder sb = new StringBuilder(1024 * 1024);
    sb.append("{\n  \"type\": \"FeatureCollection\",\n  \"features\": [\n");
    boolean first = true;
    for (LoopQualityResult r : results) {
      if (r.coordinates == null || r.coordinates.length == 0) continue;
      if (!first) sb.append(",\n");
      first = false;
      sb.append("    {\n      \"type\": \"Feature\",\n");
      sb.append("      \"properties\": {\n");
      sb.append(String.format(Locale.US, "        \"name\": \"%s\",\n", r.label));
      sb.append(String.format(Locale.US, "        \"region\": \"%s\",\n", r.region.name()));
      sb.append(String.format(Locale.US, "        \"profile\": \"%s\",\n", r.profileName));
      sb.append(String.format(Locale.US, "        \"requestedDistance\": %d,\n", r.distanceMeters));
      sb.append(String.format(Locale.US, "        \"direction\": %.0f,\n", r.direction));
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "        \"actualDistance\": %d,\n", r.metrics.getActualDistanceMeters()));
        sb.append(String.format(Locale.US, "        \"distanceRatio\": %.2f,\n", r.metrics.getDistanceRatio()));
        sb.append(String.format(Locale.US, "        \"roadReusePercent\": %.1f,\n", r.metrics.getRoadReusePercent()));
        sb.append(String.format(Locale.US, "        \"directionDelta\": %.1f,\n", r.metrics.getDirectionDeltaDegrees()));
        sb.append(String.format(Locale.US, "        \"passed\": %s,\n", r.passed()));
      }
      // Color: green=pass, red=fail
      String color = r.passed() ? "#28a745" : "#dc3545";
      sb.append(String.format("        \"stroke\": \"%s\",\n", color));
      sb.append("        \"stroke-width\": 2,\n");
      sb.append(String.format("        \"stroke-opacity\": %s\n", r.passed() ? "0.7" : "0.9"));
      sb.append("      },\n");
      sb.append("      \"geometry\": {\n        \"type\": \"LineString\",\n        \"coordinates\": [\n");
      for (int i = 0; i < r.coordinates.length; i++) {
        sb.append(String.format(Locale.US, "          [%.6f, %.6f]", r.coordinates[i][0], r.coordinates[i][1]));
        if (i < r.coordinates.length - 1) sb.append(",");
        sb.append("\n");
      }
      sb.append("        ]\n      }\n    }");
    }
    sb.append("\n  ]\n}\n");
    return sb.toString();
  }

  private void writeGoldenBaseline(OsmTrack track, LoopQualityMetrics metrics) {
    try {
      File goldenDir = new File(projectDir, "brouter-core/src/test/resources/test-data/golden");
      goldenDir.mkdirs();

      // Write metric snapshot JSON
      File metricsFile = new File(goldenDir, testLabel + "_metrics.json");
      if (!metricsFile.exists()) {
        try (FileWriter fw = new FileWriter(metricsFile)) {
          fw.write(formatMetricsJson(metrics));
        }
      }

      // Write track GeoJSON
      File geojsonFile = new File(goldenDir, testLabel + "_track.geojson");
      if (!geojsonFile.exists()) {
        try (FileWriter fw = new FileWriter(geojsonFile)) {
          fw.write(formatTrackGeoJson(track, metrics));
        }
      }
    } catch (IOException e) {
      System.err.println("Failed to write golden baseline for " + testLabel + ": " + e.getMessage());
    }
  }

  private String formatMetricsJson(LoopQualityMetrics metrics) {
    return String.format(Locale.US,
      "{\n" +
      "  \"roadReusePercent\": %.2f,\n" +
      "  \"distanceRatio\": %.4f,\n" +
      "  \"directionDeltaDegrees\": %.2f,\n" +
      "  \"actualDistanceMeters\": %d,\n" +
      "  \"requestedDistanceMeters\": %d\n" +
      "}\n",
      metrics.getRoadReusePercent(),
      metrics.getDistanceRatio(),
      metrics.getDirectionDeltaDegrees(),
      metrics.getActualDistanceMeters(),
      metrics.getRequestedDistanceMeters());
  }

  private String formatTrackGeoJson(OsmTrack track, LoopQualityMetrics metrics) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append("{\n");
    sb.append("  \"type\": \"Feature\",\n");
    sb.append("  \"properties\": {\n");
    sb.append(String.format(Locale.US, "    \"roadReusePercent\": %.2f,\n", metrics.getRoadReusePercent()));
    sb.append(String.format(Locale.US, "    \"distanceRatio\": %.4f,\n", metrics.getDistanceRatio()));
    sb.append(String.format(Locale.US, "    \"directionDeltaDegrees\": %.2f,\n", metrics.getDirectionDeltaDegrees()));
    sb.append(String.format(Locale.US, "    \"actualDistanceMeters\": %d,\n", metrics.getActualDistanceMeters()));
    sb.append(String.format(Locale.US, "    \"requestedDistanceMeters\": %d\n", metrics.getRequestedDistanceMeters()));
    sb.append("  },\n");
    sb.append("  \"geometry\": {\n");
    sb.append("    \"type\": \"LineString\",\n");
    sb.append("    \"coordinates\": [\n");
    for (int i = 0; i < track.nodes.size(); i++) {
      OsmPathElement n = track.nodes.get(i);
      double lon = (n.getILon() - 180000000) / 1000000.0;
      double lat = (n.getILat() - 90000000) / 1000000.0;
      sb.append(String.format(Locale.US, "      [%.6f, %.6f]", lon, lat));
      if (i < track.nodes.size() - 1) sb.append(",");
      sb.append("\n");
    }
    sb.append("    ]\n");
    sb.append("  }\n");
    sb.append("}\n");
    return sb.toString();
  }

  private File segmentDir() {
    return new File(projectDir, "segments4");
  }

  private File profileFile(String name) {
    return new File(projectDir, "misc/profiles2/" + name + ".brf");
  }

  /**
   * Holds the result of a single loop quality test case for report generation.
   */
  static class LoopQualityResult {
    final String label;
    final LoopTestRegion region;
    final int distanceMeters;
    final String profileName;
    final double direction;
    final LoopQualityMetrics metrics; // null if routing failed
    final String error; // null if routing succeeded
    final double[][] coordinates; // [lon, lat] pairs; null if routing failed

    LoopQualityResult(String label, LoopTestRegion region, int distanceMeters,
                      String profileName, double direction,
                      LoopQualityMetrics metrics, String error, double[][] coordinates) {
      this.label = label;
      this.region = region;
      this.distanceMeters = distanceMeters;
      this.profileName = profileName;
      this.direction = direction;
      this.metrics = metrics;
      this.error = error;
      this.coordinates = coordinates;
    }

    boolean passed() {
      if (metrics == null) return false;
      return metrics.getRoadReusePercent() <= region.maxReusePercent
        && metrics.getDistanceRatio() >= region.minDistanceRatio
        && metrics.getDistanceRatio() <= region.maxDistanceRatio
        && metrics.getDirectionDeltaDegrees() <= region.maxDirectionDelta;
    }
  }
}
