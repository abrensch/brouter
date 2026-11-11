package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class LoopGeneratorTest {
  private File workingDir;

  @Before
  public void before() {
    URL resulturl = this.getClass().getResource("/testtrack0.gpx");
    Assert.assertNotNull("reference result not found: ", resulturl);
    File resultfile = new File(resulturl.getFile());
    workingDir = resultfile.getParentFile();
  }

  @Test
  public void generatesLoopWithinToleranceBand() {
    RoutingContext rctx = new RoutingContext();
    rctx.loopDistance = 3000;      // 3 km target
    rctx.loopTolerance = 0.15;     // +/-15%
    rctx.loopMaxResults = 5;

    RoutingEngine re = runLoop(8.720897, 50.002515, "looptrack", rctx);

    Assert.assertNull("loop generation failed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("no loop track produced", track);
    Assert.assertTrue("loop track empty", track.nodes.size() > 2);

    // starts and ends at the same node -> it is a loop
    OsmPathElement first = track.nodes.get(0);
    OsmPathElement last = track.nodes.get(track.nodes.size() - 1);
    Assert.assertEquals("loop does not return to start (lon)", first.getILon(), last.getILon());
    Assert.assertEquals("loop does not return to start (lat)", first.getILat(), last.getILat());

    // length is within the acceptance band around D
    int d = rctx.loopDistance;
    double lo = d * (1.0 - rctx.loopTolerance);
    double hi = d * (1.0 + rctx.loopTolerance);
    Assert.assertTrue("loop length " + track.distance + " out of band [" + lo + "," + hi + "]",
      track.distance >= lo && track.distance <= hi);

    // continuity guard: no consecutive-node jump. A big gap here is the "straight line to
    // near the start" artifact (mis-oriented return leg).
    int maxGap = 0;
    for (int i = 1; i < track.nodes.size(); i++) {
      maxGap = Math.max(maxGap, track.nodes.get(i).calcDistance(track.nodes.get(i - 1)));
    }
    Assert.assertTrue("loop has a discontinuity (gap " + maxGap + "m)", maxGap < 1000);

    // a gpx file for the best loop was written
    File out = new File(workingDir, "looptrack0.gpx");
    out.deleteOnExit();
    Assert.assertTrue("no loop gpx written", out.exists());
  }

  @Test
  public void returnsRankedListAsFeatureCollection() {
    RoutingContext rctx = new RoutingContext();
    rctx.loopDistance = 3000;
    rctx.loopTolerance = 0.15;
    rctx.loopMaxResults = 5;
    rctx.outputFormat = "geojson";

    RoutingEngine re = runLoop(8.720897, 50.002515, "looptrackfc", rctx);
    Assert.assertNull("loop generation failed: " + re.getErrorMessage(), re.getErrorMessage());

    List<OsmTrack> loops = re.getFoundTracks();
    Assert.assertNotNull("no ranked loop list produced", loops);
    Assert.assertFalse("ranked loop list empty", loops.isEmpty());
    Assert.assertTrue("more loops than requested", loops.size() <= rctx.loopMaxResults);

    String json = new FormatJson(rctx).format(loops);
    Assert.assertTrue("not a FeatureCollection", json.contains("\"type\": \"FeatureCollection\""));
    // one Feature per loop
    int features = json.split("\"type\": \"Feature\"", -1).length - 1;
    Assert.assertEquals("feature count != loop count", loops.size(), features);
    Assert.assertTrue("best loop not ranked 0", json.contains("\"rank\": 0"));

    for (int i = 0; i < loops.size(); i++) {
      File out = new File(workingDir, "looptrackfc" + i + ".geojson");
      out.deleteOnExit();
    }
  }

  @Test
  public void hillAndSurfaceWeightsProduceValidLoops() {
    // avoid hills + target paved paths, via the loop ranking factors
    RoutingContext rctx = new RoutingContext();
    rctx.loopDistance = 3000;
    rctx.loopTolerance = 0.20;
    rctx.loopMaxResults = 2;
    rctx.loopWeightHills = 0.4;
    rctx.loopHillPreference = "avoid";
    rctx.loopWeightSurface = 0.4;
    rctx.loopSurface = "paved,path";
    rctx.loopRandomness = 0.0; // deterministic

    RoutingEngine re = runLoop(8.720897, 50.002515, "loophill", rctx);
    Assert.assertNull("hill/surface loop failed: " + re.getErrorMessage(), re.getErrorMessage());
    OsmTrack track = re.getFoundTrack();
    Assert.assertNotNull("no loop produced with hill/surface weights", track);

    int d = rctx.loopDistance;
    double lo = d * (1.0 - rctx.loopTolerance);
    double hi = d * (1.0 + rctx.loopTolerance);
    Assert.assertTrue("loop length " + track.distance + " out of band", track.distance >= lo && track.distance <= hi);

    OsmPathElement first = track.nodes.get(0);
    OsmPathElement last = track.nodes.get(track.nodes.size() - 1);
    Assert.assertEquals(first.getILon(), last.getILon());
    Assert.assertEquals(first.getILat(), last.getILat());

    File out = new File(workingDir, "loophill0.gpx");
    out.deleteOnExit();
  }

  @Test
  public void randomnessIsDeterministicWhenOffAndReproducibleWithSeed() {
    // randomness off -> identical results
    Assert.assertEquals(loopSignature(0.0, null), loopSignature(0.0, null));
    // fixed seed -> reproducible even with randomness on
    Assert.assertEquals(loopSignature(0.6, 123L), loopSignature(0.6, 123L));
  }

  @Test
  public void randomnessVariesResultsAcrossSeeds() {
    // With randomness on and several seeds, the best loop should differ across calls when the
    // area offers more than one good loop (it does here).
    java.util.Set<String> distinct = new java.util.HashSet<>();
    for (long seed = 1; seed <= 8; seed++) {
      distinct.add(loopSignature(1.0, seed));
    }
    Assert.assertTrue("randomness produced no variety: " + distinct, distinct.size() >= 2);
  }

  @Test
  public void loopsHaveNoOutAndBackSpurs() {
    // An immediate out-and-back (P -> Q -> P) shows up as nodes[i-1] and nodes[i+1] sharing a
    // position. Clean loops never do this; spur trimming must eliminate it. Check several seeds.
    for (long seed = 1; seed <= 6; seed++) {
      RoutingContext rctx = new RoutingContext();
      rctx.loopDistance = 3000;
      rctx.loopTolerance = 0.30;
      rctx.loopMaxResults = 2;
      rctx.loopRandomness = 1.0;
      rctx.loopSeed = seed;
      RoutingEngine re = runLoop(8.720897, 50.002515, "loopspur", rctx);
      Assert.assertNull("loop failed: " + re.getErrorMessage(), re.getErrorMessage());
      for (OsmTrack t : re.getFoundTracks()) {
        for (int i = 1; i + 1 < t.nodes.size(); i++) {
          OsmPathElement a = t.nodes.get(i - 1);
          OsmPathElement b = t.nodes.get(i + 1);
          Assert.assertFalse("out-and-back spur at index " + i + " (seed " + seed + ")",
            a.getILon() == b.getILon() && a.getILat() == b.getILat());
        }
      }
    }
  }

  @Test
  public void returnedLoopsAlwaysRespectBandEvenWithSurfaceBias() {
    // Strong surface bias can inflate a near-out-and-back's fwd+back length into the band while
    // its real (trimmed) length is far short. Whatever is returned MUST be inside the band; if no
    // in-band loop exists, the engine returns none rather than a much-too-short route.
    int d = 3000;
    double tol = 0.10;
    double lo = d * (1.0 - tol);
    double hi = d * (1.0 + tol);
    for (long seed = 1; seed <= 6; seed++) {
      RoutingContext rctx = new RoutingContext();
      rctx.loopDistance = d;
      rctx.loopTolerance = tol;
      rctx.loopMaxResults = 2;
      rctx.loopSurface = "path";
      rctx.loopWeightSurface = 2.0;
      rctx.loopRandomness = 0.5;
      rctx.loopSeed = seed;
      RoutingEngine re = runLoop(8.720897, 50.002515, "loopband", rctx);
      List<OsmTrack> loops = re.getFoundTracks();
      if (loops == null) {
        continue; // acceptable: no in-band loop for this constraint
      }
      for (OsmTrack t : loops) {
        Assert.assertTrue("returned loop " + t.distance + " outside band [" + lo + "," + hi + "] (seed " + seed + ")",
          t.distance >= lo && t.distance <= hi);
      }
    }
  }

  private String loopSignature(double randomness, Long seed) {
    RoutingContext rctx = new RoutingContext();
    rctx.loopDistance = 3000;
    rctx.loopTolerance = 0.30;
    rctx.loopMaxResults = 1;
    rctx.loopRandomness = randomness;
    rctx.loopSeed = seed;
    RoutingEngine re = runLoop(8.720897, 50.002515, "looprnd", rctx);
    OsmTrack t = re.getFoundTrack();
    return t == null ? "null" : (t.distance + "/" + t.nodes.size());
  }

  private RoutingEngine runLoop(double lon, double lat, String trackname, RoutingContext rctx) {
    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "start";
    n.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    wplist.add(n);

    rctx.localFunction = wd + "/../../../../misc/profiles2/trekking.brf";

    RoutingEngine re = new RoutingEngine(
      wd + "/" + trackname,
      null,
      new File(wd, "/../../../../brouter-map-creator/build/resources/test/tmp/segments"),
      wplist,
      rctx,
      RoutingEngine.BROUTER_ENGINEMODE_LOOP);

    re.doRun(0);
    return re;
  }
}
