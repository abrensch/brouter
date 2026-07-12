package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import org.junit.Test;

/**
 * Verifies the name-independent paved/road-bike classification in
 * {@link RoundTripQualityGate}. The classifier probes what a profile's cost
 * model actually charges for an unpaved way rather than matching the profile
 * name, so it stays correct for profiles the old name heuristic missed.
 *
 * <p>Classification is the ratio cf(grade3 gravel track)/cf(paved residential):
 * a profile is paved-only when it treats loose unpaved surface as off-limits.
 * Calibration over the stock profiles, recorded so the threshold is traceable:
 * <pre>
 *   profile                   g3-ratio   verdict
 *   fastbike                      8.33   paved
 *   fastbike-verylowtraffic       8.33   paved
 *   skating                      ~9523   paved (rollerblades cannot ride any track)
 *   vm-forum-velomobil-schnell   23.53   paved (faired road vehicle cannot ride gravel)
 *   trekking                      2.65   NOT paved (tolerates loose surface)
 *   gravel                        0.79   NOT paved
 *   mtb                           0.57   NOT paved (penalises paved even harder)
 *   shortest                      1.00   NOT paved
 * </pre>
 * Threshold {@link RoundTripQualityGate#PAVED_PROBE_RATIO} = 5.0 separates every
 * cannot-ride-unpaved vehicle (>= 8.33) from the unpaved-tolerant bikes (<= 2.65).
 * grade1 is deliberately NOT used: skating/moped/car penalise even grade1 tracks,
 * so "rides grade1 cheaply" is not a universal paved-only trait.
 */
public class PavedProfileProbeTest {

  private static File profileDir() {
    File d = new File("misc/profiles2");
    if (!d.exists()) d = new File("../misc/profiles2");
    return d;
  }

  private static BExpressionContextWay parse(String profile) {
    File dir = profileDir();
    assumeTrue("misc/profiles2 not available", dir.exists());
    File brf = new File(dir, profile + ".brf");
    assumeTrue("profile not available: " + profile, brf.exists());
    return parseFile(dir, brf);
  }

  /** Parse an inline profile body against the real lookups.dat. */
  private static BExpressionContextWay parseInline(String body) {
    File dir = profileDir();
    assumeTrue("misc/profiles2 not available", dir.exists());
    try {
      File tmp = File.createTempFile("probe-profile", ".brf");
      tmp.deleteOnExit();
      java.nio.file.Files.write(tmp.toPath(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return parseFile(dir, tmp);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static BExpressionContextWay parseFile(File profileDir, File brf) {
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay ctx = new BExpressionContextWay(meta);
    meta.readMetaData(new File(profileDir, "lookups.dat"));
    ctx.parseFile(brf, "global");
    return ctx;
  }

  /**
   * Every profile whose vehicle cannot ride loose unpaved is paved-only — not
   * just road bikes. skating (rollerblades) and the velomobil (faired road
   * trike) both forbid/heavily-penalise gravel, so a round-trip routed onto it
   * must be rejected for them too. This is why grade1 is not part of the test:
   * skating penalises even a grade1 track (g1/paved 1.95), yet is paved-only.
   */
  @Test
  public void cannotRideUnpavedProfilesClassifiedPaved() {
    for (String p : new String[]{"fastbike", "fastbike-verylowtraffic",
        "skating", "vm-forum-velomobil-schnell"}) {
      assertTrue("expected paved-only classification for " + p,
        RoundTripQualityGate.classifyPavedProfile(parse(p), "probe-" + p));
    }
  }

  @Test
  public void unpavedTolerantProfilesClassifiedNotPaved() {
    for (String p : new String[]{"trekking", "gravel", "mtb", "hiking-mountain", "shortest"}) {
      assertFalse("expected non-paved classification for " + p,
        RoundTripQualityGate.classifyPavedProfile(parse(p), "probe-" + p));
    }
  }

  /**
   * The ratio (not the absolute unpaved cost) is the discriminator: mtb's
   * absolute gravel cost (7.5) is near fastbike's (10), but mtb penalises paved
   * even harder, so its grade3 ratio (0.57) is far below the threshold.
   */
  @Test
  public void ratioDiscriminatesRoadFromMtb() {
    assertTrue(RoundTripQualityGate.probePavedFromCostModel(parse("fastbike")));
    assertFalse(RoundTripQualityGate.probePavedFromCostModel(parse("mtb")));
  }

  /**
   * Name-independence: a road-bike-SHAPED cost model (rides grade1 cheaply,
   * rejects grade3 gravel) is classified paved even with a name that contains
   * no {@code fastbike}/{@code racing}/{@code road} token — the property the
   * removed name heuristic could never deliver.
   */
  @Test
  public void roadShapedCostModelClassifiedPavedRegardlessOfName() {
    BExpressionContextWay roadShaped = parseInline(
      "---context:global\n"
        + "---context:way\nassign costfactor = switch surface=gravel 10 1.0\n"
        + "---context:node\nassign initialcost = 0\n");
    assertTrue("road-shaped cost model should classify paved-only",
      RoundTripQualityGate.classifyPavedProfile(roadShaped, "rennrad-no-token"));
  }

  /**
   * Classification is scale-invariant: cost is relative, so multiplying every
   * costfactor by a constant must not change the verdict. Both gates are ratios
   * against the same paved reference, so a profile and its k-scaled twin classify
   * identically — proven here for k=7 on both a paved-shaped and a flat profile.
   */
  @Test
  public void classificationIsScaleInvariant() {
    // Road-shaped: grade3 gravel = 10x paved/grade1. Scaling by 7 keeps the ratios.
    assertTrue(RoundTripQualityGate.probePavedFromCostModel(parseInline(
      "---context:global\n---context:way\nassign costfactor = switch surface=gravel 10 1.0\n"
        + "---context:node\nassign initialcost = 0\n")));
    assertTrue("x7 scaling must not change the paved verdict",
      RoundTripQualityGate.probePavedFromCostModel(parseInline(
        "---context:global\n---context:way\nassign costfactor = switch surface=gravel 70 7.0\n"
          + "---context:node\nassign initialcost = 0\n")));

    // Flat profile: not paved at any scale.
    assertFalse(RoundTripQualityGate.probePavedFromCostModel(parseInline(
      "---context:global\n---context:way\nassign costfactor = 1.0\n"
        + "---context:node\nassign initialcost = 0\n")));
    assertFalse("x7 scaling must not change the not-paved verdict",
      RoundTripQualityGate.probePavedFromCostModel(parseInline(
        "---context:global\n---context:way\nassign costfactor = 7.0\n"
          + "---context:node\nassign initialcost = 0\n")));
  }

  /** An explicit {@code roadbikeSurfaceGate} global overrides the probe verdict. */
  @Test
  public void authorOverrideForcesClassification() {
    // Permissive cost model (probe would say not-paved), but override=1 forces paved.
    BExpressionContextWay on = parseInline(
      "---context:global\nassign roadbikeSurfaceGate = 1\n"
        + "---context:way\nassign costfactor = 1.0\n"
        + "---context:node\nassign initialcost = 0\n");
    assertFalse("control: this cost model probes as not-paved",
      RoundTripQualityGate.probePavedFromCostModel(on));
    assertTrue("override=1 should force paved-only",
      RoundTripQualityGate.classifyPavedProfile(on, "override-on"));

    // Gravel-hostile cost model (probe would say paved), but override=0 forces not-paved.
    BExpressionContextWay off = parseInline(
      "---context:global\nassign roadbikeSurfaceGate = 0\n"
        + "---context:way\nassign costfactor = switch surface=gravel 10 1.0\n"
        + "---context:node\nassign initialcost = 0\n");
    assertTrue("control: this cost model probes as paved",
      RoundTripQualityGate.probePavedFromCostModel(off));
    assertFalse("override=0 should force not-paved",
      RoundTripQualityGate.classifyPavedProfile(off, "override-off"));
  }

  /** With no expression context to probe, the profile is treated as not-paved (no name guess). */
  @Test
  public void noContextClassifiesNotPaved() {
    // Even a "fastbike"/"road" name is not paved without a probe — the name
    // heuristic was removed; classification is cost-model-only. (Distinct cache
    // keys so this does not perturb the shared "fastbike" entry other tests use.)
    assertFalse(RoundTripQualityGate.classifyPavedProfile(null, "noctx-fastbike"));
    assertFalse(RoundTripQualityGate.classifyPavedProfile(null, "noctx-road-bike"));
    assertFalse(RoundTripQualityGate.classifyPavedProfile(null, "noctx-trekking"));
  }

  /** Once classified, the name-only entry point returns the memoised probe result. */
  @Test
  public void isPavedProfileReturnsMemoisedProbeResult() {
    String name = "memo-roadshape";
    BExpressionContextWay roadShaped = parseInline(
      "---context:global\n"
        + "---context:way\nassign costfactor = switch surface=gravel 10 1.0\n"
        + "---context:node\nassign initialcost = 0\n");
    // Unclassified -> not paved (no name guess); the probe makes it true and caches it.
    assertFalse(RoundTripQualityGate.isPavedProfile(name));
    RoundTripQualityGate.classifyPavedProfile(roadShaped, name);
    assertEquals(Boolean.TRUE, RoundTripQualityGate.isPavedProfile(name));
  }

  /**
   * A null-context classification (an AUTO-competition child engine built via
   * copyRequestFields, which omits expctxWay) must NOT overwrite a real probed
   * verdict in the shared static cache — otherwise every later isPavedProfile()
   * lookup would wrongly bypass the hostile-surface gate for the whole competition.
   */
  @Test
  public void nullContextDoesNotPoisonCachedClassification() {
    String name = "poison-test";
    RoundTripQualityGate.classifyPavedProfile(parse("fastbike"), name); // parent probes -> paved
    assertTrue(RoundTripQualityGate.isPavedProfile(name));
    // child with no context: returns the safe `false` for its own use, but must not cache it
    assertFalse(RoundTripQualityGate.classifyPavedProfile(null, name));
    assertTrue("null-context classify must not poison the cached verdict",
      RoundTripQualityGate.isPavedProfile(name));
  }
}
