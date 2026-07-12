package btools.router;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistence + report generation for the loop-quality matrix, decoupled from
 * the test forks.
 *
 * <p><b>Producer side</b> (called from {@code LoopQualityTestBase} in each test
 * fork): {@link #persist} writes one JSON file per result. It is <em>write-only
 * and append-only</em> — it never clears the cache. That removes the cross-fork
 * race the old in-test {@code ensureCacheCleared()} had (a {@code synchronized}
 * method only serializes one JVM), and it keeps the per-fork heap small because
 * a fork never holds the full corpus.
 *
 * <p><b>Consumer side</b> ({@link #main}, run as the {@code generateLoopReport}
 * Gradle task in its own single JVM <em>after</em> {@code integrationTest}):
 * reads every persisted result back and renders the HTML/GeoJSON report with
 * its own (large) heap. The Gradle task owns clear-at-start (a {@code doFirst}
 * that deletes {@link #RESULTS_CACHE_DIR}) and read-at-end, so the lifecycle is
 * explicit and fork-safe.
 *
 * <p><b>Placement-path summary intentionally dropped.</b> The old in-test
 * {@code @AfterClass} printed a {@code RoutingEngine.placementPathCounts()}
 * distribution. Those are <em>process-wide static side-effect counters</em>
 * incremented during routing; they are not persisted per result, so a separate
 * report JVM (which routes nothing) would only ever read zero, and per-fork they
 * are partial. Rather than read a misleading zero, the summary is dropped with a
 * logged note. This is a conscious choice — the prior measurement found the
 * placement work moot (ENVELOPE_ISO_FALLBACK=0, CIRCLE=0). If it is ever needed
 * again, add a {@code placementPath} field to {@link LoopQualityResult} + its
 * JSON and aggregate it here.
 */
final class LoopQualityReport {

  private LoopQualityReport() {
  }

  /**
   * On-disk cache of every {@link LoopQualityResult} produced by any test in any
   * fork. One file per result; filename is deterministic from the test
   * parameters so re-running a single case overwrites cleanly. The cache is
   * cleared once at suite start by the {@code integrationTest} task's
   * {@code doFirst}, not by the tests themselves.
   */
  static final File RESULTS_CACHE_DIR = new File("build/reports/loops/.results");

  /** Output directory for the rendered report artifacts. */
  static final File REPORT_DIR = new File("build/reports/loops");

  // ---- Producer: write-only persistence ----------------------------------

  /**
   * Append one result to the on-disk cache. Filename is deterministic from the
   * test parameters so re-running a single case overwrites the previous file
   * instead of accumulating duplicates. Write-only: never clears the cache (the
   * Gradle task owns clearing), so concurrent forks cannot race here.
   */
  static void persist(LoopQualityResult r) {
    if (r == null) return;
    if (!RESULTS_CACHE_DIR.exists()) {
      RESULTS_CACHE_DIR.mkdirs();
    }
    String fn = String.format(Locale.US, "%s__%dkm__%s__%03.0f__%s.json",
      r.region.name().toLowerCase(), r.distanceMeters / 1000,
      r.profileName, r.direction, r.variant);
    File f = new File(RESULTS_CACHE_DIR, fn);
    try (FileWriter fw = new FileWriter(f)) {
      fw.write(serializeResult(r));
    } catch (IOException e) {
      System.err.println("Failed to persist loop quality result " + fn + ": " + e.getMessage());
    }
  }

  // ---- Consumer: report generation ---------------------------------------

  /**
   * Entry point for the {@code generateLoopReport} Gradle task. Reads the
   * persisted corpus and renders the report. Tolerates an empty cache (e.g. the
   * suite produced nothing) by logging and exiting 0 — the report is an
   * artifact, never a build gate.
   */
  public static void main(String[] args) {
    List<LoopQualityResult> all = loadCachedResults();
    if (all.isEmpty()) {
      System.out.println("LoopQualityReport: no cached results in "
        + RESULTS_CACHE_DIR.getAbsolutePath() + " — nothing to render.");
      return;
    }
    generate(all);
  }

  static void generate(List<LoopQualityResult> allResults) {
    printSummary(allResults);

    // Placement-path summary intentionally unavailable here — see class javadoc.
    System.out.println("PLACEMENT-PATH COUNTS: unavailable under parallel forks "
      + "(routing-time process-wide counters are not persisted per result; dropped deliberately).");

    try {
      REPORT_DIR.mkdirs();

      // HTML report
      File reportFile = new File(REPORT_DIR, "index.html");
      String html = LoopQualityReportGenerator.generateHtml(allResults);
      try (FileWriter fw = new FileWriter(reportFile)) {
        fw.write(html);
      }
      System.out.println("Loop quality report: " + reportFile.getAbsolutePath()
        + " (" + allResults.size() + " entries from disk cache)");

      // Combined GeoJSON FeatureCollection with all routes
      File geojsonFile = new File(REPORT_DIR, "all-routes.geojson");
      try (FileWriter fw = new FileWriter(geojsonFile)) {
        fw.write(formatCombinedGeoJson(allResults));
      }
      System.out.println("GeoJSON export: " + geojsonFile.getAbsolutePath());

      // Per-region HTML with full geometry and variant comparison
      for (LoopTestRegion region : LoopTestRegion.values()) {
        List<LoopQualityResult> regionResults = new ArrayList<>();
        for (LoopQualityResult r : allResults) {
          if (r.region == region) regionResults.add(r);
        }
        if (regionResults.isEmpty()) continue;

        File regionGeoJson = new File(REPORT_DIR, "routes-" + region.name().toLowerCase() + ".geojson");
        try (FileWriter fw = new FileWriter(regionGeoJson)) {
          fw.write(formatCombinedGeoJson(regionResults));
        }

        File regionHtml = new File(REPORT_DIR, region.name().toLowerCase() + ".html");
        try (FileWriter fw = new FileWriter(regionHtml)) {
          fw.write(LoopQualityReportGenerator.generateRegionHtml(region, regionResults));
        }
        System.out.println("Region report: " + regionHtml.getAbsolutePath());
      }
    } catch (IOException e) {
      System.err.println("Failed to write loop quality report: " + e.getMessage());
    }
  }

  private static void printSummary(List<LoopQualityResult> all) {
    java.util.Map<String, int[]> byVariant = new java.util.LinkedHashMap<>();
    for (LoopQualityResult r : all) {
      int[] counts = byVariant.computeIfAbsent(r.variant, k -> new int[2]); // [ok, error]
      if (r.metrics == null) counts[1]++; else counts[0]++;
    }

    System.out.println();
    System.out.println("=== LoopQualityTest variant summary ===");
    for (java.util.Map.Entry<String, int[]> e : byVariant.entrySet()) {
      int ok = e.getValue()[0];
      int err = e.getValue()[1];
      System.out.println(String.format(Locale.US, "  %-10s  ok=%d  error=%d  (total=%d)",
        e.getKey(), ok, err, ok + err));
    }

    // Errors by region/profile
    java.util.Map<String, Integer> errorsByCell = new java.util.TreeMap<>();
    for (LoopQualityResult r : all) {
      if (r.metrics != null) continue;
      String cell = r.region.name() + "/" + r.profileName;
      errorsByCell.merge(cell, 1, Integer::sum);
    }
    if (!errorsByCell.isEmpty()) {
      System.out.println("=== Errors by region/profile ===");
      for (java.util.Map.Entry<String, Integer> e : errorsByCell.entrySet()) {
        System.out.println(String.format(Locale.US, "  %-30s  %d", e.getKey(), e.getValue()));
      }
    }
    System.out.println();
  }

  /**
   * Read every cached {@link LoopQualityResult} back from disk — the full corpus
   * across all forks.
   */
  static List<LoopQualityResult> loadCachedResults() {
    List<LoopQualityResult> loaded = new ArrayList<>();
    if (!RESULTS_CACHE_DIR.exists()) return loaded;
    File[] files = RESULTS_CACHE_DIR.listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return loaded;
    for (File f : files) {
      try {
        LoopQualityResult r = deserializeResult(f);
        if (r != null) loaded.add(r);
      } catch (IOException | RuntimeException e) {
        System.err.println("Failed to read cached result " + f.getName() + ": " + e.getMessage());
      }
    }
    return loaded;
  }

  // ---- JSON (de)serialisation --------------------------------------------

  /**
   * Compact ad-hoc JSON serialisation. We don't pull in a JSON library for a
   * test-only artifact — the field set is fixed and small, and we control both
   * ends of the wire. Strings are not escaped because they only contain
   * lowercase ASCII and underscores by construction.
   */
  static String serializeResult(LoopQualityResult r) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append("{");
    sb.append("\"label\":\"").append(r.label).append("\",");
    sb.append("\"region\":\"").append(r.region.name()).append("\",");
    sb.append(String.format(Locale.US, "\"distanceMeters\":%d,", r.distanceMeters));
    sb.append("\"profileName\":\"").append(r.profileName).append("\",");
    sb.append(String.format(Locale.US, "\"direction\":%f,", r.direction));
    sb.append("\"variant\":\"").append(r.variant).append("\",");
    if (r.error != null) {
      sb.append("\"error\":\"").append(r.error.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",");
    }
    if (r.metrics != null) {
      LoopQualityMetrics m = r.metrics;
      sb.append("\"metrics\":{");
      sb.append(String.format(Locale.US, "\"reuse\":%.4f,\"distR\":%.4f,\"dirD\":%.4f,",
        m.getRoadReusePercent(), m.getDistanceRatio(), m.getDirectionDeltaDegrees()));
      sb.append(String.format(Locale.US, "\"actual\":%d,\"requested\":%d,\"cont\":%.4f,",
        m.getActualDistanceMeters(), m.getRequestedDistanceMeters(), m.getContinuityScore()));
      sb.append(String.format(Locale.US, "\"maxGap\":%d,\"totGap\":%d,\"compact\":%.4f,",
        m.getMaxGapMeters(), m.getTotalGapMeters(), m.getCompactnessScore()));
      sb.append(String.format(Locale.US, "\"costM\":%.4f,\"closure\":%d,",
        m.getAverageCostPerMeter(), m.getClosureDistanceMeters()));
      sb.append(String.format(Locale.US, "\"spur\":%d,\"worstSpur\":%d,",
        m.getSpurCount(), m.getWorstSpurMeters()));
      sb.append(String.format(Locale.US, "\"crossX\":%d,\"loopX\":%d",
        m.getSelfIntersections(), m.getSmallLoopCrossings()));
      sb.append("},");
    }
    if (r.coordinates != null) {
      sb.append("\"coordinates\":[");
      for (int i = 0; i < r.coordinates.length; i++) {
        if (i > 0) sb.append(",");
        sb.append(String.format(Locale.US, "[%.6f,%.6f]", r.coordinates[i][0], r.coordinates[i][1]));
      }
      sb.append("]");
    } else {
      sb.append("\"coordinates\":null");
    }
    sb.append("}");
    return sb.toString();
  }

  /**
   * Inverse of {@link #serializeResult}. Uses primitive scanning rather than a
   * JSON parser because the format is fixed. Returns null on malformed input.
   */
  static LoopQualityResult deserializeResult(File f) throws IOException {
    StringBuilder content = new StringBuilder((int) f.length());
    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
      String line;
      while ((line = br.readLine()) != null) content.append(line);
    }
    String json = content.toString();
    String label = strField(json, "label");
    String regionName = strField(json, "region");
    String profileName = strField(json, "profileName");
    String variant = strField(json, "variant");
    if (label == null || regionName == null || profileName == null || variant == null) return null;
    LoopTestRegion region = LoopTestRegion.valueOf(regionName);
    int distanceMeters = (int) Math.round(numField(json, "distanceMeters"));
    double direction = numField(json, "direction");
    String error = strField(json, "error");
    LoopQualityMetrics metrics = parseMetricsBlock(json);
    double[][] coords = parseCoords(json);
    return new LoopQualityResult(label, region, distanceMeters, profileName, direction,
      metrics, error, coords, variant);
  }

  private static String strField(String json, String key) {
    String needle = "\"" + key + "\":\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int start = i + needle.length();
    StringBuilder out = new StringBuilder();
    for (int j = start; j < json.length(); j++) {
      char c = json.charAt(j);
      if (c == '\\' && j + 1 < json.length()) {
        out.append(json.charAt(++j));
        continue;
      }
      if (c == '"') return out.toString();
      out.append(c);
    }
    return null;
  }

  private static double numField(String json, String key) {
    String needle = "\"" + key + "\":";
    int i = json.indexOf(needle);
    if (i < 0) return 0;
    int start = i + needle.length();
    int end = start;
    while (end < json.length()) {
      char c = json.charAt(end);
      if (c == ',' || c == '}' || c == ']') break;
      end++;
    }
    try {
      return Double.parseDouble(json.substring(start, end));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static LoopQualityMetrics parseMetricsBlock(String json) {
    int i = json.indexOf("\"metrics\":{");
    if (i < 0) return null;
    int start = i + "\"metrics\":{".length();
    int end = json.indexOf('}', start);
    if (end < 0) return null;
    String block = json.substring(start, end);
    double reuse = numField(block, "reuse");
    double distR = numField(block, "distR");
    double dirD = numField(block, "dirD");
    int actual = (int) Math.round(numField(block, "actual"));
    int requested = (int) Math.round(numField(block, "requested"));
    double cont = numField(block, "cont");
    int maxGap = (int) Math.round(numField(block, "maxGap"));
    int totGap = (int) Math.round(numField(block, "totGap"));
    double compact = numField(block, "compact");
    double costM = numField(block, "costM");
    int closure = (int) Math.round(numField(block, "closure"));
    int spur = (int) Math.round(numField(block, "spur"));
    int worstSpur = (int) Math.round(numField(block, "worstSpur"));
    int crossX = (int) Math.round(numField(block, "crossX"));
    int loopX = (int) Math.round(numField(block, "loopX"));
    return LoopQualityMetrics.fromFields(reuse, distR, dirD, actual, requested,
      cont, maxGap, totGap, compact, costM, closure, spur, worstSpur, crossX, loopX);
  }

  private static double[][] parseCoords(String json) {
    // Locate the OUTER coordinates array. The value can be null (no track) or a
    // list of [lon, lat] pairs; nested brackets mean a naive indexOf(']') finds
    // the wrong closer, so we walk and count bracket depth.
    int keyIdx = json.indexOf("\"coordinates\":");
    if (keyIdx < 0) return null;
    int afterKey = keyIdx + "\"coordinates\":".length();
    while (afterKey < json.length() && Character.isWhitespace(json.charAt(afterKey))) afterKey++;
    if (afterKey >= json.length()) return null;
    if (json.startsWith("null", afterKey)) return null;
    if (json.charAt(afterKey) != '[') return null;
    int start = afterKey + 1;
    int depth = 1;
    int end = -1;
    for (int j = start; j < json.length(); j++) {
      char c = json.charAt(j);
      if (c == '[') depth++;
      else if (c == ']') {
        depth--;
        if (depth == 0) { end = j; break; }
      }
    }
    if (end < 0) return null;
    String block = json.substring(start, end);
    if (block.isEmpty()) return new double[0][];
    List<double[]> pairs = new ArrayList<>();
    int idx = 0;
    while (idx < block.length()) {
      int lb = block.indexOf('[', idx);
      if (lb < 0) break;
      int rb = block.indexOf(']', lb);
      if (rb < 0) break;
      String pair = block.substring(lb + 1, rb);
      int comma = pair.indexOf(',');
      if (comma < 0) { idx = rb + 1; continue; }
      try {
        double lon = Double.parseDouble(pair.substring(0, comma).trim());
        double lat = Double.parseDouble(pair.substring(comma + 1).trim());
        pairs.add(new double[]{lon, lat});
      } catch (NumberFormatException e) {
        // skip malformed pair
      }
      idx = rb + 1;
    }
    return pairs.toArray(new double[0][]);
  }

  // ---- GeoJSON ------------------------------------------------------------

  private static String variantColor(String variant) {
    switch (variant) {
      case "isochrone": return "#e67300";
      case "greedy": return "#22aa44";
      case "iso_greedy": return "#aa22cc";
      default: return "#0066cc";
    }
  }

  static String formatCombinedGeoJson(List<LoopQualityResult> results) {
    StringBuilder sb = new StringBuilder(1024 * 1024);
    sb.append("{\n  \"type\": \"FeatureCollection\",\n  \"features\": [\n");
    boolean first = true;
    for (LoopQualityResult r : results) {
      if (r.coordinates == null || r.coordinates.length == 0) continue;
      if (!first) sb.append(",\n");
      first = false;
      sb.append("    {\n      \"type\": \"Feature\",\n");
      sb.append("      \"properties\": {\n");
      sb.append(String.format(Locale.US, "        \"name\": \"%s [%s]\",\n", r.label, r.variant));
      sb.append(String.format(Locale.US, "        \"variant\": \"%s\",\n", r.variant));
      sb.append(String.format(Locale.US, "        \"region\": \"%s\",\n", r.region.name()));
      sb.append(String.format(Locale.US, "        \"profile\": \"%s\",\n", r.profileName));
      sb.append(String.format(Locale.US, "        \"requestedDistance\": %d,\n", r.distanceMeters));
      sb.append(String.format(Locale.US, "        \"direction\": %.0f,\n", r.direction));
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "        \"actualDistance\": %d,\n", r.metrics.getActualDistanceMeters()));
        sb.append(String.format(Locale.US, "        \"distanceRatio\": %.2f,\n", r.metrics.getDistanceRatio()));
        sb.append(String.format(Locale.US, "        \"roadReusePercent\": %.1f,\n", r.metrics.getRoadReusePercent()));
        sb.append(String.format(Locale.US, "        \"directionDelta\": %.1f,\n", r.metrics.getDirectionDeltaDegrees()));
      }
      sb.append(String.format("        \"stroke\": \"%s\",\n", variantColor(r.variant)));
      sb.append("        \"stroke-width\": 2,\n");
      sb.append("        \"stroke-opacity\": 0.8\n");
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
}
