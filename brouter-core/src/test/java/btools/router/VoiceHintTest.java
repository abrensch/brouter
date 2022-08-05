package btools.router;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import btools.mapaccess.OsmNode;
import btools.util.CheapRuler;

@RunWith(Parameterized.class)
public class VoiceHintTest {
  @Parameterized.Parameter()
  public double startLon;
  @Parameterized.Parameter(1)
  public double startLat;
  @Parameterized.Parameter(2)
  public double endLon;
  @Parameterized.Parameter(3)
  public double endLat;
  @Parameterized.Parameter(4)
  public String action;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
      {8.706193, 50.003673, 8.706000, 50.003905, "1"}, // straight
      {8.705796, 50.003124, 8.706185, 50.003705, "4"}, // left
      {8.706496, 50.003643, 8.706177, 50.003724, "7"}, // right
    });
  }

  static int toOsmLon(double lon) {
    return (int) ((lon + 180.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  static int toOsmLat(double lat) {
    return (int) ((lat + 90.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  @Test
  public void Locus() {
    URL segmentUrl = this.getClass().getResource("/E5_N45.rd5");
    assertThat(segmentUrl, is(notNullValue()));
    File segmentFile = new File(segmentUrl.getFile());
    File segmentDir = segmentFile.getParentFile();

    RoutingContext routingContext = new RoutingContext();
    routingContext.localFunction = "../misc/profiles2/trekking.brf";
    routingContext.turnInstructionMode = 2;
    List<OsmNodeNamed> waypoints = Arrays.asList(
      new OsmNodeNamed(new OsmNode(toOsmLon(startLon), toOsmLat(startLat))),
      new OsmNodeNamed(new OsmNode(toOsmLon(endLon), toOsmLat(endLat)))
    );
    RoutingEngine routingEngine = new RoutingEngine(null, null, segmentDir, waypoints, routingContext);
    routingEngine.quite = true;
    routingEngine.doRun(0);

    assertThat(routingEngine.getErrorMessage(), is(nullValue()));

    OsmTrack track = routingEngine.getFoundTrack();
    String gpx = track.formatAsGpx();

    assertThat(gpx, containsString("<locus:rtePointAction>" + action + "</locus:rtePointAction>"));
  }
}
