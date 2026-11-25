package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class RoutingEngineTest {
  private File workingDir;

  @Before
  public void before() {
    URL resulturl = this.getClass().getResource("/testtrack0.gpx");
    Assert.assertNotNull("reference result not found: ", resulturl);
    File resultfile = new File(resulturl.getFile());
    workingDir = resultfile.getParentFile();
  }

  @Test
  public void routeCrossingSegmentBorder() {
    String msg = calcRoute(8.720897, 50.002515, 8.723658, 49.997510, "testtrack", new RoutingContext());
    // error message from router?
    Assert.assertNull("routing failed: " + msg, msg);

    // if the track didn't change, we expect the first alternative also
    File a1 = new File(workingDir, "testtrack1.gpx");
    a1.deleteOnExit();
    Assert.assertTrue("result content mismatch", a1.exists());
  }

  @Test
  public void routeDestinationPointFarOff() {
    String msg = calcRoute(8.720897, 50.002515, 16.723658, 49.997510, "notrack", new RoutingContext());
    Assert.assertTrue(msg, msg != null && msg.contains("not found"));
  }

  // check that a (short) route and an alternative route can be computed
  // while explicitely overriding a routing profile parameter
  @Test
  public void overrideParam() {
    // 1st route computing (with param)
    RoutingContext rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    String msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 1st route): " + msg, msg);
    // 2nd route computing (same from/to & same param)
    rctx = new RoutingContext();
    rctx.keyValues = new HashMap<>();
    rctx.keyValues.put("avoid_unsafe", "1.0");
    msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "paramTrack", rctx);
    Assert.assertNull("routing failed (paramTrack 2nd route): " + msg, msg);

    File trackFile = new File(workingDir, "paramTrack0.gpx");
    trackFile.deleteOnExit();
    trackFile = new File(workingDir, "paramTrack1.gpx");
    trackFile.deleteOnExit();
    // checks if a gpx file has been created for the alternative route
    Assert.assertTrue("result content mismatch", trackFile.exists());
  }

  // a cell decoded in one pass is woven from its recording in the next:
  // the route and its alternative must not depend on that
  @Test
  public void wovenCellsGiveTheSameRoute() throws IOException {
    String msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "wovenTrack", new RoutingContext());
    Assert.assertNull("routing failed (cells kept): " + msg, msg);
    msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "wovenTrack", new RoutingContext());
    Assert.assertNull("routing failed (cells kept, alternative): " + msg, msg);

    System.setProperty("disableWovenCells", "true");
    try {
      msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "decodedTrack", new RoutingContext());
      Assert.assertNull("routing failed (cells decoded): " + msg, msg);
      msg = calcRoute(8.723037, 50.000491, 8.712737, 50.002899, "decodedTrack", new RoutingContext());
      Assert.assertNull("routing failed (cells decoded, alternative): " + msg, msg);
    } finally {
      System.clearProperty("disableWovenCells");
    }

    for (int alternative = 0; alternative <= 1; alternative++) {
      File woven = new File(workingDir, "wovenTrack" + alternative + ".gpx");
      File decoded = new File(workingDir, "decodedTrack" + alternative + ".gpx");
      woven.deleteOnExit();
      decoded.deleteOnExit();
      String expected = new String(Files.readAllBytes(decoded.toPath()), StandardCharsets.UTF_8);
      Assert.assertTrue("no track points in " + decoded, expected.contains("<trkpt"));
      Assert.assertEquals("alternative " + alternative + " differs",
        expected, new String(Files.readAllBytes(woven.toPath()), StandardCharsets.UTF_8));
    }
  }

  private String calcRoute(double flon, double flat, double tlon, double tlat, String trackname, RoutingContext rctx) {
    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<>();
    OsmNodeNamed n;
    n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 + (int) (flon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (flat * 1000000 + 0.5);
    wplist.add(n);

    n = new OsmNodeNamed();
    n.name = "to";
    n.ilon = 180000000 + (int) (tlon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (tlat * 1000000 + 0.5);
    wplist.add(n);

    rctx.localFunction = wd + "/../../../../misc/profiles2/trekking.brf";

    RoutingEngine re = new RoutingEngine(
      wd + "/" + trackname,
      wd + "/" + trackname,
      new File(wd, "/../../../../brouter-map-creator/build/resources/test/tmp/segments"),
      wplist,
      rctx);

    re.doRun(0);

    return re.getErrorMessage();
  }

}
