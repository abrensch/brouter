package btools.routingapp;


import android.os.Bundle;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.router.FormatGpx;
import btools.router.FormatJson;
import btools.router.FormatKml;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;

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

  @SuppressWarnings("deprecation")
  public String getTrackFromParams(Bundle params) {

    int engineMode = 0;
    if (params.containsKey("engineMode")) {
      engineMode = params.getInt("engineMode", 0);
    }

    RoutingContext rc = new RoutingContext();
    rc.rawTrackPath = rawTrackPath;
    rc.localFunction = profilePath;

    RoutingParamCollector routingParamCollector = new RoutingParamCollector();

    // parameter pre control
    if (params.containsKey("lonlats")) {
      waypoints = routingParamCollector.getWayPointList(params.getString("lonlats"));
      params.remove("lonlats");
    }
    if (params.containsKey("lats")) {
      double[] lats = params.getDoubleArray("lats");
      double[] lons = params.getDoubleArray("lons");
      waypoints = routingParamCollector.readPositions(lons, lats);
      params.remove("lons");
      params.remove("lats");
    }

    if (waypoints == null) {
      throw new IllegalArgumentException("no points!");
    }
    if (engineMode == 0) {
      if (waypoints.size() < 2) {
        throw new IllegalArgumentException("we need two lat/lon points at least!");
      }
    } else {
      if (waypoints.size() < 1) {
        throw new IllegalArgumentException("we need two lat/lon points at least!");
      }
    }

    if (nogoList != null && nogoList.size() > 0) {
      // forward already read nogos from filesystem
      if (rc.nogopoints == null) {
        rc.nogopoints = nogoList;
      } else {
        rc.nogopoints.addAll(nogoList);
      }

    }

    Map<String, String> theParams = new HashMap<>();
    for (String key : params.keySet()) {
      Object value = params.get(key);
      if (value instanceof double[]) {
        String s = Arrays.toString(params.getDoubleArray(key));
        s = s.replace("[", "").replace("]", "");
        theParams.put(key, s);
      } else {
        theParams.put(key, value.toString());
      }
    }
    routingParamCollector.setParams(rc, waypoints, theParams);

    if (params.containsKey("extraParams")) {
      Map<String, String> profileparams = null;
      try {
        profileparams = routingParamCollector.getUrlParams(params.getString("extraParams"));
        routingParamCollector.setProfileParams(rc, profileparams);
      } catch (UnsupportedEncodingException e) {
        // ignore
      }
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
      maxRunningTime = Integer.parseInt(sMaxRunningTime) * 1000L;
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

      int writeFromat = OUTPUT_FORMAT_GPX;
      if (rc.outputFormat != null) {
        if ("kml".equals(rc.outputFormat)) writeFromat = OUTPUT_FORMAT_KML;
        if ("json".equals(rc.outputFormat)) writeFromat = OUTPUT_FORMAT_JSON;
      }
      OsmTrack track = null;
      track = cr.getFoundTrack();
      if (track != null) {
        track.exportWaypoints = rc.exportWaypoints;
        if (pathToFileResult == null) {
          switch (writeFromat) {
            case OUTPUT_FORMAT_KML:
              return new FormatKml(rc).format(track);
            case OUTPUT_FORMAT_JSON:
              return new FormatJson(rc).format(track);
            case OUTPUT_FORMAT_GPX:
            default:
              return new FormatGpx(rc).format(track);
          }

        }

      }
      try {
        switch (writeFromat) {
          case OUTPUT_FORMAT_KML:
            new FormatKml(rc).write(pathToFileResult, track);
            break;
          case OUTPUT_FORMAT_JSON:
            new FormatJson(rc).write(pathToFileResult, track);
            break;
          case OUTPUT_FORMAT_GPX:
          default:
            new FormatGpx(rc).write(pathToFileResult, track);
            break;
        }
      } catch (Exception e) {
        return "error writing file: " + e;
      }

    } else {    // get other infos
      if (cr.getErrorMessage() != null) {
        return cr.getErrorMessage();
      }
      return cr.getFoundInfo();
    }
    return null;
  }


  private void writeTimeoutData(RoutingContext rc) throws Exception {
    String timeoutFile = baseDir + "/brouter/modes/timeoutdata.txt";

    BufferedWriter bw = new BufferedWriter(new FileWriter(timeoutFile));
    bw.write(profileName);
    bw.write("\n");
    bw.write(rc.rawTrackPath);
    bw.write("\n");
    writeWPList(bw, waypoints);
    writeWPList(bw, rc.nogopoints);
    bw.close();
  }

  private void writeWPList(BufferedWriter bw, List<OsmNodeNamed> wps) throws Exception {
    if (wps == null) {
      bw.write("0\n");
    } else {
      bw.write(wps.size() + "\n");
      for (OsmNodeNamed wp : wps) {
        bw.write(wp.toString());
        bw.write("\n");
      }
    }
  }
}

