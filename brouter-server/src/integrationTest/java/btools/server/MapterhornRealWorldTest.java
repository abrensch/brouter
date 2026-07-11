package btools.server;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import java.io.File;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The two real-world test cases suggested by @afischerdev in issue #558, frozen as
 * offline regression tests over REAL Mapterhorn tiles and REAL OSM extracts
 * (route geometry and expectations calibrated 2026-07-10 against brouter.de's new
 * 1-arcsecond elevation).
 * <p>
 * TUNNEL (Meisterntunnel, Bad Wildbad): the DTM reads up to +146 m ABOVE the route over
 * the tunnel hill. The tunnel machinery (WayLinker's NO_TUNNEL_BIT, no-data interior
 * elevations, portal-to-portal interpolation in the engine) must keep that hill out of
 * the routed profile; with Mapterhorn the residual portal step measured 5.0 m (legacy
 * data: 9.75 m).
 * <p>
 * HELGOLAND: an isolated island - surrounding ocean tiles are ABSENT from the archive,
 * the cliff between Unterland and Oberland is the sharpest terrain the converter meets,
 * and harbours carry water-surface elevations.
 */
public class MapterhornRealWorldTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @After
  public void clearMapPollingProperty() {
    System.clearProperty("avoidMapPolling");
  }

  @Test
  public void tunnelHillStaysOutOfTheRoutedProfile() throws Exception {
    File workDir = tmp.newFolder("tunnel");
    File srtmDir = Rd5TestHarness.mkdir(workDir, "srtm");
    Rd5TestHarness.buildBef(getClass(), new int[][]{{2145, 1410}, {2145, 1411}},
      5, 45, new double[]{8.53, 48.72, 8.58, 48.78}, workDir, srtmDir);
    File segments = Rd5TestHarness.buildRd5(getClass(), "/mapterhorn/badwildbad.pbf",
      workDir, srtmDir);

    // the exact case from #558
    OsmTrack track = Rd5TestHarness.route(segments, "car-vario",
      "8.551975,48.757943|8.548198,48.743116");
    assertTrue("track too short: " + track.nodes.size(), track.nodes.size() >= 50);

    double maxEle = Double.NEGATIVE_INFINITY;
    double maxStep = 0;
    double prev = Double.NaN;
    for (OsmPathElement p : track.nodes) {
      assertNotEquals("point without elevation at " + (p.getILat() / 1e6 - 90) + ","
        + (p.getILon() / 1e6 - 180), (long) Short.MIN_VALUE, (long) p.getSElev());
      double ele = p.getSElev() / 4.0;
      maxEle = Math.max(maxEle, ele);
      if (!Double.isNaN(prev)) {
        maxStep = Math.max(maxStep, Math.abs(ele - prev));
      }
      prev = ele;
    }
    // the hill above the Meisterntunnel is ~590 m; the road never exceeds ~455 m.
    // if tunnel interpolation broke, maxEle would jump by >100 m.
    assertTrue("tunnel hill leaked into the profile: max elevation " + maxEle + " m",
      maxEle < 470.0);
    // residual portal step measured 5.0 m with Mapterhorn (9.75 m with legacy data)
    assertTrue("portal step regressed: " + maxStep + " m", maxStep <= 6.5);
    assertTrue("ascent regressed: " + hystGain(track) + " m", hystGain(track) <= 50.0);
  }

  @Test
  public void helgolandIslandRoutesWithPlausibleCliffProfile() throws Exception {
    File workDir = tmp.newFolder("helgoland");
    File srtmDir = Rd5TestHarness.mkdir(workDir, "srtm");
    Rd5TestHarness.buildBef(getClass(),
      new int[][]{{2137, 1311}, {2137, 1312}, {2138, 1311}, {2138, 1312}},
      5, 50, new double[]{7.85, 54.15, 7.95, 54.21}, workDir, srtmDir);
    File segments = Rd5TestHarness.buildRd5(getClass(), "/mapterhorn/helgoland.pbf",
      workDir, srtmDir);

    // the exact hiking route from #558: Unterland -> cliff -> Oberland
    OsmTrack track = Rd5TestHarness.route(segments, "hiking-mountain",
      "7.889622,54.180475|7.874973,54.186364|7.880015,54.187448");
    assertTrue("track too short: " + track.nodes.size(), track.nodes.size() >= 50);

    double minEle = Double.POSITIVE_INFINITY;
    double maxEle = Double.NEGATIVE_INFINITY;
    for (OsmPathElement p : track.nodes) {
      assertNotEquals("point without elevation at " + (p.getILat() / 1e6 - 90) + ","
        + (p.getILon() / 1e6 - 180), (long) Short.MIN_VALUE, (long) p.getSElev());
      double ele = p.getSElev() / 4.0;
      minEle = Math.min(minEle, ele);
      maxEle = Math.max(maxEle, ele);
    }
    // Unterland sits at 2-6 m, the Oberland plateau at ~40-61 m: the route must
    // start low and climb the cliff
    assertTrue("Unterland too high/low: " + minEle + " m", minEle >= -4.0 && minEle <= 12.0);
    assertTrue("Oberland plateau off: " + maxEle + " m", maxEle >= 35.0 && maxEle <= 62.0);
    double gain = hystGain(track);
    assertTrue("cliff climb gain implausible: " + gain + " m", gain >= 30.0 && gain <= 100.0);
  }

  /**
   * Ascent the way BRouter reports it: positive deltas with a 10 m hysteresis.
   */
  private static double hystGain(OsmTrack track) {
    double gain = 0;
    double anchor = Double.NaN;
    for (OsmPathElement p : track.nodes) {
      if (p.getSElev() == Short.MIN_VALUE) {
        continue;
      }
      double h = p.getSElev() / 4.0;
      if (Double.isNaN(anchor)) {
        anchor = h;
        continue;
      }
      if (h > anchor + 10.0) {
        gain += h - anchor;
        anchor = h;
      } else if (h < anchor - 10.0) {
        anchor = h;
      }
    }
    return gain;
  }
}
