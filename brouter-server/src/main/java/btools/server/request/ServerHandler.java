package btools.server.request;

import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.server.ServiceContext;
import java.io.BufferedWriter;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * URL query parameter handler for web and standalone server. Supports all 
 * BRouter features without restrictions.
 * 
 * Parameters:
 * 
 * lonlats = lon,lat|... (unlimited list of lon,lat waypoints separated by |)
 * nogos = lon,lat,radius|... (optional, radius in meters)
 * profile = profile file name without .brf
 * alternativeidx = [0|1|2|3] (optional, default 0)
 * format = [kml|gpx|geojson] (optional, default gpx)
 *
 * Example URLs:
 * http://localhost:17777/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&nogos=&profile=trekking&alternativeidx=0&format=gpx
 * http://localhost:17777/brouter?lonlats=1.1,1.2|2.1,2.2|3.1,3.2|4.1,4.2&nogos=-1.1,-1.2,1|-2.1,-2.2,2&profile=shortest&alternativeidx=1&format=kml
 *
 */
public class ServerHandler extends RequestHandler {

  private RoutingContext rc;

  public ServerHandler( ServiceContext serviceContext, HashMap<String, String> params )
  {
    super( serviceContext, params );
  }

  @Override
  public RoutingContext readRoutingContext()
  {
    rc = new RoutingContext();
    rc.memoryclass = 128;

    String profile = params.get( "profile" );
    // when custom profile replace prefix with directory path
    if ( profile.startsWith( ProfileUploadHandler.CUSTOM_PREFIX ) )
    {
      String customProfile = profile.substring( ProfileUploadHandler.CUSTOM_PREFIX.length() );
      profile = new File( serviceContext.customProfileDir, customProfile ).getPath();
    }
    rc.localFunction = profile;

    rc.setAlternativeIdx(Integer.parseInt(params.get( "alternativeidx" )));
    
    List<OsmNodeNamed> nogoList = readNogoList();
    List<OsmNodeNamed> nogoPolygonsList = readNogoPolygons();

    if ( nogoList != null )
    {
      RoutingContext.prepareNogoPoints( nogoList );
      rc.nogopoints = nogoList;
    }

    if (rc.nogopoints == null)
    {
      rc.nogopoints = nogoPolygonsList;
    }
    else if ( nogoPolygonsList != null )
    {
      rc.nogopoints.addAll(nogoPolygonsList);
    }

    return rc;
  }

  @Override
  public List<OsmNodeNamed> readWayPointList()
  {
    // lon,lat|...
    String lonLats = params.get( "lonlats" );
    if (lonLats == null) throw new IllegalArgumentException( "lonlats parameter not set" );

    String[] coords = lonLats.split("\\|");
    if (coords.length < 2) 
      throw new IllegalArgumentException( "we need two lat/lon points at least!" );
    
    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    for (int i = 0; i < coords.length; i++)
    {
      String[] lonLat = coords[i].split(",");
      if (lonLat.length < 2) 
        throw new IllegalArgumentException( "we need two lat/lon points at least!" );
      wplist.add( readPosition( lonLat[0], lonLat[1], "via" + i ) );
    }

    wplist.get(0).name = "from";
    wplist.get(wplist.size()-1).name = "to";

    return wplist;
  }
  
  @Override
  public String formatTrack(OsmTrack track)
  {
    String result;
    // optional, may be null
    String format = params.get( "format" );

    if (format == null || "gpx".equals(format))
    {
      result = track.formatAsGpx();
    }
    else if ("kml".equals(format))
    {
      result = track.formatAsKml();
    }
    else if ("geojson".equals(format))
    {
      result = track.formatAsGeoJson();
    }
    else if ("csv".equals(format))
    {
      try
      {
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        track.writeMessages( bw, rc );
        return sw.toString();
      }
      catch (Exception ex)
      {
        return "Error: " + ex.getMessage();
      }
    }
    else {
      System.out.println("unknown track format '" + format + "', using default");
      result = track.formatAsGpx();
    }

    return result;
  }

  @Override
  public String getMimeType()
  {
    // default
    String result = "text/plain";

    // optional, may be null
    String format = params.get( "format" );
    if ( format != null )
    {
      if ( "gpx".equals( format ) )
      {
        result = "application/gpx+xml";
      }
      else if ( "kml".equals( format ) )
      {
        result = "application/vnd.google-earth.kml+xml";
      }
      else if ( "geojson".equals( format ) )
      {
        result = "application/vnd.geo+json";
      }
      else if ( "csv".equals( format ) )
      {
        result = "text/tab-separated-values";
      }
    }
            
    return result;
  }

  @Override
  public String getFileName()
  {
    String fileName = null;
    String format = params.get( "format" );

    if ( format != null )
    {
      fileName = "brouter." + format;
    }

    return fileName;
  }

  private static OsmNodeNamed readPosition( String vlon, String vlat, String name )
  {
    if ( vlon == null ) throw new IllegalArgumentException( "lon " + name + " not found in input" );
    if ( vlat == null ) throw new IllegalArgumentException( "lat " + name + " not found in input" );
    
    return readPosition(Double.parseDouble( vlon ), Double.parseDouble( vlat ), name);
  }
  
  private static OsmNodeNamed readPosition( double lon, double lat, String name )
  {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int)( ( lon + 180. ) *1000000. + 0.5);
    n.ilat = (int)( ( lat +  90. ) *1000000. + 0.5);
    return n;
  }
  
  private List<OsmNodeNamed> readNogoList()
  {
    // lon,lat,radius|...
    String nogos = params.get( "nogos" );
    if ( nogos == null ) return null;

    String[] lonLatRadList = nogos.split("\\|");

    List<OsmNodeNamed> nogoList = new ArrayList<OsmNodeNamed>();
    for (int i = 0; i < lonLatRadList.length; i++)
    {
      String[] lonLatRad = lonLatRadList[i].split(",");
      nogoList.add(readNogo(lonLatRad[0], lonLatRad[1], lonLatRad[2]));
    }

    return nogoList;
  }
  
  private static OsmNodeNamed readNogo( String lon, String lat, String radius )
  {
    return readNogo(Double.parseDouble( lon ), Double.parseDouble( lat ), Integer.parseInt( radius ) );
  }

  private static OsmNodeNamed readNogo( double lon, double lat, int radius )
  {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "nogo" + radius;
    n.ilon = (int)( ( lon + 180. ) *1000000. + 0.5);
    n.ilat = (int)( ( lat +  90. ) *1000000. + 0.5);
    n.isNogo = true;
    return n;
  }

  private List<OsmNodeNamed> readNogoPolygons()
  {
    String polygons = params.get( "polygons" );
    if ( polygons == null ) return null;

    String[] polygonList = polygons.split("\\|");

    List<OsmNodeNamed> nogoPolygonList = new ArrayList<OsmNodeNamed>();
    for (int i = 0; i < polygonList.length; i++)
    {
      String[] lonLatList = polygonList[i].split(",");
      if ( lonLatList.length > 1 )
      {
        OsmNogoPolygon polygon = new OsmNogoPolygon();
        for (int j = 0; j < lonLatList.length-1;)
        {
          String slon = lonLatList[j++];
          String slat = lonLatList[j++];
          int lon = (int)( ( Double.parseDouble(slon) + 180. ) *1000000. + 0.5);
          int lat = (int)( ( Double.parseDouble(slat) +  90. ) *1000000. + 0.5);
          polygon.addVertex(lon, lat);
        }
        if ( polygon.points.size() > 0 )
        {
          polygon.name = "";
          polygon.isNogo = true;
          polygon.calcBoundingCircle();
          nogoPolygonList.add(polygon);
        }
      }
    }
    return nogoPolygonList;
  }
}
