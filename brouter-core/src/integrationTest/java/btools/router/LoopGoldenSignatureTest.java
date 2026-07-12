package btools.router;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.fail;

/**
 * Byte-identical route regression harness for the GREEDY and ISO_GREEDY
 * round-trip loop generators.
 * <p>
 * This is the "golden output" gate from the performance-optimization plan
 * (docs/features/greedy-loop-perf-optimizations.md §5). It runs a fixed matrix
 * of loop-generation scenarios and reduces each produced route to a
 * deterministic <em>signature</em> — the ordered node sequence (hashed),
 * node count, distance, ascend, cost and error string. The signature is the
 * user-facing deliverable: if any optimization changes which candidate wins at
 * any step, the merged node sequence changes and the hash diverges.
 * <p>
 * Every result-preserving optimization MUST keep this green. It is the
 * mechanical proof that a "performance only" change did not alter output.
 *
 * <h3>Running</h3>
 * <pre>
 *   # verify (default action): assert signatures match the committed golden
 *   ./gradlew :brouter-core:test --tests '*LoopGoldenSignatureTest' -Dgolden.tests=true
 *
 *   # (re)capture the golden baseline — only on KNOWN-GOOD code
 *   ./gradlew :brouter-core:test --tests '*LoopGoldenSignatureTest' \
 *       -Dgolden.tests=true -Dgolden.write=true
 * </pre>
 * The suite is opt-in ({@code -Dgolden.tests=true}) because it routes real
 * loops and needs the {@code segments4} tiles present on disk; it
 * {@link Assume}-skips when the property is unset or tiles are missing, so the
 * standard build is unaffected.
 */
public class LoopGoldenSignatureTest {

  // Fail a pathological/non-terminating routing case fast instead of hanging
  // the whole suite. Normal cases finish in seconds-to-low-minutes; 5 min is a
  // generous ceiling. withLookingForStuckThread dumps the stuck stack on timeout.
  @Rule
  public org.junit.rules.Timeout perTestTimeout = org.junit.rules.Timeout.builder()
    .withTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
    .withLookingForStuckThread(true)
    .build();

  /** Golden file, relative to the module dir (cwd during the test = brouter-core). */
  private static final String GOLDEN_PATH =
    "src/test/resources/test-data/golden/loop-signatures.txt";

  /**
   * Fixed scenario matrix. Kept small and biased toward fast, well-covered
   * regions so every run completes comfortably under the planner deadline —
   * a scenario that flirted with the timeout would be non-deterministic and
   * unusable as a golden. Covers both algorithms, a paved (fastbike) and a
   * non-paved (gravel) profile, and two start directions so the
   * self-intersection / hostility / visited-edge code paths are all exercised.
   */
  private static final Scenario[] SCENARIOS = {
    new Scenario(LoopTestRegion.DREIEICH, 8000, 0, "fastbike", RoundTripAlgorithm.GREEDY),
    new Scenario(LoopTestRegion.DREIEICH, 8000, 0, "fastbike", RoundTripAlgorithm.ISO_GREEDY),
    new Scenario(LoopTestRegion.DREIEICH, 8000, 90, "fastbike", RoundTripAlgorithm.GREEDY),
    new Scenario(LoopTestRegion.DREIEICH, 8000, 90, "fastbike", RoundTripAlgorithm.ISO_GREEDY),
    new Scenario(LoopTestRegion.URBAN_BERLIN, 8000, 0, "fastbike", RoundTripAlgorithm.GREEDY),
    new Scenario(LoopTestRegion.URBAN_BERLIN, 8000, 0, "fastbike", RoundTripAlgorithm.ISO_GREEDY),
    new Scenario(LoopTestRegion.RURAL_LOZERE, 8000, 0, "gravel", RoundTripAlgorithm.GREEDY),
    new Scenario(LoopTestRegion.RURAL_LOZERE, 8000, 0, "gravel", RoundTripAlgorithm.ISO_GREEDY),
  };

  @Test
  public void signaturesMatchGolden() throws Exception {

    File moduleDir = new File(".").getCanonicalFile();
    File projectDir = moduleDir.getParentFile();
    File segDir = new File(projectDir, "segments4");
    Assume.assumeTrue("segments4 dir missing: " + segDir.getAbsolutePath(), segDir.isDirectory());

    Map<String, String> actual = new TreeMap<>();
    for (Scenario s : SCENARIOS) {
      File tile = new File(segDir, s.region.segmentFile);
      File profileFile = new File(projectDir, "misc/profiles2/" + s.profile + ".brf");
      if (!tile.exists() || !profileFile.exists()) {
        continue; // skip scenarios whose data is not present
      }
      actual.put(s.key(), computeSignature(s, segDir, profileFile));
    }
    Assume.assumeFalse("no scenario data available — nothing to compare", actual.isEmpty());

    File goldenFile = new File(moduleDir, GOLDEN_PATH);

    if (Boolean.getBoolean("golden.write")) {
      goldenFile.getParentFile().mkdirs();
      StringBuilder sb = new StringBuilder();
      sb.append("# Golden route signatures for GREEDY / ISO_GREEDY loop generation.\n");
      sb.append("# Regenerate with -Dgolden.tests=true -Dgolden.write=true on known-good code.\n");
      for (Map.Entry<String, String> e : actual.entrySet()) {
        sb.append(e.getKey()).append(" => ").append(e.getValue()).append('\n');
      }
      Files.write(goldenFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
      System.out.println("Wrote golden signatures (" + actual.size() + " scenarios): "
        + goldenFile.getAbsolutePath());
      return;
    }

    if (!goldenFile.exists()) {
      fail("Golden file missing: " + goldenFile.getAbsolutePath()
        + " — capture it first with -Dgolden.write=true");
    }
    Map<String, String> golden = readGolden(goldenFile);

    List<String> diffs = new ArrayList<>();
    for (Map.Entry<String, String> e : actual.entrySet()) {
      String want = golden.get(e.getKey());
      if (want == null) {
        diffs.add("  NEW scenario not in golden: " + e.getKey() + " => " + e.getValue());
      } else if (!want.equals(e.getValue())) {
        diffs.add("  CHANGED " + e.getKey() + "\n      golden: " + want + "\n      actual: " + e.getValue());
      }
    }
    for (String key : golden.keySet()) {
      if (!actual.containsKey(key)) {
        diffs.add("  MISSING scenario (in golden, not produced now): " + key);
      }
    }
    if (!diffs.isEmpty()) {
      fail("Route signatures diverged from golden — an optimization changed output:\n"
        + String.join("\n", diffs)
        + "\n\nIf this change is intentionally output-altering, it is NOT a pure performance"
        + " optimization. If you are certain it is correct, recapture with -Dgolden.write=true.");
    }
  }

  private String computeSignature(Scenario s, File segDir, File profileFile) {
    try {
      List<OsmNodeNamed> wplist = new ArrayList<>();
      OsmNodeNamed start = new OsmNodeNamed();
      start.name = "from";
      start.ilon = s.region.ilon;
      start.ilat = s.region.ilat;
      wplist.add(start);

      RoutingContext rctx = new RoutingContext();
      rctx.localFunction = profileFile.getAbsolutePath();
      rctx.startDirection = s.direction;
      rctx.roundTripDistance = s.searchRadius;
      rctx.roundTripAlgorithm = s.algorithm;
      // Sign only gate-accepted clean loops, matching the sibling quality
      // matrices (LoopGoldStandardTest / LoopQualityTest / RoundTripWeakCell…).
      // Without this the lenient default would adopt QUALITY-failed best-effort
      // loops (err=null), freezing a degraded loop into the golden instead of
      // surfacing it as a regression (NO_TRACK).
      rctx.roundTripStrictQuality = true;

      RoutingEngine re = new RoutingEngine(
        null, null, segDir, wplist, rctx,
        RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
      re.quite = true;
      re.doRun(0);

      OsmTrack track = re.getFoundTrack();
      String error = re.getErrorMessage();
      if (track == null || track.nodes == null || track.nodes.isEmpty()) {
        return "NO_TRACK err=" + (error == null ? "null" : error);
      }
      // FNV-1a over the ordered (ilon,ilat) sequence — any reorder or
      // single-node change flips the hash.
      long h = 0xcbf29ce484222325L;
      for (OsmPathElement n : track.nodes) {
        h = fnv(h, n.getILon());
        h = fnv(h, n.getILat());
      }
      return String.format(
        "nodes=%d hash=%016x dist=%d ascend=%d cost=%d err=%s",
        track.nodes.size(), h, track.distance, track.ascend, track.cost,
        error == null ? "null" : error);
    } catch (Exception e) {
      return "EXCEPTION " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  private static long fnv(long h, int v) {
    for (int shift = 24; shift >= 0; shift -= 8) {
      h ^= (v >>> shift) & 0xff;
      h *= 0x100000001b3L;
    }
    return h;
  }

  private Map<String, String> readGolden(File f) throws Exception {
    Map<String, String> m = new LinkedHashMap<>();
    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
      String t = line.trim();
      if (t.isEmpty() || t.startsWith("#")) continue;
      int sep = t.indexOf(" => ");
      if (sep < 0) continue;
      m.put(t.substring(0, sep), t.substring(sep + 4));
    }
    return m;
  }

  private static final class Scenario {
    final LoopTestRegion region;
    final int searchRadius;
    final int direction;
    final String profile;
    final RoundTripAlgorithm algorithm;

    Scenario(LoopTestRegion region, int searchRadius, int direction,
             String profile, RoundTripAlgorithm algorithm) {
      this.region = region;
      this.searchRadius = searchRadius;
      this.direction = direction;
      this.profile = profile;
      this.algorithm = algorithm;
    }

    String key() {
      return String.format("%s|%s|r%d|dir%d|%s",
        algorithm.name().toLowerCase(), region.name().toLowerCase(),
        searchRadius, direction, profile);
    }
  }
}
