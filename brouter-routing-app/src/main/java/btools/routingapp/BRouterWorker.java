package btools.routingapp;


import android.os.Bundle;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;

import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class BRouterWorker {
  private static final int OUTPUT_FORMAT_GPX = 0;
  private static final int OUTPUT_FORMAT_KML = 1;
  private static final int OUTPUT_FORMAT_JSON = 2;

  public String baseDir;
  public File segmentDir;
  public String profileName;
  public String profilePath;
  public String rawTrackPath;
  public List<OsmNodeNamed> waypoints;
  public List<OsmNodeNamed> nogoList;
  public List<OsmNodeNamed> nogoPolygonsList;
  public String profileParams;

  public String getTrackFromParams(Bundle params) {

    int engineMode = 0;
    if (params.containsKey("engineMode")) {
      engineMode = params.getInt("engineMode", 0);
    }

    String pathToFileResult = params.getString("pathToFileResult");

    if (pathToFileResult != null) {
      File f = new File(pathToFileResult);
      File dir = f.getParentFile();
      if (!dir.exists() || !dir.canWrite()) {
        return "file folder does not exists or can not be written!";
      }
    }

    long maxRunningTime = 60000;
    String sMaxRunningTime = params.getString("maxRunningTime");
    if (sMaxRunningTime != null) {
      maxRunningTime = Integer.parseInt(sMaxRunningTime) * 1000;
    }

    RoutingContext rc = new RoutingContext();
    rc.rawTrackPath = rawTrackPath;
    rc.localFunction = profilePath;

    String tiFormat = params.getString("turnInstructionFormat");
    if (tiFormat != null) {
      if ("osmand".equalsIgnoreCase(tiFormat)) {
        rc.turnInstructionMode = 3;
      } else if ("locus".equalsIgnoreCase(tiFormat)) {
        rc.turnInstructionMode = 7;
      }
    }
    if (params.containsKey("timode")) {
      rc.turnInstructionMode = params.getInt("timode");
    }

    if (params.containsKey("direction")) {
      rc.startDirection = params.getInt("direction");
    }
    if (params.containsKey("heading")) {
      rc.startDirection = params.getInt("heading");
      rc.forceUseStartDirection = true;
    }
    if (params.containsKey("alternativeidx")) {
      rc.alternativeIdx = params.getInt("alternativeidx");
    }

    readNogos(params); // add interface provided nogos
    if (nogoList != null) {
      RoutingContext.prepareNogoPoints(nogoList);
      if (rc.nogopoints == null) {
        rc.nogopoints = nogoList;
      } else {
        rc.nogopoints.addAll(nogoList);
      }
    }
    if (rc.nogopoints == null) {
      rc.nogopoints = nogoPolygonsList;
    } else if (nogoPolygonsList != null) {
      rc.nogopoints.addAll(nogoPolygonsList);
    }
    List<OsmNodeNamed> poisList = readPoisList(params);
    rc.poipoints = poisList;

    if (params.containsKey("lats")) {
      waypoints = readPositions(params);
    }
    if (params.containsKey("lonlats")) {
      waypoints = readLonlats(params, engineMode);
    }

    if (waypoints == null) return "no pts ";

    if (params.containsKey("straight")) {
      try {
        String straight = params.getString("straight");
        String[] sa = straight.split(",");
        for (int i = 0; i < sa.length; i++) {
          int v = Integer.parseInt(sa[i]);
          if (waypoints.size() > v) waypoints.get(v).direct = true;
        }
      } catch (NumberFormatException e) {
      }
    }

    String extraParams = null;
    if (params.containsKey("extraParams")) {  // add user params
      extraParams = params.getString("extraParams");
    }
    if (extraParams != null && this.profileParams != null) {
      // don't overwrite incoming values
      extraParams = this.profileParams + "&" + extraParams;
    } else if (this.profileParams != null) {
      extraParams = this.profileParams;
    }

    if (params.containsKey("extraParams")) {  // add user params
      if (rc.keyValues == null) rc.keyValues = new HashMap<>();
      StringTokenizer tk = new StringTokenizer(extraParams, "?&");
      while (tk.hasMoreTokens()) {
        String t = tk.nextToken();
        StringTokenizer tk2 = new StringTokenizer(t, "=");
        if (tk2.hasMoreTokens()) {
          String key = tk2.nextToken();
          if (tk2.hasMoreTokens()) {
            String value = tk2.nextToken();
            rc.keyValues.put(key, value);
          }
        }
      }
    }

    try {
      writeTimeoutData(rc);
    } catch (Exception e) {
    }

    RoutingEngine cr = new RoutingEngine(null, null, segmentDir, waypoints, rc, engineMode);
    cr.quite = true;
    cr.doRun(maxRunningTime);

    if (engineMode == RoutingEngine.BROUTER_ENGINEMODE_ROUTING) {
      // store new reference track if any
      // (can exist for timed-out search)
      if (cr.getFoundRawTrack() != null) {
        try {
          cr.getFoundRawTrack().writeBinary(rawTrackPath);
        } catch (Exception e) {
        }
      }

      if (cr.getErrorMessage() != null) {
        return cr.getErrorMessage();
      }

      String format = params.getString("trackFormat");
      int writeFromat = OUTPUT_FORMAT_GPX;
      if (format != null) {
        if ("kml".equals(format)) writeFromat = OUTPUT_FORMAT_KML;
        if ("json".equals(format)) writeFromat = OUTPUT_FORMAT_JSON;
      }

      OsmTrack track = cr.getFoundTrack();
      if (track != null) {
        if (params.containsKey("exportWaypoints")) {
          track.exportWaypoints = (params.getInt("exportWaypoints", 0) == 1);
        }
        if (pathToFileResult == null) {
          switch (writeFromat) {
            case OUTPUT_FORMAT_GPX:
              return track.formatAsGpx();
            case OUTPUT_FORMAT_KML:
              return track.formatAsKml();
            case OUTPUT_FORMAT_JSON:
              return track.formatAsGeoJson();
            default:
              return track.formatAsGpx();
          }

        }
        try {
          switch (writeFromat) {
            case OUTPUT_FORMAT_GPX:
              track.writeGpx(pathToFileResult);
              break;
            case OUTPUT_FORMAT_KML:
              track.writeKml(pathToFileResult);
              break;
            case OUTPUT_FORMAT_JSON:
              track.writeJson(pathToFileResult);
              break;
            default:
              track.writeGpx(pathToFileResult);
              break;
          }
        } catch (Exception e) {
          return "error writing file: " + e;
        }
      }
    } else {    // get other infos
      if (cr.getErrorMessage() != null) {
        return cr.getErrorMessage();
      }
      return cr.getFoundInfo();
    }
    return null;
  }

  private List<OsmNodeNamed> readPositions(Bundle params) {
    List<OsmNodeNamed> wplist = new ArrayList<>();

    double[] lats = params.getDoubleArray("lats");
    double[] lons = params.getDoubleArray("lons");

    if (lats == null || lats.length < 2 || lons == null || lons.length < 2) {
      throw new IllegalArgumentException("we need two lat/lon points at least!");
    }

    for (int i = 0; i < lats.length && i < lons.length; i++) {
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = "via" + i;
      n.ilon = (int) ((lons[i] + 180.) * 1000000. + 0.5);
      n.ilat = (int) ((lats[i] + 90.) * 1000000. + 0.5);
      wplist.add(n);
    }
    if (wplist.get(0).name.startsWith("via")) wplist.get(0).name = "from";
    if (wplist.get(wplist.size() - 1).name.startsWith("via"))
      wplist.get(wplist.size() - 1).name = "to";

    return wplist;
  }

  private List<OsmNodeNamed> readLonlats(Bundle params, int mode) {
    List<OsmNodeNamed> wplist = new ArrayList<>();

    String lonLats = params.getString("lonlats");
    if (lonLats == null) throw new IllegalArgumentException("lonlats parameter not set");

    String[] coords;
    if (mode == 0) {
      coords = lonLats.split("\\|");
      if (coords.length < 2)
        throw new IllegalArgumentException("we need two lat/lon points at least!");
    } else {
      coords = new String[1];
      coords[0] = lonLats;
    }
    for (int i = 0; i < coords.length; i++) {
      String[] lonLat = coords[i].split(",");
      if (lonLat.length < 2)
        throw new IllegalArgumentException("we need a lat and lon point at least!");
      wplist.add(readPosition(lonLat[0], lonLat[1], "via" + i));
      if (lonLat.length > 2) {
        if (lonLat[2].equals("d")) {
          wplist.get(wplist.size() - 1).direct = true;
        } else {
          wplist.get(wplist.size() - 1).name = lonLat[2];
        }
      }
    }

    if (wplist.get(0).name.startsWith("via")) wplist.get(0).name = "from";
    if (wplist.get(wplist.size() - 1).name.startsWith("via"))
      wplist.get(wplist.size() - 1).name = "to";

    return wplist;
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


  private void readNogos(Bundle params) {
    if (params.containsKey("nogoLats")) {
      double[] lats = params.getDoubleArray("nogoLats");
      double[] lons = params.getDoubleArray("nogoLons");
      double[] radi = params.getDoubleArray("nogoRadi");

      if (lats == null || lons == null || radi == null) return;

      for (int i = 0; i < lats.length && i < lons.length && i < radi.length; i++) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.name = "nogo" + (int) radi[i];
        n.ilon = (int) ((lons[i] + 180.) * 1000000. + 0.5);
        n.ilat = (int) ((lats[i] + 90.) * 1000000. + 0.5);
        n.isNogo = true;
        n.nogoWeight = Double.NaN;
        AppLogger.log("added interface provided nogo: " + n);
        nogoList.add(n);
      }
    }
    if (params.containsKey("nogos")) {
      nogoList = readNogoList(params);
    }
    if (params.containsKey("polylines") ||
      params.containsKey("polygons")) {
      nogoPolygonsList = readNogoPolygons(params);
    }
  }

  private List<OsmNodeNamed> readNogoList(Bundle params) {
    // lon,lat,radius|...
    String nogos = params.getString("nogos");
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

  private List<OsmNodeNamed> readNogoPolygons(Bundle params) {
    List<OsmNodeNamed> result = new ArrayList<>();
    parseNogoPolygons(params.getString("polylines"), result, false);
    parseNogoPolygons(params.getString("polygons"), result, true);
    return result.size() > 0 ? result : null;
  }

  private static void parseNogoPolygons(String polygons, List<OsmNodeNamed> result, boolean closed) {
    if (polygons != null) {
      String[] polygonList = polygons.split("\\|");
      for (int i = 0; i < polygonList.length; i++) {
        String[] lonLatList = polygonList[i].split(",");
        if (lonLatList.length > 1) {
          OsmNogoPolygon polygon = new OsmNogoPolygon(closed);
          polygon.name = "nogo" + i;
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

  private List<OsmNodeNamed> readPoisList(Bundle params) {
    // lon,lat,name|...
    String pois = params.getString("pois");
    if (pois == null) return null;

    String[] lonLatNameList = pois.split("\\|");

    List<OsmNodeNamed> poisList = new ArrayList<>();
    for (int i = 0; i < lonLatNameList.length; i++) {
      String[] lonLatName = lonLatNameList[i].split(",");

      OsmNodeNamed n = new OsmNodeNamed();
      n.ilon = (int) ((Double.parseDouble(lonLatName[0]) + 180.) * 1000000. + 0.5);
      n.ilat = (int) ((Double.parseDouble(lonLatName[1]) + 90.) * 1000000. + 0.5);
      n.name = lonLatName[2];
      poisList.add(n);
    }

    return poisList;
  }

  private void writeTimeoutData(RoutingContext rc) throws Exception {
    String timeoutFile = baseDir + "/brouter/modes/timeoutdata.txt";

    BufferedWriter bw = new BufferedWriter(new FileWriter(timeoutFile));
    bw.write(profileName);
    bw.write("\n");
    bw.write(rc.rawTrackPath);
    bw.write("\n");
    writeWPList(bw, waypoints);
    writeWPList(bw, nogoList);
    bw.close();
  }

  private void writeWPList(BufferedWriter bw, List<OsmNodeNamed> wps) throws Exception {
    bw.write(wps.size() + "\n");
    for (OsmNodeNamed wp : wps) {
      bw.write(wp.toString());
      bw.write("\n");
    }
  }
}

