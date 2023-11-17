package btools.server.request;

import java.util.Map;

import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.server.ServiceContext;

public abstract class RequestHandler {
  protected ServiceContext serviceContext;
  protected Map<String, String> params;

  public RequestHandler(ServiceContext serviceContext, Map<String, String> params) {
    this.serviceContext = serviceContext;
    this.params = params;
  }

  public abstract RoutingContext readRoutingContext();

  public abstract String formatTrack(OsmTrack track);

  public abstract String getMimeType();

  public abstract String getFileName();
}
