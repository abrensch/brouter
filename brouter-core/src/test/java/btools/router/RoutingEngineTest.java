package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
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
    String msg = calcRoute(8.720897, 50.002515, 8.723658, 49.997510, "testtrack");
    // error message from router?
    Assert.assertNull("routing failed: " + msg, msg);

    // if the track didn't change, we expect the first alternative also
    File a1 = new File(workingDir, "testtrack1.gpx");
    Assert.assertTrue("result content missmatch", a1.exists());
  }

  @Test
  public void routeDestinationPointFarOff() {
    String msg = calcRoute(8.720897, 50.002515, 16.723658, 49.997510, "notrack");
    Assert.assertTrue(msg, msg != null && msg.contains("not found"));
  }

  private String calcRoute(double flon, double flat, double tlon, double tlat, String trackname) {
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

    RoutingContext rctx = new RoutingContext();
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
