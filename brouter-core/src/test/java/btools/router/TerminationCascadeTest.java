package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/**
 * Termination must cascade to child engines: server pre-emption reclaims the
 * core an AUTO request is burning inside an isolated child.
 */
public class TerminationCascadeTest {

  private static RoutingEngine build() {
    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = 188720000;
    start.ilat = 140000000;
    start.name = "from";
    wps.add(start);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    return new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
  }

  @Test
  public void terminateRunsRegisteredHooks() {
    RoutingEngine engine = build();
    AtomicInteger fired = new AtomicInteger();
    engine.roundTripOps().addTerminationHook(fired::incrementAndGet);
    engine.roundTripOps().addTerminationHook(fired::incrementAndGet);
    Assert.assertEquals(0, fired.get());
    engine.terminate();
    Assert.assertTrue(engine.isTerminated());
    Assert.assertEquals("both hooks must fire on terminate", 2, fired.get());
  }

  @Test
  public void lateRegistrationFiresImmediately() {
    RoutingEngine engine = build();
    engine.terminate();
    AtomicInteger fired = new AtomicInteger();
    // Closes the race: pre-emption lands while the child engine is still
    // being constructed — the hook must still kill it.
    engine.roundTripOps().addTerminationHook(fired::incrementAndGet);
    Assert.assertEquals(1, fired.get());
  }

}
