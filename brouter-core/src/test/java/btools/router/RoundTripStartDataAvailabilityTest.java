package btools.router;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.NodesCache;

/**
 * Covers the round-trip start data-availability handling:
 *
 * <ul>
 *   <li>{@link NodesCache#hasSegmentFor}/{@link NodesCache#getSegmentFileName}
 *       — the side-effect-free predicate + canonical tile name.</li>
 *   <li>The {@link RoutingEngine#doRoundTrip} fail-fast guard that uses them, so
 *       a start in a tile with no .rd5 surfaces the canonical
 *       "datafile … not found" instead of being mislabeled "start point not on
 *       road network" by the greedy planner (whose {@code matchPoint} now maps
 *       <em>any</em> match failure — including the missing-tile
 *       IllegalArgumentException — to a null/skip).</li>
 * </ul>
 *
 * <p>Fixture map ({@link RoundTripFixture#segmentDir()}) ships exactly two tiles:
 * {@code E5_N45.rd5} (lon[5,10), lat[45,50)) and {@code E5_N50.rd5} (lat[50,55)).
 * lon 8.72/lat 60.0 falls in the never-present {@code E5_N60} tile.
 */
public class RoundTripStartDataAvailabilityTest {

  private static final File SEGMENT_DIR = RoundTripFixture.segmentDir();

  private static NodesCache freshCache() {
    File profileDir = RoundTripFixture.profileFile("trekking").getParentFile();
    File lookups = new File(profileDir, "lookups.dat");
    assumeTrue("lookups.dat not available", lookups.exists());
    assumeTrue("fixture segment dir not built: " + SEGMENT_DIR, SEGMENT_DIR.isDirectory());

    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay ctx = new BExpressionContextWay(meta);
    meta.readMetaData(lookups);
    ctx.parseFile(RoundTripFixture.profileFile("trekking"), "global");
    return new NodesCache(SEGMENT_DIR, ctx, false, 64L * 1024 * 1024, null, false);
  }

  @Test
  public void hasSegmentForTrueForPresentTiles() {
    NodesCache nc = freshCache();
    OsmNodeNamed inN50 = RoundTripFixture.node("n50", 8.72, 50.0); // E5_N50.rd5 (present)
    OsmNodeNamed inN45 = RoundTripFixture.node("n45", 7.0, 46.0);  // E5_N45.rd5 (present)

    Assert.assertTrue("E5_N50 tile must be detected as present",
      nc.hasSegmentFor(inN50.ilon, inN50.ilat));
    Assert.assertTrue("E5_N45 tile must be detected as present",
      nc.hasSegmentFor(inN45.ilon, inN45.ilat));
  }

  @Test
  public void hasSegmentForFalseForMissingTile() {
    NodesCache nc = freshCache();
    OsmNodeNamed missing = RoundTripFixture.node("gap", 8.72, 60.0); // E5_N60.rd5 (absent)

    Assert.assertFalse("E5_N60 has no .rd5 in the fixture and must read as absent",
      nc.hasSegmentFor(missing.ilon, missing.ilat));
  }

  @Test
  public void getSegmentFileNameIsCanonicalIrrespectiveOfExistence() {
    NodesCache nc = freshCache();
    OsmNodeNamed present = RoundTripFixture.node("n50", 8.72, 50.0);
    OsmNodeNamed missing = RoundTripFixture.node("gap", 8.72, 60.0);

    Assert.assertEquals("E5_N50.rd5", nc.getSegmentFileName(present.ilon, present.ilat));
    // Name is computed from the coordinate, not disk state.
    Assert.assertEquals("E5_N60.rd5", nc.getSegmentFileName(missing.ilon, missing.ilat));
  }

  @Test
  public void roundTripMissingStartTileSurfacesDatafileNotFound() {
    assumeTrue("fixture segment dir not built: " + SEGMENT_DIR, SEGMENT_DIR.isDirectory());

    RoutingEngine re = roundTripEngine(8.72, 60.0); // start in the absent E5_N60 tile
    re.doRun(0);

    String err = re.getErrorMessage();
    Assert.assertNotNull("a missing start tile must produce an error", err);
    Assert.assertTrue("must name the missing datafile: " + err,
      err.contains("datafile") && err.contains("E5_N60") && err.contains("not found"));
    Assert.assertFalse("missing data must NOT be mislabeled as off-network: " + err,
      err.contains("not on road network"));
  }

  @Test
  public void roundTripPresentStartTileIsNotFalselyRejected() {
    assumeTrue("fixture segment dir not built: " + SEGMENT_DIR, SEGMENT_DIR.isDirectory());

    RoutingEngine re = roundTripEngine(8.72, 50.0); // start in the present E5_N50 tile
    re.doRun(0);

    String err = re.getErrorMessage();
    // The route may or may not succeed on the tiny fixture, but the up-front
    // guard must never fire a false "datafile not found" for a present tile.
    if (err != null) {
      Assert.assertFalse("present tile must not trip the data-availability guard: " + err,
        err.contains("datafile") && err.contains("not found"));
    }
  }

  private static RoutingEngine roundTripEngine(double lon, double lat) {
    List<OsmNodeNamed> wps = new ArrayList<>();
    wps.add(RoundTripFixture.node("from", lon, lat));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    rc.roundTripDistance = 5000;

    RoutingEngine re = new RoutingEngine(null, null, SEGMENT_DIR, wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    return re;
  }
}
