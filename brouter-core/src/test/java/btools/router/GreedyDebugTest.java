package btools.router;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GreedyDebugTest {
  @Test
  public void debugGreedySingleStep() {
    File segDir = new File("../segments4");
    File profileFile = new File("../misc/profiles2/fastbike.brf");
    Assume.assumeTrue("needs segments", segDir.exists());

    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = (int) ((8.720 + 180) * 1e6);
    start.ilat = (int) ((50.000 + 90) * 1e6);
    start.name = "from";

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.startDirection = 0;
    rctx.roundTripDistance = 8000;
    rctx.roundTripAlgorithm = RoundTripAlgorithm.GREEDY;

    List<OsmNodeNamed> wplist = new ArrayList<>();
    wplist.add(start);

    RoutingEngine re = new RoutingEngine(null, null, segDir, wplist, rctx,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);

    try {
      rctx.useDynamicDistance = true;
      re.resetCache(false);

      GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(re);
      RoundTripResult result = planner.plan(start, 2 * Math.PI * 8000, 0);
      System.out.println("Result track: " + (result.getTrack() != null ? "dist=" + result.getTrack().distance : "null"));
      System.out.println("Diagnostics: " + result.getDiagnostics());
      System.out.println("Fallback: " + result.getFallbackReason());
    } catch (Exception e) {
      e.printStackTrace(System.out);
    }
  }
}
