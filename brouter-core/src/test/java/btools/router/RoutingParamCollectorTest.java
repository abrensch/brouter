package btools.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.Test;

public class RoutingParamCollectorTest {

  /** roundTripSteerVias is the opt-in request flag that activates GREEDY via-steering (default off). */
  @Test
  public void parsesRoundTripSteerViasFlag() {
    RoutingParamCollector collector = new RoutingParamCollector();

    RoutingContext on = new RoutingContext();
    collector.setParams(on, new ArrayList<>(), Collections.singletonMap("roundTripSteerVias", "1"));
    assertTrue("roundTripSteerVias=1 turns steering on", on.roundTripSteerVias);

    RoutingContext off = new RoutingContext();
    collector.setParams(off, new ArrayList<>(), Collections.singletonMap("roundTripSteerVias", "0"));
    assertFalse("roundTripSteerVias=0 leaves steering off", off.roundTripSteerVias);
  }
}
