package btools.router;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Real Dreieich <em>small-loop</em> tests for the output pipeline — the
 * formatters ({@link FormatGpx}/{@link FormatJson}/{@link FormatKml}) and the
 * engine's file-write path ({@code writeAdoptedTrackOutput}).
 *
 * <p>They run against the bundled fixture segment used by {@link RoundTripFixture}
 * (the build-generated Dreieich {@code E5_N50.rd5}), so they execute in CI
 * instead of being skipped — the "not ignored, Dreieich mini round-trip" tests
 * afischerdev asked for on PR&nbsp;#903. The fixture is a tiny tile, so the loops
 * are deliberately small ({@value #RADIUS} m search radius); the {@code gravel}
 * profile forms a clean loop in every compass direction on it (verified across
 * the algorithm/direction/radius matrix), which keeps these tests reliable
 * rather than direction-fragile.
 *
 * <p><b>Regression guarded.</b> The AUTO competition and the forced
 * GREEDY/ISO_GREEDY paths adopt a track assembled from merged greedy legs via
 * {@code new OsmTrack()}. Plain point-to-point routes get their
 * {@code messageList} populated in {@code doRouting}; the merged round-trip
 * track did not, so it reached {@link FormatGpx} with {@code messageList == null}
 * and any GPX export threw
 * <pre>NullPointerException: Cannot invoke "java.util.List.size()" because "t.messageList" is null</pre>
 * JSON/KML never read {@code messageList}, which is why the failure was
 * GPX-only and the pre-existing round-trip tests (which only inspect the track
 * object, never format it) missed it entirely.
 *
 * <p>Pre-fix, the GPX tests below fail on this exact fixture (verified by
 * reverting the fix at any of these small radii); post-fix they pass. The
 * root-cause fix populates {@code messageList} in
 * {@code finalizeAdoptedRoundTripTrack} ({@code ensureInfoMessage}); the
 * formatters were additionally hardened to tolerate a null/empty list.
 */
public class RoundTripOutputFormatTest {

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  /** gravel forms a clean loop in every direction on the fixture (matrix-verified). */
  private static final String PROFILE = "gravel";
  /** Small loop: the fixture is a ~3 km tile, so keep the search radius tiny. */
  private static final int RADIUS = 1000;
  private static final int[] DIRECTIONS = {0, 90, 180, 270};
  private static final int EAST = 90;

  // -------------------------------------------------------------------------
  // The regression guards: AUTO / greedy small loops must export GPX in every
  // direction (the merged-track path that had a null messageList).
  // -------------------------------------------------------------------------

  @Test
  public void autoSmallLoopGpxExportDoesNotThrow() {
    for (int dir : DIRECTIONS) {
      Result r = route(RoundTripAlgorithm.AUTO, dir, RADIUS, PROFILE, 2);
      String label = "AUTO dir=" + dir;
      assertProduced(r, label);
      assertMessageList(r.track, label);

      String gpx = new FormatGpx(r.rc).format(r.track);
      assertWellFormedGpx(gpx, label);
      // The info line the fix attaches must reach the GPX verbatim.
      Assert.assertTrue(label + ": GPX must embed the info message: " + r.track.messageList.get(0),
        gpx.contains(r.track.messageList.get(0)));
      Assert.assertTrue(label + ": small loop (timode=2) must carry turn instructions",
        r.track.voiceHints != null && !r.track.voiceHints.list.isEmpty());
    }
  }

  @Test
  public void mergedSmallLoopsExportGpxWithoutNpe() {
    // GREEDY and ISO_GREEDY always adopt a merged greedy track — the exact
    // shape whose messageList was null. Cover both, in every direction.
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      for (int dir : DIRECTIONS) {
        Result r = route(algo, dir, RADIUS, PROFILE, 4);
        String label = algo + " dir=" + dir;
        assertProduced(r, label);
        assertMessageList(r.track, label);
        assertWellFormedGpx(new FormatGpx(r.rc).format(r.track), label);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Full-matrix coverage: every output format and every turn-instruction mode
  // must export a merged small loop without throwing.
  // -------------------------------------------------------------------------

  @Test
  public void gpxExportCoversAllTurnInstructionModes() {
    // timode 9 (BRouter style) reads messageList.get(0) for <brouter:info>;
    // every other mode iterates the whole messageList for the comment header.
    // Both reads NPE'd pre-fix. ISO_GREEDY guarantees the merged-track path.
    // Cover every turn-instruction mode, including 8 (cruiser) whose <rte> block
    // dereferences t.matchedWaypoints.get(0) / t.voiceHints.list.get(0) — a merged
    // round-trip track must export it without NPE.
    for (int timode : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}) {
      Result r = route(RoundTripAlgorithm.ISO_GREEDY, EAST, RADIUS, PROFILE, timode);
      String label = "ISO_GREEDY timode=" + timode;
      assertProduced(r, label);
      assertMessageList(r.track, label);
      assertWellFormedGpx(new FormatGpx(r.rc).format(r.track), label);
    }
  }

  @Test
  public void smallLoopExportsGpxJsonAndKml() {
    Result r = route(RoundTripAlgorithm.AUTO, EAST, RADIUS, PROFILE, 2);
    assertProduced(r, "AUTO");

    String gpx = new FormatGpx(r.rc).format(r.track);
    String json = new FormatJson(r.rc).format(r.track);
    String kml = new FormatKml(r.rc).format(r.track);

    assertWellFormedGpx(gpx, "AUTO");
    Assert.assertTrue("JSON is a FeatureCollection", json != null && json.contains("\"FeatureCollection\""));
    Assert.assertTrue("KML has a <kml> root", kml != null && kml.contains("<kml") && kml.contains("</kml>"));
  }

  // -------------------------------------------------------------------------
  // The engine's own write path (the actual production scenario afischerdev
  // ran): outfileBase set, the parent engine formats + writes the adopted
  // winner to a file. Must produce a parseable file, not log
  // "AUTO: failed to write adopted track: NullPointerException".
  // -------------------------------------------------------------------------

  @Test
  public void engineWritesParseableGpxFileForSmallLoop() throws Exception {
    String base = new File(outputDir.getRoot(), "auto").getAbsolutePath();
    Result r = route(RoundTripAlgorithm.AUTO, EAST, RADIUS, PROFILE, 2, "gpx", base);
    Assert.assertNull("engine reported an error: " + r.engine.getErrorMessage(),
      r.engine.getErrorMessage());
    Assert.assertNotNull("engine produced a track", r.track);

    File gpxFile = new File(base + "0.gpx");
    Assert.assertTrue("engine wrote the adopted GPX to " + gpxFile,
      gpxFile.exists() && gpxFile.length() > 0);
    String content = new String(Files.readAllBytes(gpxFile.toPath()), StandardCharsets.UTF_8);
    assertWellFormedGpx(content, "engine-write");
    assertParseableXml(content, "engine-write");
  }

  @Test
  public void engineWritesCsvFileForSmallLoop() {
    String base = new File(outputDir.getRoot(), "autocsv").getAbsolutePath();
    Result r = route(RoundTripAlgorithm.AUTO, EAST, RADIUS, PROFILE, 0, "csv", base);
    Assert.assertNull("engine reported an error: " + r.engine.getErrorMessage(),
      r.engine.getErrorMessage());
    Assert.assertNotNull("engine produced a track", r.track);

    File csvFile = new File(base + "0.csv");
    Assert.assertTrue("engine wrote the adopted CSV to " + csvFile,
      csvFile.exists() && csvFile.length() > 0);
  }

  // -------------------------------------------------------------------------
  // exportWaypoints: a round trip requested with exportWaypoints must emit the
  // route waypoints into the GPX without tripping over the adopted track's
  // matchedWaypoints, and stay well-formed.
  // -------------------------------------------------------------------------

  @Test
  public void smallLoopWithExportWaypointsStaysWellFormed() {
    Result r = route(RoundTripAlgorithm.AUTO, EAST, RADIUS, PROFILE, 2, null, null, rc -> {
      rc.exportWaypoints = true;
      rc.exportCorrectedWaypoints = true;
    });
    assertProduced(r, "exportWaypoints");
    String gpx = new FormatGpx(r.rc).format(r.track);
    assertWellFormedGpx(gpx, "exportWaypoints");
    Assert.assertTrue("exportWaypoints must emit <wpt> entries", gpx.contains("<wpt"));
  }

  // -------------------------------------------------------------------------
  // Voice-hint regression, now in CI: greedy-merged round trips drop their leg
  // detour metadata unless the detoured merge carries it forward, which would
  // leave processVoiceHints with nothing to emit. The earlier guard for this
  // (greedyRoundTripEmitsVoiceHints) is segments-gated and skipped in CI; this
  // one runs on the bundled fixture.
  // -------------------------------------------------------------------------

  @Test
  public void mergedSmallLoopsEmitVoiceHints() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.AUTO, RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      Result r = route(algo, EAST, RADIUS, PROFILE, 4);
      assertProduced(r, algo.name());
      Assert.assertNotNull(algo + " track must carry a voice-hint list", r.track.voiceHints);
      Assert.assertFalse(algo + " small loop (timode=4) must emit voice hints",
        r.track.voiceHints.list.isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // Foot-profile guard. afischerdev's reported empty-voicehint round trip used
  // a FOOT profile (hiking-mountain, timode=4) — a different code path from the
  // gravel/bike guards above: foot routing builds its own detourMap, and the
  // merged-loop path must carry it onto the closed loop or processVoiceHints
  // emits nothing. The bike guards would not have caught a foot-only regression,
  // so pin the foot profile explicitly on the bundled fixture (CI, not skipped).
  // -------------------------------------------------------------------------

  @Test
  public void footProfileMergedLoopEmitsVoiceHints() {
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.AUTO, RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.ISO_GREEDY}) {
      Result r = route(algo, EAST, RADIUS, "hiking-mountain", 4);
      String label = "hiking-mountain " + algo;
      assertProduced(r, label);
      assertMessageList(r.track, label);
      Assert.assertNotNull(label + ": foot loop must carry a voice-hint list", r.track.voiceHints);
      Assert.assertFalse(label + ": foot loop (timode=4) must emit voice hints — the reported"
        + " empty-voicehint regression", r.track.voiceHints.list.isEmpty());
      // The merged foot loop must also format without the messageList/voiceHints NPE.
      assertWellFormedGpx(new FormatGpx(r.rc).format(r.track), label);
    }
  }

  // -------------------------------------------------------------------------
  // Algorithm-name end-to-end: a forced ISO_GREEDY must resolve through setParams
  // (RoutingParamCollector → fromString) AND drive a real route to a loop — the
  // full param-string-to-route path, not just the fromString unit mapping.
  // -------------------------------------------------------------------------

  @Test
  public void isoGreedyNameRoutesEndToEnd() {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.72, 50.0));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(PROFILE).getAbsolutePath();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("roundTripAlgorithm", "ISO_GREEDY");
    params.put("roundTripDistance", String.valueOf(RADIUS));
    params.put("direction", String.valueOf(EAST));
    new RoutingParamCollector().setParams(rc, null, params);
    Assert.assertEquals("ISO_GREEDY name must resolve to the enum",
      RoundTripAlgorithm.ISO_GREEDY, rc.roundTripAlgorithm);

    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    Assert.assertNull("ISO_GREEDY route error: " + re.getErrorMessage(), re.getErrorMessage());
    Assert.assertNotNull("ISO_GREEDY must produce a loop", re.getFoundTrack());
    Assert.assertTrue("ISO_GREEDY loop must be non-degenerate",
      re.getFoundTrack().nodes.size() > 2);
  }

  // -------------------------------------------------------------------------
  // WAYPOINT (FAST) and ISOCHRONE take the non-merged doWaypointBasedRoundTrip
  // path — a different track shape from the greedy-merged tiers (AUTO/GREEDY/
  // ISO_GREEDY) the format guards above cover. They are marginal on the tiny
  // fixture, so skip-tolerate loop formation but assert clean GPX + JSON +
  // messageList whenever a track IS produced.
  // -------------------------------------------------------------------------

  @Test
  public void waypointAndIsochroneTracksFormatCleanlyWhenProduced() {
    boolean any = false;
    for (RoundTripAlgorithm algo : new RoundTripAlgorithm[]{
        RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.ISOCHRONE}) {
      for (int dir : DIRECTIONS) {
        Result r = route(algo, dir, RADIUS, PROFILE, 4);
        if (r.engine.getErrorMessage() != null || r.track == null
            || r.track.nodes.size() <= 2) {
          continue; // marginal tier × tiny fixture — nothing to format here
        }
        any = true;
        String label = algo + " dir=" + dir;
        assertMessageList(r.track, label);
        assertWellFormedGpx(new FormatGpx(r.rc).format(r.track), label);
        String json = new FormatJson(r.rc).format(r.track);
        Assert.assertNotNull(label + ": JSON is null", json);
        Assert.assertTrue(label + ": JSON must carry coordinates", json.contains("coordinates"));
      }
    }
    org.junit.Assume.assumeTrue(
      "neither WAYPOINT nor ISOCHRONE formed a loop on the fixture — nothing to format-check",
      any);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  /** Engine + the context it routed with + its found track. */
  private static final class Result {
    final RoutingEngine engine;
    final RoutingContext rc;
    final OsmTrack track;

    Result(RoutingEngine engine, RoutingContext rc, OsmTrack track) {
      this.engine = engine;
      this.rc = rc;
      this.track = track;
    }
  }

  private Result route(RoundTripAlgorithm algo, int direction, int radius, String profile, int timode) {
    return route(algo, direction, radius, profile, timode, null, null, rc -> { });
  }

  private Result route(RoundTripAlgorithm algo, int direction, int radius, String profile, int timode,
                       String outputFormat, String outfileBase) {
    return route(algo, direction, radius, profile, timode, outputFormat, outfileBase, rc -> { });
  }

  private Result route(RoundTripAlgorithm algo, int direction, int radius, String profile, int timode,
                       String outputFormat, String outfileBase, Consumer<RoutingContext> tweak) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", 8.72, 50.0));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile(profile).getAbsolutePath();
    rc.roundTripDistance = radius;
    rc.roundTripAlgorithm = algo;
    rc.startDirection = direction;
    rc.turnInstructionMode = timode;
    if (outputFormat != null) {
      rc.outputFormat = outputFormat;
    }
    tweak.accept(rc);

    RoutingEngine re = new RoutingEngine(outfileBase, outfileBase, RoundTripFixture.segmentDir(),
      wps, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(60_000);
    return new Result(re, rc, re.getFoundTrack());
  }

  /**
   * gravel reliably routes these small loops in every direction on the fixture;
   * if a future fixture change stops producing one the test should fail loudly
   * (it is meant to run, not be skipped), so this asserts rather than
   * {@code Assume}s.
   */
  private static void assertProduced(Result r, String label) {
    Assert.assertNull(label + ": engine error — " + r.engine.getErrorMessage(),
      r.engine.getErrorMessage());
    Assert.assertNotNull(label + ": no track produced", r.track);
    Assert.assertTrue(label + ": degenerate track (" + r.track.nodes.size() + " nodes)",
      r.track.nodes.size() > 2);
  }

  private static void assertMessageList(OsmTrack track, String label) {
    Assert.assertNotNull(label + ": messageList must not be null (the reported NPE)",
      track.messageList);
    Assert.assertFalse(label + ": messageList must not be empty", track.messageList.isEmpty());
    Assert.assertNotNull(label + ": messageList[0] must not be null", track.messageList.get(0));
    Assert.assertFalse(label + ": messageList[0] must not be empty", track.messageList.get(0).isEmpty());
  }

  private static void assertWellFormedGpx(String gpx, String label) {
    Assert.assertNotNull(label + ": GPX is null", gpx);
    Assert.assertTrue(label + ": GPX must start with the XML declaration", gpx.startsWith("<?xml"));
    Assert.assertTrue(label + ": GPX must contain a <gpx> root", gpx.contains("<gpx"));
    Assert.assertTrue(label + ": GPX must close the <gpx> root", gpx.contains("</gpx>"));
    Assert.assertTrue(label + ": GPX must contain track points", gpx.contains("<trkpt"));
  }

  /** Parse with a real namespace-aware XML parser to prove well-formedness. */
  private static void assertParseableXml(String xml, String label) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    DocumentBuilder db = dbf.newDocumentBuilder();
    db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
