package btools.router;

import org.junit.Assert;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RouteParamTest {

  @Test(expected = IllegalArgumentException.class)
  public void readWptsNull() {

    RoutingParamCollector rpc = new RoutingParamCollector();
    List<OsmNodeNamed> map = rpc.getWayPointList(null);

    Assert.assertEquals("result content null", 0, map.size());

  }

  @Test
  public void readWpts() {
    String data = "1.0,1.2;2.0,2.2";
    RoutingParamCollector rpc = new RoutingParamCollector();
    List<OsmNodeNamed> map = rpc.getWayPointList(data);

    Assert.assertEquals("result content 1 ", 2, map.size());

    data = "1.0,1.1|2.0,2.2|3.0,3.3";
    map = rpc.getWayPointList(data);

    Assert.assertEquals("result content 2 ", 3, map.size());

    data = "1.0,1.2,Name;2.0,2.2";
    map = rpc.getWayPointList(data);

    Assert.assertEquals("result content 3 ", "Name", map.get(0).name);

    data = "1.0,1.2,d;2.0,2.2";
    map = rpc.getWayPointList(data);

    Assert.assertEquals("result content 4 ", map.get(0).wpttype, 3); // 3 = MatchedWaypoint.WAYPOINT_TYPE_DIRECT

    data = "1.0,1.2,m;2.0,2.2";
    map = rpc.getWayPointList(data);

    Assert.assertEquals("result content 4 ", map.get(0).wpttype, 2); // 2 = MatchedWaypoint.WAYPOINT_TYPE_MEETING
  }

  @Test
  public void readUrlParams() throws UnsupportedEncodingException {
    String url = "lonlats=1,1;2,2&profile=test&more=1";
    RoutingParamCollector rpc = new RoutingParamCollector();
    Map<String, String> map = rpc.getUrlParams(url);

    Assert.assertEquals("result content ", 3, map.size());
  }

  @Test
  public void roundTripSeedSemantics() {
    // ADR-0001: alternativeidx is a variety seed in round-trip mode — raw value
    // with a lower clamp at 0 — while classic routing keeps the 0–3 clamp.
    RoutingContext rc = new RoutingContext();
    Assert.assertEquals("absent → seed 0 (inert baseline)", 0, rc.getRoundTripSeed());

    rc.setAlternativeIdx(7);
    Assert.assertEquals("round-trip mode reads the raw value", 7, rc.getRoundTripSeed());
    Assert.assertEquals("classic-routing clamp unchanged", 3, rc.getAlternativeIdx(0, 3));

    rc.setAlternativeIdx(-2);
    Assert.assertEquals("negative clamps to 0 (= no jitter)", 0, rc.getRoundTripSeed());
  }

  @Test
  public void readParamsFromList() throws UnsupportedEncodingException {
    Map<String, String> params = new HashMap<>();
    params.put("timode", "3");
    RoutingContext rc = new RoutingContext();
    RoutingParamCollector rpc = new RoutingParamCollector();
    rpc.setParams(rc, null, params);

    Assert.assertEquals("result content timode ", 3, rc.turnInstructionMode);
  }

  @Test
  public void roundTripDesirabilityParsesIntoContextAndPropagatesToChildren()
      throws UnsupportedEncodingException {
    // Default: off when the parameter is absent.
    RoutingContext bare = new RoutingContext();
    new RoutingParamCollector().setParams(bare, null, new HashMap<>());
    Assert.assertFalse("roundTripDesirability defaults to off", bare.roundTripDesirability);

    // roundTripDesirability=1 turns it on.
    Map<String, String> params = new HashMap<>();
    params.put("roundTripDesirability", "1");
    RoutingContext rc = new RoutingContext();
    new RoutingParamCollector().setParams(rc, null, params);
    Assert.assertTrue("roundTripDesirability=1 enables the flag", rc.roundTripDesirability);

    // It must follow the parent into the AUTO-spawned child context, where it takes effect.
    RoutingContext child = rc.copyRequestFields();
    Assert.assertTrue("roundTripDesirability is copied into child contexts", child.roundTripDesirability);

    // Any non-1 value leaves it off.
    Map<String, String> zero = new HashMap<>();
    zero.put("roundTripDesirability", "0");
    RoutingContext off = new RoutingContext();
    new RoutingParamCollector().setParams(off, null, zero);
    Assert.assertFalse("roundTripDesirability=0 keeps the flag off", off.roundTripDesirability);
  }

}
