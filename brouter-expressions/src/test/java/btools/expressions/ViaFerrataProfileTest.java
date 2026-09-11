package btools.expressions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ViaFerrataProfileTest {
  private static final File PROFILE_DIR = new File("../misc/profiles2");
  private static final File LOOKUPS = new File(PROFILE_DIR, "lookups.dat");
  private static final float FORBIDDEN = 100000f;
  private static final String[] HIGHWAYS = {"via_ferrata", "path", "footway", "steps"};

  @Test
  public void testDefaultExcludesAllRecognisableFerratas() {
    BExpressionContextWay context = context(Collections.<String, String>emptyMap());
    for (String highway : HIGHWAYS) {
      for (int grade = 0; grade <= 6; grade++) {
        checkAccess(context, false, "highway=" + highway, "via_ferrata_scale=" + grade);
      }
    }
    checkAccess(context, false, "highway=via_ferrata");
    checkAccess(context, true, "highway=path");
  }

  @Test
  public void testWholeGradeLimits() {
    for (int limit = -1; limit <= 6; limit++) {
      BExpressionContextWay context = context(parameters(limit));
      for (String highway : HIGHWAYS) {
        for (int grade = 0; grade <= 6; grade++) {
          checkAccess(context, grade <= limit, "highway=" + highway, "via_ferrata_scale=" + grade);
        }
      }
    }
  }

  @Test
  public void testGradeModifiers() {
    for (int limit = -1; limit <= 6; limit++) {
      BExpressionContextWay context = context(parameters(limit));
      for (String highway : HIGHWAYS) {
        for (int grade = 0; grade <= 6; grade++) {
          checkAccess(context, grade <= limit, "highway=" + highway, "via_ferrata_scale=" + grade + "-");
          checkAccess(context, grade < limit, "highway=" + highway, "via_ferrata_scale=" + grade + "+");
        }
      }
    }
  }

  @Test
  public void testMissingAndUnrecognisedGradesFailClosed() {
    for (int limit : new int[]{0, 2, 4, 6}) {
      BExpressionContextWay context = context(parameters(limit));
      checkAccess(context, false, "highway=via_ferrata");
      checkAccess(context, false, "highway=via_ferrata", "via_ferrata_scale=");
      for (String highway : HIGHWAYS) {
        for (String grade : new String[]{"unknown", "7", "99", "A", "B/C", "2;3", "2.5", "2++", "no"}) {
          checkAccess(context, false, "highway=" + highway, "via_ferrata_scale=" + grade);
        }
      }
    }
  }

  @Test
  public void testShortestWayCannotBypassLimit() {
    for (int limit : new int[]{-1, 0, 2, 4, 6}) {
      Map<String, String> parameters = parameters(limit);
      parameters.put("shortest_way", "1");
      BExpressionContextWay context = context(parameters);
      for (String highway : HIGHWAYS) {
        checkAccess(context, false, "highway=" + highway, "via_ferrata_scale=6+");
        checkAccess(context, limit >= 2, "highway=" + highway, "via_ferrata_scale=2");
        checkAccess(context, limit >= 4, "highway=" + highway, "via_ferrata_scale=4");
      }
      checkAccess(context, false, "highway=via_ferrata");
    }
  }

  @Test
  public void testElevationAndPreferencesCannotBypassLimit() {
    for (int shortest = 0; shortest <= 1; shortest++) {
      for (int elevation = 0; elevation <= 1; elevation++) {
        for (int wet = 0; wet <= 1; wet++) {
          Map<String, String> parameters = parameters(2);
          parameters.put("shortest_way", Integer.toString(shortest));
          parameters.put("consider_elevation", Integer.toString(elevation));
          parameters.put("iswet", Integer.toString(wet));
          parameters.put("Offroad_factor", "3");
          parameters.put("path_preference", "20");
          parameters.put("hiking_routes_preference", "2");
          parameters.put("SAC_scale_limit", "6");
          parameters.put("consider_forest", "1");
          parameters.put("consider_river", "1");
          parameters.put("consider_noise", "1");
          parameters.put("consider_town", "1");
          BExpressionContextWay context = context(parameters);
          for (String highway : HIGHWAYS) {
            for (String grade : new String[]{"2+", "3", "6", "unknown"}) {
              checkAccess(context, false, "highway=" + highway, "via_ferrata_scale=" + grade,
                  "sac_scale=hiking", "route_hiking_lwn=yes", "surface=grass");
            }
          }
          checkAccess(context, true, "highway=path", "via_ferrata_scale=2", "sac_scale=hiking");
        }
      }
    }
  }

  @Test
  public void testOrdinaryPathsAreUnaffectedByLimit() {
    String[][] ways = {
      {"highway=path"},
      {"highway=path", "sac_scale=mountain_hiking", "surface=ground"},
      {"highway=path", "sac_scale=demanding_mountain_hiking", "route_hiking_lwn=yes"},
      {"highway=path", "sac_scale=difficult_alpine_hiking"},
      {"highway=footway", "surface=paved"},
      {"highway=residential"},
      {"highway=steps"}
    };
    BExpressionContextWay reference = context(parameters(-1));
    for (int limit = 0; limit <= 6; limit++) {
      BExpressionContextWay context = context(parameters(limit));
      for (String[] way : ways) {
        checkSameCosts(evaluate(reference, false, way), evaluate(context, false, way), Arrays.toString(way));
      }
    }
  }

  @Test
  public void testAllowedGradeDoesNotOverrideExistingCosts() {
    BExpressionContextWay context = context(parameters(2));
    checkSameCosts(evaluate(context, false, "highway=path", "sac_scale=mountain_hiking"),
        evaluate(context, false, "highway=path", "sac_scale=mountain_hiking", "via_ferrata_scale=2"),
        "An allowed grade must not change the existing path cost");
  }

  @Test
  public void testAccessAndStepRestrictionsStillApply() {
    BExpressionContextWay context = context(parameters(6));
    for (String highway : HIGHWAYS) {
      checkAccess(context, false, "highway=" + highway, "via_ferrata_scale=2",
          "access=no", "foot=no", "bicycle=no");
    }
    Map<String, String> parameters = parameters(6);
    parameters.put("allow_steps", "0");
    checkAccess(context(parameters), false, "highway=steps", "via_ferrata_scale=2");
  }

  @Test
  public void testSacPreferencesRemainIndependent() {
    BExpressionContextWay context = context(parameters(2));
    float[] easy = evaluate(context, false, "highway=path", "via_ferrata_scale=2", "sac_scale=hiking");
    float[] hard = evaluate(context, false, "highway=path", "via_ferrata_scale=2", "sac_scale=difficult_alpine_hiking");
    for (int i = 0; i < easy.length; i++) {
      if (hard[i] <= easy[i]) {
        throw new AssertionError("Allowing a ferrata must not remove existing SAC penalties");
      }
    }
    checkAccess(context, false, "highway=path", "via_ferrata_scale=3", "sac_scale=hiking");
  }

  @Test
  public void testLookupRoundTripPreservesEveryGrade() {
    BExpressionContextWay context = context(parameters(6));
    for (int grade = 0; grade <= 6; grade++) {
      for (String modifier : new String[]{"", "-", "+"}) {
        String value = grade + modifier;
        byte[] encoded = encode(context, "highway=path", "via_ferrata_scale=" + value);
        String decoded = context.getKeyValueDescription(false, encoded);
        if (!Arrays.asList(decoded.split(" ")).contains("via_ferrata_scale=" + value)) {
          throw new AssertionError("Lost grade " + value + " in " + decoded);
        }
      }
    }
  }

  @Test
  public void testCachedEvaluationDistinguishesGrades() {
    BExpressionContextWay context = context(parameters(2));
    for (int iteration = 0; iteration < 3; iteration++) {
      checkAccess(context, true, "highway=path", "via_ferrata_scale=2");
      checkAccess(context, false, "highway=path", "via_ferrata_scale=3");
      checkAccess(context, false, "highway=path", "via_ferrata_scale=unknown");
      checkAccess(context, true, "highway=path");
    }
  }

  @Test
  public void testOlderDataHasNoRecoverableGrade() throws IOException {
    Path legacyLookup = legacyLookup();
    try {
      BExpressionContextWay oldContext = context(legacyLookup.toFile(), "shortest.brf",
          Collections.<String, String>emptyMap());
      BExpressionContextWay newContext = context(parameters(6));
      byte[] oldFerrata = encode(oldContext, "highway=via_ferrata");
      newContext.evaluate(false, oldFerrata);
      checkCosts(false, costs(newContext), "Old data: ungraded highway=via_ferrata");
      // An older data file cannot identify a path whose only ferrata tag was discarded.
      byte[] oldPath = encode(oldContext, "highway=path");
      newContext.evaluate(false, oldPath);
      checkCosts(true, costs(newContext), "Old data: plain path remains indistinguishable");
    } finally {
      Files.deleteIfExists(legacyLookup);
    }
  }

  @Test
  public void testLookupExtensionPreservesExistingEncoding() throws IOException {
    Path legacyLookup = legacyLookup();
    try {
      BExpressionContextWay oldContext = context(legacyLookup.toFile(), "shortest.brf",
          Collections.<String, String>emptyMap());
      BExpressionContextWay newContext = context(LOOKUPS, "shortest.brf",
          Collections.<String, String>emptyMap());
      for (String key : new String[]{"highway", "surface", "sac_scale", "route_hiking_lwn", "construction"}) {
        if (oldContext.getLookupNameIdx(key) != newContext.getLookupNameIdx(key)) {
          throw new AssertionError("Changed existing lookup index: " + key);
        }
      }
      if (newContext.getLookupNameIdx("via_ferrata_scale") != oldContext.createNewLookupData().length) {
        throw new AssertionError("The new way tag must be appended, not inserted");
      }
      String[] tags = {"highway=path", "surface=ground", "sac_scale=mountain_hiking",
        "route_hiking_lwn=yes", "construction=path"};
      byte[] oldEncoding = encode(oldContext, tags);
      byte[] newEncoding = encode(newContext, tags);
      if (!Arrays.equals(oldEncoding, newEncoding)) {
        throw new AssertionError("Changed the encoding of existing tags");
      }
      String oldDescription = oldContext.getKeyValueDescription(false, oldEncoding);
      String newDescription = newContext.getKeyValueDescription(false, oldEncoding);
      if (!oldDescription.equals(newDescription)) {
        throw new AssertionError("Old tags decoded differently with the extended lookup");
      }
    } finally {
      Files.deleteIfExists(legacyLookup);
    }
  }

  @Test
  public void testOldLookupIgnoresNewTrailingTag() throws IOException {
    Path legacyLookup = legacyLookup();
    try {
      BExpressionContextWay oldContext = context(legacyLookup.toFile(), "shortest.brf",
          Collections.<String, String>emptyMap());
      BExpressionContextWay newContext = context(LOOKUPS, "shortest.brf",
          Collections.<String, String>emptyMap());
      byte[] oldEncoding = encode(oldContext, "highway=path", "sac_scale=mountain_hiking");
      byte[] newEncoding = encode(newContext, "highway=path", "sac_scale=mountain_hiking", "via_ferrata_scale=2");
      String oldDescription = oldContext.getKeyValueDescription(false, oldEncoding);
      String newDescription = oldContext.getKeyValueDescription(false, newEncoding);
      if (!oldDescription.equals(newDescription)) {
        throw new AssertionError("An old reader must ignore the appended tag without changing existing tags");
      }
    } finally {
      Files.deleteIfExists(legacyLookup);
    }
  }

  private static Map<String, String> parameters(int limit) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put("via_ferrata_scale_limit", Integer.toString(limit));
    return parameters;
  }

  private static BExpressionContextWay context(Map<String, String> parameters) {
    return context(LOOKUPS, "hiking-mountain.brf", parameters);
  }

  private static BExpressionContextWay context(File lookups, String profile, Map<String, String> parameters) {
    BExpressionMetaData metadata = new BExpressionMetaData();
    BExpressionContextWay way = new BExpressionContextWay(metadata);
    BExpressionContextNode node = new BExpressionContextNode(metadata);
    metadata.readMetaData(lookups);
    node.setForeignContext(way);
    way.parseFile(new File(PROFILE_DIR, profile), "global", parameters);
    node.parseFile(new File(PROFILE_DIR, profile), "global", parameters);
    return way;
  }

  private static byte[] encode(BExpressionContextWay context, String... tags) {
    int[] data = context.createNewLookupData();
    for (String tag : tags) {
      int separator = tag.indexOf('=');
      context.addLookupValue(tag.substring(0, separator), tag.substring(separator + 1), data);
    }
    return context.encode(data);
  }

  private static float[] evaluate(BExpressionContextWay context, boolean reverse, String... tags) {
    context.evaluate(reverse, encode(context, tags));
    return costs(context);
  }

  private static float[] costs(BExpressionContextWay context) {
    return new float[]{context.getCostfactor(), context.getUphillCostfactor(), context.getDownhillCostfactor()};
  }

  private static void checkAccess(BExpressionContextWay context, boolean allowed, String... tags) {
    for (boolean reverse : new boolean[]{false, true}) {
      checkCosts(allowed, evaluate(context, reverse, tags), Arrays.toString(tags) + "; reverse=" + reverse);
    }
  }

  private static void checkCosts(boolean allowed, float[] costs, String description) {
    for (float cost : costs) {
      boolean matches = allowed ? cost >= 1f && cost < FORBIDDEN : cost >= FORBIDDEN;
      if (!Float.isFinite(cost) || !matches) {
        throw new AssertionError(description + ": expected " + (allowed ? "allowed" : "forbidden")
            + ", got " + Arrays.toString(costs));
      }
    }
  }

  private static void checkSameCosts(float[] expected, float[] actual, String description) {
    if (!Arrays.equals(expected, actual)) {
      throw new AssertionError(description + ": expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
    }
  }

  private static Path legacyLookup() throws IOException {
    StringBuilder contents = new StringBuilder();
    // Keep the way-table prefix before this extension, even if more tags are appended later.
    boolean skipNewWayTags = false;
    for (String line : Files.readAllLines(LOOKUPS.toPath(), StandardCharsets.UTF_8)) {
      if (line.startsWith("via_ferrata_scale;")) {
        skipNewWayTags = true;
      } else if (line.startsWith("---context:")) {
        skipNewWayTags = false;
      }
      if (!skipNewWayTags) {
        contents.append(line).append('\n');
      }
    }
    Path file = Files.createTempFile("brouter-before-via-ferrata-", ".dat");
    Files.write(file, contents.toString().getBytes(StandardCharsets.UTF_8));
    return file;
  }
}
