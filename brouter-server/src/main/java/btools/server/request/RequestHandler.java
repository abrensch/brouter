package btools.server.request;

import java.util.HashMap;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.server.ServiceContext;

public abstract class RequestHandler
{
	protected ServiceContext serviceContext;
	protected HashMap<String,String> params;

	public RequestHandler( ServiceContext serviceContext, HashMap<String,String> params )
	{
		this.serviceContext = serviceContext;
		this.params = params;
	}

	public abstract RoutingContext readRoutingContext();

	public abstract List<OsmNodeNamed> readWayPointList();

	public abstract String formatTrack(OsmTrack track);

  public abstract String getMimeType();

  public abstract String getFileName();
}