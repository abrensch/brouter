package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.Test;
import btools.router.roundtrip.RoundTripAlgorithm;
import btools.router.roundtrip.RoundTripQualityGate;

/**
 * End-to-end pin for the shipped-crossings advisory plumbing (code-review
 * finding #3): {@code RoundTripOrchestrator} now reads the gate's own
 * self-intersection count off the accepted {@code RoundTripQualityResult}
 * ({@code quality.getSelfIntersections()}) instead of re-scanning the shipped
 * track a third time. This test drives a real round trip end to end through
 * {@link RoutingEngine#doRun} — not just the gate/score unit tests — and
 * checks that the "route crosses its own path N time(s)" advisory the caller
 * actually receives names the SAME count an independent, direct call to
 * {@link RoundTripQualityGate#countSelfIntersections} reports for the exact
 * same track. A regression that reads a stale/mismatched count (e.g. a
 * forgotten stamp on some result-building branch) would silently drift the
 * two numbers apart without failing any gate-level unit test.
 *
 * <p>Uses the same Annecy fixture and parameters as {@code AnnecyLoopShapeTest},
 * which documents this exact case as currently shipping up to one minor
 * self-crossing on real OSM tiles. Real-map geometry can drift with tile
 * updates, so this test skips (rather than fails) when the current tiles
 * happen to produce a fully clean loop — the advisory-consistency property it
 * pins only has something to check when a crossing exists to disclose.
 */
public class RoundTripShippedCrossingsAdvisoryIntegrationTest {

  private static final Pattern SHIPPED_CROSSINGS_MESSAGE =
    Pattern.compile("route crosses its own path (\\d+) time");

  @Test
  public void shippedCrossingsAdvisoryMatchesDirectRecompute() throws Exception {
    File projectDir = new File(".").getCanonicalFile().getParentFile();
    File segDir = new File(projectDir, "segments4");
    File segFile = new File(segDir, "E5_N45.rd5");
    Assume.assumeTrue("segment file missing: " + segFile, segFile.exists());
    File profileFile = new File(projectDir, "misc/profiles2/fastbike.brf");

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = (int) ((6.130 + 180.0) * 1e6);
    start.ilat = (int) ((45.900 + 90.0) * 1e6);
    wplist.add(start);

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 8000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.AUTO;
    rctx.roundTripStrictQuality = false;

    RoutingEngine re = new RoutingEngine(null, null, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(0);
    OsmTrack track = re.getFoundTrack();
    Assume.assumeNotNull("routing produced no track (terrain limitation)", track);

    int direct = RoundTripQualityGate.countSelfIntersections(track);
    Assume.assumeTrue("fixture currently ships a clean loop (tile drift) — nothing to pin here",
      direct > 0);

    String message = (track.messageList != null && !track.messageList.isEmpty())
      ? track.messageList.get(0) : track.message;
    org.junit.Assert.assertNotNull("a shipped crossing must carry the advisory", message);
    Matcher m = SHIPPED_CROSSINGS_MESSAGE.matcher(message);
    org.junit.Assert.assertTrue(
      "expected a 'route crosses its own path N time(s)' advisory in: " + message,
      m.find());
    int advised = Integer.parseInt(m.group(1));
    org.junit.Assert.assertEquals(
      "the shipped advisory's count must match a fresh direct recompute on the same track",
      direct, advised);
  }
}
