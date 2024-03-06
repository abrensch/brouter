package btools.server;

import java.io.File;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;

public class BRouter {
  public static void main(String[] args) throws Exception {
    if (args.length == 3 || args.length == 4) { // cgi-input-mode
      try {
        System.setProperty("segmentBaseDir", args[0]);
        System.setProperty("profileBaseDir", args[1]);
        String queryString = args[2];

        queryString = URLDecoder.decode(queryString, "ISO-8859-1");

        int lonIdx = queryString.indexOf("lonlats=");
        int sepIdx = queryString.indexOf("&", lonIdx);
        String lonlats = queryString.substring(lonIdx + 8, sepIdx);

        RoutingContext rc = new RoutingContext();
        RoutingParamCollector routingParamCollector = new RoutingParamCollector();
        List<OsmNodeNamed> wplist = routingParamCollector.getWayPointList(lonlats);

        Map<String, String> params = routingParamCollector.getUrlParams(queryString);
        int engineMode = 0;
        if (params.containsKey("engineMode")) {
          engineMode = Integer.parseInt(params.get("engineMode"));
        }
        routingParamCollector.setParams(rc, wplist, params);

        String exportName = null;
        if (args.length == 4) {
          exportName = args[3];
        } else {
          // cgi-header
          System.out.println("Content-type: text/plain");
          System.out.println();
        }

        long maxRunningTime = 60000; // the cgi gets a 1 Minute timeout
        String sMaxRunningTime = System.getProperty("maxRunningTime");
        if (sMaxRunningTime != null) {
          maxRunningTime = Integer.parseInt(sMaxRunningTime) * 1000;
        }

        RoutingEngine re = new RoutingEngine(exportName, null, new File(args[0]), wplist, rc, engineMode);
        re.doRun(maxRunningTime);
        if (re.getErrorMessage() != null) {
          System.out.println(re.getErrorMessage());
        }
      } catch (Throwable e) {
        System.out.println("unexpected exception: " + e);
      }
      System.exit(0);
    }
    System.out.println("BRouter " + OsmTrack.version + " / " + OsmTrack.versionDate);
    if (args.length < 5) {
      System.out.println("Find routes in an OSM map");
      System.out.println("usage: java -jar brouter.jar <segmentdir> <profiledir> <engineMode> <profile> <lonlats-list> [parameter-list] [profile-parameter-list] ");
      System.out.println("   or: java -cp %CLASSPATH% btools.server.BRouter <segmentdir>> <profiledir> <engineMode> <profile> <lonlats-list> [parameter-list] [profile-parameter-list]");
      System.out.println("   or: java -jar brouter.jar <segmentdir> <profiledir> <parameter-list> [output-filename]");
      System.exit(0);
    }

    int engineMode = 0;
    try {
      engineMode = Integer.parseInt(args[2]);
    } catch (NumberFormatException e) {
    }

    RoutingParamCollector routingParamCollector = new RoutingParamCollector();
    List<OsmNodeNamed> wplist = routingParamCollector.getWayPointList(args[4]);

    System.setProperty("segmentBaseDir", args[0]);
    System.setProperty("profileBaseDir", args[1]);
    String moreParams = null;
    String profileParams = null;
    if (args.length >= 6) {
      moreParams = args[5];
    }
    if (args.length == 7) {
      profileParams = args[6];
    }

    RoutingContext rc = new RoutingContext();
    rc.localFunction = args[3];
    if (moreParams != null) {
      Map<String, String> params = routingParamCollector.getUrlParams(moreParams);
      routingParamCollector.setParams(rc, wplist, params);
    }
    if (profileParams != null) {
      Map<String, String> params = routingParamCollector.getUrlParams(profileParams);
      routingParamCollector.setProfileParams(rc, params);
    }
    try {
      RoutingEngine re = null;
      if (engineMode == RoutingEngine.BROUTER_ENGINEMODE_GETELEV) {
        re = new RoutingEngine("testinfo", null, new File(args[0]), wplist, rc, engineMode);
      } else {
        // use this to generate a raw track for CLI
        // rc.rawTrackPath = "testtrack.raw";
        re = new RoutingEngine("testtrack", null, new File(args[0]), wplist, rc, engineMode);
      }
      re.doRun(0);

      if (engineMode == RoutingEngine.BROUTER_ENGINEMODE_ROUTING ||
          engineMode == RoutingEngine.BROUTER_ENGINEMODE_PREPARE_REROUTE) {
        // store new reference track if any
        // (can exist for timed-out search)
        if (rc.rawTrackPath != null && re.getFoundRawTrack() != null) {
          try {
            re.getFoundRawTrack().writeBinary(rc.rawTrackPath);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }

    } catch (Exception e) {
      System.out.println(e.getMessage());
    }

  }


}
