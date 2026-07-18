package btools.router.roundtrip;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import btools.router.OsmPathElement;
import btools.router.OsmTrack;

/**
 * The post-routing ring-retry trigger: a directional lobe that placed but did
 * not route into a real loop must be retried as an encircling ring.
 */
public class FastStrategyTest {

  private static OsmTrack track(int nodes, int distance) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    for (int i = 0; i < nodes; i++) {
      t.nodes.add(OsmPathElement.create(1000 * i, 1000 * i, (short) 0, null));
    }
    t.distance = distance;
    return t;
  }

  @Test
  public void degenerateOutcomeTriggersOnMissingOrStubTracks() {
    RoundTripRequest request = new RoundTripRequest(null);

    request.track = null;
    Assert.assertTrue("no track routes -> retry", FastStrategy.degenerateOutcome(request));

    request.track = track(3, 5000);
    Assert.assertTrue("below the node floor -> retry", FastStrategy.degenerateOutcome(request));

    request.track = track(20, 150);
    Assert.assertTrue("below the length floor -> retry", FastStrategy.degenerateOutcome(request));
  }

  @Test
  public void degenerateOutcomeAcceptsARealLoop() {
    RoundTripRequest request = new RoundTripRequest(null);
    request.track = track(20, 5000);
    Assert.assertFalse(FastStrategy.degenerateOutcome(request));
  }
}
