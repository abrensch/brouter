package btools.router;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import btools.mapaccess.OsmNode;
import btools.util.CheapRuler;

public class VoiceHintTest {
  static int toOsmLon(double lon) {
    return (int) ((lon + 180.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  static int toOsmLat(double lat) {
    return (int) ((lat + 90.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  @Test
  public void Locus() {
    RoutingContext routingContext = new RoutingContext();
    routingContext.localFunction = "../misc/profiles2/trekking.brf";
    routingContext.turnInstructionMode = 2;
    // https://brouter.de/brouter-web/#map=17/50.00408/8.70788/standard&lonlats=8.705796,50.003124;8.705859,50.003959
    List<OsmNodeNamed> waypoints = Arrays.asList(
      new OsmNodeNamed(new OsmNode(toOsmLon(8.705796), toOsmLat(50.003124))),
      new OsmNodeNamed(new OsmNode(toOsmLon(8.705859), toOsmLat(50.003959)))
    );
    RoutingEngine routingEngine = new RoutingEngine(null, null, new File("../brouter-map-creator/build/resources/test/tmp/segments/"), waypoints, routingContext);
    routingEngine.doRun(0);

    assertThat(routingEngine.getErrorMessage(), is(nullValue()));

    OsmTrack track = routingEngine.getFoundTrack();
    String gpx = track.formatAsGpx();

    assertThat(gpx, containsString("<locus:rtePointAction>4</locus:rtePointAction>")); // left turn
    assertThat(gpx, containsString("<locus:rtePointAction>1</locus:rtePointAction>")); // straight
  }
}
