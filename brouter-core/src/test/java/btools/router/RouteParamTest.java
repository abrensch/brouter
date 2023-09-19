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

    Assert.assertTrue("result content 4 ", map.get(0).direct);
  }

  @Test
  public void readUrlParams() throws UnsupportedEncodingException {
    String url = "lonlats=1,1;2,2&profile=test&more=1";
    RoutingParamCollector rpc = new RoutingParamCollector();
    Map<String, String> map = rpc.getUrlParams(url);

    Assert.assertEquals("result content ", 3, map.size());
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

}
