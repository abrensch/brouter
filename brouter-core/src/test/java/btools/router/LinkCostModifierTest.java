package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the turncost of a way as a selector of the LinkCostModifier
 * (RoutingContext.getLinkCostModifier).
 *
 * One short track (689 m) is routed several times with a copy of shortest.brf
 * (cost 1 per metre), only the turncost of the copy differs. Every test compares
 * the cost of the track with the cost at turncost 0:
 * - a positive turncost still adds the turn angle penalty,
 * - a negative turncost without a modifier (-12) only switches the penalty off,
 * - -10 selects a modifier with factor 1, the cost does not change,
 * - -11 selects a modifier with factor 0.5, the cost is halved.
 * The track itself (its distance) must stay the same.
 */
public class LinkCostModifierTest {
  @Rule
  public TemporaryFolder profileDir = new TemporaryFolder();

  private File workingDir;

  @Before
  public void before() {
    URL resulturl = this.getClass().getResource("/testtrack0.gpx");
    Assert.assertNotNull("reference result not found: ", resulturl);
    File resultfile = new File(resulturl.getFile());
    workingDir = resultfile.getParentFile();
  }

  @Test
  public void negativeTurncostWithoutModifierKeepsCost() throws IOException {
    OsmTrack track0 = calcRoute("0");
    OsmTrack track = calcRoute("-12");
    Assert.assertEquals(track0.distance, track.distance);
    Assert.assertEquals(track0.cost, track.cost);
  }

  @Test
  public void constantModifierKeepsCost() throws IOException {
    OsmTrack track0 = calcRoute("0");
    OsmTrack track = calcRoute("-10");
    Assert.assertEquals(track0.distance, track.distance);
    Assert.assertEquals(track0.cost, track.cost);
  }

  @Test
  public void constantModifierHalvesCost() throws IOException {
    OsmTrack track0 = calcRoute("0");
    OsmTrack track = calcRoute("-11");
    Assert.assertEquals(track0.distance, track.distance);
    // every section cost is rounded down to int after the factor
    int sections = track.nodes.size() - 1;
    Assert.assertTrue("cost " + track.cost + " vs. " + track0.cost,
      track.cost <= track0.cost / 2 && track.cost >= track0.cost / 2 - sections);
  }

  @Test
  public void positiveTurncostAddsCost() throws IOException {
    OsmTrack track0 = calcRoute("0");
    OsmTrack track = calcRoute("90");
    Assert.assertTrue("cost " + track.cost + " vs. " + track0.cost, track.cost > track0.cost);
  }

  // the route of RoutingEngineTest.routeCrossingSegmentBorder, profile shortest.brf
  // with another turncost
  private OsmTrack calcRoute(String turncost) throws IOException {
    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    wplist.add(createNode("from", 8.720897, 50.002515));
    wplist.add(createNode("to", 8.723658, 49.997510));

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = createProfile(new File(wd, "/../../../../misc/profiles2"), turncost).getPath();

    RoutingEngine re = new RoutingEngine(
      null,
      null,
      new File(wd, "/../../../../brouter-map-creator/build/resources/test/tmp/segments"),
      wplist,
      rctx);

    re.doRun(0);

    Assert.assertNull("routing failed: " + re.getErrorMessage(), re.getErrorMessage());
    return re.getFoundTrack();
  }

  // the profile name contains turncost, ProfileCache knows a profile by name and timestamp
  private File createProfile(File profiles2, String turncost) throws IOException {
    File lookups = new File(profileDir.getRoot(), "lookups.dat");
    if (!lookups.exists()) {
      Files.copy(new File(profiles2, "lookups.dat").toPath(), lookups.toPath());
    }
    String profile = new String(Files.readAllBytes(new File(profiles2, "shortest.brf").toPath()), StandardCharsets.UTF_8);
    Assert.assertTrue(profile.contains("assign turncost 0"));
    File file = new File(profileDir.getRoot(), "turncost" + turncost + ".brf");
    Files.write(file.toPath(), profile.replace("assign turncost 0", "assign turncost " + turncost).getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static OsmNodeNamed createNode(String name, double lon, double lat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    return n;
  }
}
