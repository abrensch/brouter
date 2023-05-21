package btools.server.request;

import java.io.BufferedWriter;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
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
 * nogos = lon,lat,radius,weight|... (optional, list of lon, lat, radius in meters, weight (optional) separated by |)
 * polylines = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
 * polygons        = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
 * profile = profile file name without .brf
 * alternativeidx = [0|1|2|3] (optional, default 0)
 * format = [kml|gpx|geojson] (optional, default gpx)
 * trackname = name used for filename and format specific trackname (optional, default brouter)
 * exportWaypoints = 1 to export them (optional, default is no export)
 * pois = lon,lat,name|... (optional)
 * timode = turnInstructionMode [0=none, 1=auto-choose, 2=locus-style, 3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style] default 0
 * heading = angle (optional to give a route a start direction)
 * profile:xxx = parameter in profile (optional)
 * straight = idx1,idx2,.. (optional, minimum one value, index of a direct routing point in the waypoint list)
 * <p>
 * Example URLs:
 * {@code http://localhost:17777/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&nogos=&profile=trekking&alternativeidx=0&format=gpx}
 * {@code http://localhost:17777/brouter?lonlats=1.1,1.2|2.1,2.2|3.1,3.2|4.1,4.2&nogos=-1.1,-1.2,1|-2.1,-2.2,2&profile=shortest&alternativeidx=1&format=kml&trackname=Ride&pois=1.1,2.1,Barner Bar}
 */
public class ServerHandler extends RequestHandler {

  private RoutingContext rc;

  public ServerHandler(ServiceContext serviceContext, Map<String, String> params) {
    super(serviceContext, params);
  }

  @Override
  public RoutingContext readRoutingContext() {
    rc = new RoutingContext();
    rc.memoryclass = 128;

    String profile = params.get("profile");
    // when custom profile replace prefix with directory path
    if (profile.startsWith(ProfileUploadHandler.CUSTOM_PREFIX)) {
      String customProfile = profile.substring(ProfileUploadHandler.CUSTOM_PREFIX.length());
      profile = new File(serviceContext.customProfileDir, customProfile).getPath();
    } else if (profile.startsWith(ProfileUploadHandler.SHARED_PREFIX)) {
      String customProfile = profile.substring(ProfileUploadHandler.SHARED_PREFIX.length());
      profile = new File(serviceContext.sharedProfileDir, customProfile).getPath();
    }
    rc.localFunction = profile;

    rc.setAlternativeIdx(Integer.parseInt(params.get("alternativeidx")));

    List<OsmNodeNamed> poisList = readPoisList();
    rc.poipoints = poisList;

    List<OsmNodeNamed> nogoList = readNogoList();
    List<OsmNodeNamed> nogoPolygonsList = readNogoPolygons();

    if (nogoList != null) {
      RoutingContext.prepareNogoPoints(nogoList);
      rc.nogopoints = nogoList;
    }

    if (rc.nogopoints == null) {
      rc.nogopoints = nogoPolygonsList;
    } else if (nogoPolygonsList != null) {
      rc.nogopoints.addAll(nogoPolygonsList);
    }

    return rc;
  }

  @Override
  public List<OsmNodeNamed> readWayPointList() {
    // lon,lat|...
    String lonLats = params.get("lonlats");
    if (lonLats == null) throw new IllegalArgumentException("lonlats parameter not set");

    String[] coords = lonLats.split("\\|");
    if (coords.length < 2)
      throw new IllegalArgumentException("we need two lat/lon points at least!");

    List<OsmNodeNamed> wplist = new ArrayList<>();
    for (int i = 0; i < coords.length; i++) {
      String[] lonLat = coords[i].split(",");
      if (lonLat.length < 2)
        throw new IllegalArgumentException("we need two lat/lon points at least!");
      wplist.add(readPosition(lonLat[0], lonLat[1], "via" + i));
      if (lonLat.length > 2) {
        if (lonLat[2].equals("d")) {
          wplist.get(wplist.size()-1).direct = true;
        } else {
          wplist.get(wplist.size()-1).name = lonLat[2];
        }
      }
    }

    if (wplist.get(0).name.startsWith("via")) wplist.get(0).name = "from";
    if (wplist.get(wplist.size() - 1).name.startsWith("via")) wplist.get(wplist.size() - 1).name = "to";

    return wplist;
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
    String exportWaypointsStr = params.get("exportWaypoints");
    if (exportWaypointsStr != null && Integer.parseInt(exportWaypointsStr) != 0) {
      track.exportWaypoints = true;
    }

    if (format == null || "gpx".equals(format)) {
      result = track.formatAsGpx();
    } else if ("kml".equals(format)) {
      result = track.formatAsKml();
    } else if ("geojson".equals(format)) {
      result = track.formatAsGeoJson();
    } else if ("csv".equals(format)) {
      try {
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        track.writeMessages(bw, rc);
        return sw.toString();
      } catch (Exception ex) {
        return "Error: " + ex.getMessage();
      }
    } else {
      System.out.println("unknown track format '" + format + "', using default");
      result = track.formatAsGpx();
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
      } else if ("kml".equals(format)) {
        result = "application/vnd.google-earth.kml+xml";
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

  private static OsmNodeNamed readPosition(String vlon, String vlat, String name) {
    if (vlon == null) throw new IllegalArgumentException("lon " + name + " not found in input");
    if (vlat == null) throw new IllegalArgumentException("lat " + name + " not found in input");

    return readPosition(Double.parseDouble(vlon), Double.parseDouble(vlat), name);
  }

  private static OsmNodeNamed readPosition(double lon, double lat, String name) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.ilat = (int) ((lat + 90.) * 1000000. + 0.5);
    return n;
  }

  private List<OsmNodeNamed> readPoisList() {
    // lon,lat,name|...
    String pois = params.get("pois");
    if (pois == null) return null;

    String[] lonLatNameList = pois.split("\\|");

    List<OsmNodeNamed> poisList = new ArrayList<>();
    for (int i = 0; i < lonLatNameList.length; i++) {
      String[] lonLatName = lonLatNameList[i].split(",");

      if (lonLatName.length != 3)
        continue;

      OsmNodeNamed n = new OsmNodeNamed();
      n.ilon = (int) ((Double.parseDouble(lonLatName[0]) + 180.) * 1000000. + 0.5);
      n.ilat = (int) ((Double.parseDouble(lonLatName[1]) + 90.) * 1000000. + 0.5);
      n.name = lonLatName[2];
      poisList.add(n);
    }

    return poisList;
  }

  private List<OsmNodeNamed> readNogoList() {
    // lon,lat,radius|...
    String nogos = params.get("nogos");
    if (nogos == null) return null;

    String[] lonLatRadList = nogos.split("\\|");

    List<OsmNodeNamed> nogoList = new ArrayList<>();
    for (int i = 0; i < lonLatRadList.length; i++) {
      String[] lonLatRad = lonLatRadList[i].split(",");
      String nogoWeight = "NaN";
      if (lonLatRad.length > 3) {
        nogoWeight = lonLatRad[3];
      }
      nogoList.add(readNogo(lonLatRad[0], lonLatRad[1], lonLatRad[2], nogoWeight));
    }

    return nogoList;
  }

  private static OsmNodeNamed readNogo(String lon, String lat, String radius, String nogoWeight) {
    double weight = "undefined".equals(nogoWeight) ? Double.NaN : Double.parseDouble(nogoWeight);
    return readNogo(Double.parseDouble(lon), Double.parseDouble(lat), Integer.parseInt(radius), weight);
  }

  private static OsmNodeNamed readNogo(double lon, double lat, int radius, double nogoWeight) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = "nogo" + radius;
    n.ilon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.ilat = (int) ((lat + 90.) * 1000000. + 0.5);
    n.isNogo = true;
    n.nogoWeight = nogoWeight;
    return n;
  }

  private List<OsmNodeNamed> readNogoPolygons() {
    List<OsmNodeNamed> result = new ArrayList<>();
    parseNogoPolygons(params.get("polylines"), result, false);
    parseNogoPolygons(params.get("polygons"), result, true);
    return result.size() > 0 ? result : null;
  }

  private static void parseNogoPolygons(String polygons, List<OsmNodeNamed> result, boolean closed) {
    if (polygons != null) {
      String[] polygonList = polygons.split("\\|");
      for (int i = 0; i < polygonList.length; i++) {
        String[] lonLatList = polygonList[i].split(",");
        if (lonLatList.length > 1) {
          OsmNogoPolygon polygon = new OsmNogoPolygon(closed);
          int j;
          for (j = 0; j < 2 * (lonLatList.length / 2) - 1; ) {
            String slon = lonLatList[j++];
            String slat = lonLatList[j++];
            int lon = (int) ((Double.parseDouble(slon) + 180.) * 1000000. + 0.5);
            int lat = (int) ((Double.parseDouble(slat) + 90.) * 1000000. + 0.5);
            polygon.addVertex(lon, lat);
          }

          String nogoWeight = "NaN";
          if (j < lonLatList.length) {
            nogoWeight = lonLatList[j];
          }
          polygon.nogoWeight = Double.parseDouble(nogoWeight);

          if (polygon.points.size() > 0) {
            polygon.calcBoundingCircle();
            result.add(polygon);
          }
        }
      }
    }
  }
}
