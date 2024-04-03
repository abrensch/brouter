package btools.server;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import btools.router.RoutingContext;
import btools.server.request.ServerHandler;

public class RequestHandlerTest {
  @Test
  @Ignore("Parameters are currently handled by RouteServer, not RequestHandler")
  public void parseParameters() {
    Map<String, String> params = new HashMap<>();
    params.put("lonlats", "8.799297,49.565883|8.811764,49.563606");
    params.put("profile", "trekking");
    params.put("alternativeidx", "0");
    params.put("profile:test", "bar");
    ServerHandler serverHandler = new ServerHandler(null, params);
    RoutingContext routingContext = serverHandler.readRoutingContext();

    Assert.assertEquals("trekking", routingContext.localFunction);
    Assert.assertEquals("bar", routingContext.keyValues.get("test"));
  }
}
