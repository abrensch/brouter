package btools.server.request;

import java.util.Map;

import btools.router.FormatGpx;
import btools.router.FormatJson;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.server.ServiceContext;

/**
 * URL query parameter handler for web and standalone server. Supports all
 * BRouter features without restrictions.
 * <p>
 * Parameters:
 * <p>
 * lonlats = lon,lat|... (unlimited list of lon,lat waypoints separated by |)
 * nogos = lon,lat,radius|... (optional, list of lon, lat, radius in meters separated by |)
 * profile = profile file name without .brf
 * alternativeidx = [0|1|2|3] (optional, default 0)
 * format = [gpx|geojson] (optional, default gpx)
 * trackname = name used for filename and format specific trackname (optional, default brouter)
 * pois = lon,lat,name|... (optional)
 * timode = turnInstructionMode [0=none, 1=auto-choose, 2=locus-style, 3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style] default 0
 * profile:xxx = parameter in profile (optional)
 * <p>
 * Example URLs:
 * {@code http://localhost:17777/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&nogos=&profile=trekking&alternativeidx=0&format=gpx}
 * {@code http://localhost:17777/brouter?lonlats=1.1,1.2|2.1,2.2|3.1,3.2|4.1,4.2&nogos=-1.1,-1.2,1|-2.1,-2.2,2&profile=shortest&alternativeidx=1&format=gpx&trackname=Ride&pois=1.1,2.1,Barner Bar}
 */
public class ServerHandler extends RequestHandler {

  private RoutingContext rc;

  public ServerHandler(ServiceContext serviceContext, Map<String, String> params) {
    super(serviceContext, params);
  }

  @Override
  public String formatTrack(OsmTrack track) {
    String result;
    // optional, may be null
    String format = params.get("format");
    String trackName = getTrackName();
    if (trackName != null) {
      track.name = trackName;
    }

    if (format == null || "gpx".equals(format)) {
      result = new FormatGpx(rc).format(track);
    } else if ("geojson".equals(format)) {
      result = new FormatJson(rc).format(track);
    } else {
      System.out.println("unknown track format '" + format + "', using default");
      result = new FormatGpx(rc).format(track);
    }

    return result;
  }

  @Override
  public String getMimeType() {
    // default
    String result = "text/plain";

    // optional, may be null
    String format = params.get("format");
    if (format != null) {
      if ("gpx".equals(format)) {
        result = "application/gpx+xml";
      } else if ("geojson".equals(format)) {
        result = "application/vnd.geo+json";
      } else if ("csv".equals(format)) {
        result = "text/tab-separated-values";
      }
    }

    return result;
  }

  @Override
  public String getFileName() {
    String fileName = null;
    String format = params.get("format");
    String trackName = getTrackName();

    if (format != null) {
      fileName = (trackName == null ? "brouter" : trackName) + "." + format;
    }

    return fileName;
  }

  private String getTrackName() {
    return params.get("trackname") == null ? null : params.get("trackname").replaceAll("[^a-zA-Z0-9 \\._\\-]+", "");
  }

}
