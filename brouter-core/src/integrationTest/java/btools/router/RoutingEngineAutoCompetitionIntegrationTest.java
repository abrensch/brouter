package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the AUTO candidate-competition flow described in
 * docs/features/roundtrip-auto-quality-redesign.md §353. These cases require
 * real routing and are segments-gated (skipped if data absent).
 *
 * <p>The flow under test: for generated AUTO loops with no user vias, the
 * engine runs ISO_GREEDY first, compares GREEDY when ISO_GREEDY is weak,
 * and falls back to legacy WAYPOINT/probe only when greedy variants fail.
 * Accepted candidates are scored and the highest-scoring child track is
 * adopted directly.
 */
public class RoutingEngineAutoCompetitionIntegrationTest {

  private File segmentDir;
  private File profileDir;

  @Before
  public void setup() {
    segmentDir = new File("../segments4");
    if (!segmentDir.exists() || !segmentDir.isDirectory()) {
      segmentDir = new File("segments4");
    }
    profileDir = new File("misc/profiles2");
    if (!profileDir.exists()) {
      profileDir = new File("../misc/profiles2");
    }
  }

  private boolean hasSegmentData(String region) {
    return segmentDir.exists() && segmentDir.isDirectory()
      && new File(segmentDir, region).exists();
  }

  // =========================================================================
  // §353.1–6, 10, 11 — Integration tests (segments-gated, real routing).
  // =========================================================================

  /** Dreieich start: 8.720, 50.000 — clean fastbike loops per the loop-quality data. */
  private static OsmNodeNamed dreieichStart() {
    OsmNodeNamed n = new OsmNodeNamed();
    n.ilon = (int) ((8.720 + 180) * 1_000_000);
    n.ilat = (int) ((50.000 + 90) * 1_000_000);
    n.name = "from";
    return n;
  }

  /**
   * Integration tests use the trekking profile, not fastbike: paved-only
   * fastbike rejects the path/track terrain that real routes around the
   * Dreieich start can pick up, and the AUTO competition test cares about
   * COMPETITION mechanics, not profile policy. The hostility gate is still
   * enforced and exercised separately.
   */
  private RoutingContext trekkingContext(int radius) {
    RoutingContext rctx = new RoutingContext();
    File trekking = new File(profileDir, "trekking.brf");
    rctx.localFunction = trekking.exists() ? trekking.getAbsolutePath()
      : new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx.roundTripDistance = radius;
    return rctx;
  }

  @Test
  public void largeLoopAutoTriesMultipleCandidatesAndAdoptsWinner() {
    // §353.1 / 353.6 / 353.11. AUTO + large radius runs the competition
    // and adopts the winner's track. Verify the result has a track and
    // the message records "AUTO selected ..." with the chosen algorithm.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(dreieichStart());
    RoutingContext rctx = trekkingContext(6000);
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 90; // East — Dreieich has clean fastbike to the east

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(120_000);

    Assert.assertNull("AUTO completed: " + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("track produced", re.getFoundTrack());
    Assert.assertNotNull("track has message", re.getFoundTrack().message);
    Assert.assertTrue("message mentions AUTO selection: " + re.getFoundTrack().message,
      re.getFoundTrack().message.contains("AUTO selected"));
  }

  @Test
  public void smallLoopAutoUsesGreedyCompetition() {
    // Small generated AUTO loops now use the same greedy-first competition
    // path as larger loops. The cheap selector remains only as a fallback
    // helper for unsupported/direct callers.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(dreieichStart());
    RoutingContext rctx = trekkingContext(3000);
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 90;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);

    Assert.assertNull("AUTO completed: " + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("track produced", re.getFoundTrack());
    Assert.assertNotNull("track has message", re.getFoundTrack().message);
    Assert.assertTrue("small loop entered AUTO competition: " + re.getFoundTrack().message,
      re.getFoundTrack().message.contains("AUTO selected"));
  }

  @Test
  public void forcedAlgorithmBypassesCompetition() {
    // §353.14. Explicit roundTripAlgorithm=WAYPOINT (or any forced algo)
    // bypasses the competition. The result message should NOT contain
    // "AUTO selected" because AUTO branch isn't entered.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(dreieichStart());
    RoutingContext rctx = trekkingContext(6000);
    rctx.roundTripAlgorithm = RoundTripAlgorithm.WAYPOINT; // forced
    rctx.startDirection = 90;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);

    Assert.assertNotNull("forced WAYPOINT must produce a track", re.getFoundTrack());
    Assert.assertNotNull("track must carry a disclosure message", re.getFoundTrack().message);
    Assert.assertFalse("forced algo bypasses competition: "
      + re.getFoundTrack().message,
      re.getFoundTrack().message.contains("AUTO selected"));
  }

  @Test
  public void competitionOutputIsSuppressed() {
    // §353.10. The competition runs children with null outfileBase; no
    // intermediate GPX files should be created. The parent engine writes
    // only the winner's track. We verify by passing a non-null outfile to
    // the parent, then checking the child outfiles don't exist.
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N50.rd5"));
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(dreieichStart());
    RoutingContext rctx = trekkingContext(6000);
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 90;

    File tmpDir = new File("build/tmp/auto-test");
    tmpDir.mkdirs();
    String outfileBase = new File(tmpDir, "parent").getAbsolutePath();
    RoutingEngine re = new RoutingEngine(outfileBase, outfileBase, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(120_000);

    // Parent should have written its GPX after adopting the winner.
    File parentGpx = new File(outfileBase + "0.gpx");
    Assert.assertTrue("parent GPX written: " + parentGpx, parentGpx.exists());
    // Children should not have created any intermediate files in the same
    // directory (they used null outfileBase so they can't write).
    // We check by listing the dir and confirming only the parent's files.
    File[] siblings = tmpDir.listFiles();
    Assert.assertNotNull(siblings);
    for (File f : siblings) {
      // Only the parent's prefix should appear
      Assert.assertTrue("unexpected intermediate file: " + f.getName(),
        f.getName().startsWith("parent"));
    }
  }

  /**
   * §353.1 / 353.6 / 353.11 — Real-world validation for the exact case the
   * user reported (mallorca_75km_fastbike_S in images 9–11). The forced
   * {@code [probe]} variant of this case produces a route with ~18 self-
   * crossings + visible spikes into wilderness from geometric waypoint
   * placement. AUTO mode should run the greedy-first competition
   * (ISO_GREEDY → GREEDY, with WAYPOINT/probe only as fallback), pick the
   * highest-scoring accepted candidate, and produce a track with
   * substantially fewer self-crossings.
   *
   * <p>This test is the regression guard against the user-visible chaos
   * pattern that originally motivated the AUTO redesign.
   */
  @Test
  public void mallorca75kmFastbikeAutoBeatsProbeSpike() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E0_N35.rd5"));
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((2.650 + 180) * 1_000_000);
    start.ilat = (int) ((39.570 + 90) * 1_000_000);
    start.name = "from";
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = new File(profileDir, "fastbike.brf").getAbsolutePath();
    rctx.roundTripDistance = 11937; // SEARCH_RADIUS for 75km in LoopQualityTest
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = 180; // S — the exact direction from images 9–11

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(180_000);

    Assert.assertNull("AUTO produced a route: " + re.getErrorMessage(),
      re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("track exists", track);
    Assert.assertNotNull("track.message recorded", track.message);
    Assert.assertTrue("competition ran (message mentions AUTO selected): "
      + track.message, track.message.contains("AUTO selected"));
    System.out.println("[mallorca75kmFastbikeAutoBeatsProbeSpike] winner: " + track.message);

    // Geometric quality: count self-crossings in the routed polyline.
    int selfCrossings = countSelfCrossings(track);
    System.out.println("[mallorca75kmFastbikeAutoBeatsProbeSpike] selfCrossings="
      + selfCrossings + " (probe baseline 18; iso_greedy baseline 3)");
    // The forced [probe] variant of this case scored 18 self-crossings.
    // AUTO picks ISO_GREEDY here and produces 0–3 self-crossings (verified
    // empirically). Pin ≤ 5 as a regression guard: tight enough that the
    // probe-style chaos pattern (≥ 10 typically) would clearly fail,
    // loose enough that minor routing-engine variance won't false-fail.
    Assert.assertTrue("AUTO winner has self-crossings ≤ 5 (probe baseline was 18); got "
        + selfCrossings + " — message: " + track.message,
      selfCrossings <= 5);
  }

  /**
   * Regression: AUTO must not hard-force the start direction on its child
   * candidates when the user supplied only a soft {@code direction}. Innsbruck
   * 50 km westward on fastbike is accepted by a free-bearing ISO_GREEDY, but
   * hard-forcing the opening bearing shoves the first leg onto a >1.5 km
   * profile-hostile stretch and the whole competition fails. Before the fix
   * AUTO returned no track here; it must now produce one.
   */
  @Test
  public void autoSoftDirectionDoesNotHardForceFirstLeg() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E10_N45.rd5"));
    OsmTrack track = runAuto("fastbike", 8000, 270, /*forceHeading=*/false);
    Assert.assertNotNull("AUTO produced a track (soft direction must not hard-force)", track);
  }

  /**
   * Regression: when ISO_GREEDY, GREEDY and WAYPOINT all fail, AUTO falls back
   * to direct ISOCHRONE placement. Innsbruck 100 km southward on gravel is one
   * such case — the greedy/probe candidates produce only chaotic loops, while
   * the isochrone frontier yields an accepted loop. Before the fallback was
   * added AUTO returned no track here.
   */
  @Test
  public void autoFallsBackToIsochroneWhenGreedyVariantsFail() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E10_N45.rd5"));
    OsmTrack track = runAuto("gravel", 15900, 180, /*forceHeading=*/false);
    Assert.assertNotNull("AUTO produced a track via the ISOCHRONE fallback", track);
  }

  /** Run an AUTO round trip from the Innsbruck start and return the found track. */
  private OsmTrack runAuto(String profileName, int searchRadius, int direction, boolean forceHeading) {
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((11.400 + 180) * 1_000_000);
    start.ilat = (int) ((47.260 + 90) * 1_000_000);
    start.name = "from";
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = new File(profileDir, profileName + ".brf").getAbsolutePath();
    rctx.roundTripDistance = searchRadius;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.startDirection = direction;
    rctx.forceUseStartDirection = forceHeading;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(180_000);
    return re.getFoundTrack();
  }

  /**
   * Regression: greedy/iso/AUTO round trips must emit voice hints when a
   * turn-instruction mode is requested. The greedy planner assembles its loop
   * from already-detailed legs but used to drop their detour metadata, leaving
   * processVoiceHints with nothing — so the GPX came back with an empty
   * turn-instruction list. The detoured merge carries the leg detourMaps onto
   * the result track so hints are produced (afischerdev review).
   */
  @Test
  public void greedyRoundTripEmitsVoiceHints() {
    Assume.assumeTrue("Segment data required", hasSegmentData("E5_N40.rd5"));
    RoundTripAlgorithm[] algos = {
      RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY, RoundTripAlgorithm.AUTO
    };
    for (RoundTripAlgorithm algo : algos) {
      OsmTrack t = runRoundTripWithTurns(7.270, 43.700, "hiking-mountain", 5000, 180, algo, 4);
      Assert.assertNotNull(algo + " produced a round-trip track", t);
      Assert.assertNotNull(algo + " track has a voice-hint list", t.voiceHints);
      Assert.assertFalse(algo + " round trip (timode=4) must emit voice hints",
        t.voiceHints.list.isEmpty());
    }
  }

  private OsmTrack runRoundTripWithTurns(double lon, double lat, String profileName,
      int searchRadius, int direction, RoundTripAlgorithm algo, int turnInstructionMode) {
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((lon + 180) * 1_000_000);
    start.ilat = (int) ((lat + 90) * 1_000_000);
    start.name = "from";
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = new File(profileDir, profileName + ".brf").getAbsolutePath();
    rctx.roundTripDistance = searchRadius;
    rctx.roundTripAlgorithm = algo;
    rctx.startDirection = direction;
    rctx.turnInstructionMode = turnInstructionMode;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wps, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(180_000);
    return re.getFoundTrack();
  }

  /** Count consecutive-edge intersections in a track polyline (excluding
   * shared endpoints). Mirrors the analyzer-script logic; O(n²) but track
   * sizes are bounded by routing tolerances so this is fine for one test. */
  private static int countSelfCrossings(OsmTrack track) {
    int n = track.nodes.size();
    int crossings = 0;
    for (int i = 0; i < n - 1; i++) {
      OsmPathElement a1 = track.nodes.get(i);
      OsmPathElement a2 = track.nodes.get(i + 1);
      for (int j = i + 2; j < n - 1; j++) {
        OsmPathElement b1 = track.nodes.get(j);
        OsmPathElement b2 = track.nodes.get(j + 1);
        if (segmentsCross(a1, a2, b1, b2)) crossings++;
      }
    }
    return crossings;
  }

  private static boolean segmentsCross(OsmPathElement p1, OsmPathElement p2,
                                       OsmPathElement p3, OsmPathElement p4) {
    if ((p1.getILon() == p3.getILon() && p1.getILat() == p3.getILat())
        || (p1.getILon() == p4.getILon() && p1.getILat() == p4.getILat())
        || (p2.getILon() == p3.getILon() && p2.getILat() == p3.getILat())
        || (p2.getILon() == p4.getILon() && p2.getILat() == p4.getILat())) {
      return false; // shared endpoints are not self-crossings
    }
    long c1 = ccw(p1, p3, p4);
    long c2 = ccw(p2, p3, p4);
    long c3 = ccw(p1, p2, p3);
    long c4 = ccw(p1, p2, p4);
    return (c1 > 0) != (c2 > 0) && (c3 > 0) != (c4 > 0);
  }

  /** Cross-product sign for ccw test; uses long arithmetic to avoid
   * integer overflow on ilon×ilat products at routing-scale coords. */
  private static long ccw(OsmPathElement a, OsmPathElement b, OsmPathElement c) {
    long dx1 = (long) b.getILon() - a.getILon();
    long dy1 = (long) b.getILat() - a.getILat();
    long dx2 = (long) c.getILon() - a.getILon();
    long dy2 = (long) c.getILat() - a.getILat();
    return dx1 * dy2 - dy1 * dx2;
  }
}
