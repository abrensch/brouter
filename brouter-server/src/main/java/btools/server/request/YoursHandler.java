package btools.server.request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.server.ServiceContext;

public class YoursHandler extends RequestHandler {

	public YoursHandler( ServiceContext serviceContext, HashMap<String,String> params )
	{
		super(serviceContext, params);
	}

	@Override
	public RoutingContext readRoutingContext()
	{
    RoutingContext rc = new RoutingContext();

    String profile_key = params.get( "v" ) + " " + params.get( "fast" );
    if (serviceContext.profileMap == null) throw new IllegalArgumentException( "no profile map loaded" );
    String profile_path = serviceContext.profileMap.get( profile_key );
    if ( profile_path == null ) profile_path = serviceContext.profileMap.get( "default" );
    if ( profile_path == null ) throw new IllegalArgumentException( "no profile for key: " + profile_key );
    rc.localFunction = profile_path;
    
    List<OsmNodeNamed> nogoList = serviceContext.nogoList;
    if ( nogoList != null )
    {
      rc.prepareNogoPoints( nogoList );
      rc.nogopoints = nogoList;
    }

    return rc;
	}

	@Override
	public List<OsmNodeNamed> readWayPointList()
	{
    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    wplist.add( readPosition( params, "flon", "flat", "from" ) );
    wplist.add( readPosition( params, "tlon", "tlat", "to" ) );
    return wplist;
	}
	
	@Override
	public String formatTrack(OsmTrack track)
	{
		return track.formatAsKml();
	}
	
  private static OsmNodeNamed readPosition( HashMap<String,String> params, String plon, String plat, String name )
  {
        String vlon = params.get( plon );
        if ( vlon == null ) throw new IllegalArgumentException( "param " + plon + " bot found in input" );
        String vlat = params.get( plat );
        if ( vlat == null ) throw new IllegalArgumentException( "param " + plat + " bot found in input" );

    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int)( ( Double.parseDouble( vlon ) + 180. ) *1000000. + 0.5);
    n.ilat = (int)( ( Double.parseDouble( vlat ) +  90. ) *1000000. + 0.5);
    return n;
  }
}
