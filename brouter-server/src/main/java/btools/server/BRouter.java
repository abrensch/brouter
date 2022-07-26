package btools.server;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.router.OsmNodeNamed;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;
import btools.router.SearchBoundary;

public class BRouter {
  public static void main(String[] args) throws Exception {
    if (args.length == 3) { // cgi-input-mode
      try {
        System.setProperty("segmentBaseDir", args[0]);
        System.setProperty("profileBaseDir", args[1]);
        String queryString = args[2];

        queryString = URLDecoder.decode(queryString, "ISO-8859-1");

        int lonIdx = queryString.indexOf("lonlats=");
        int sepIdx = queryString.indexOf("&", lonIdx);
        String lonlats = queryString.substring(lonIdx+8, sepIdx);

        RoutingContext rc = new RoutingContext();
        RoutingParamCollector routingParamCollector = new RoutingParamCollector();
        List<OsmNodeNamed> wplist = routingParamCollector.getWayPointList(lonlats);

        Map<String, String> params = routingParamCollector.getUrlParams(queryString);
        routingParamCollector.setParams(rc, wplist, params);

        // cgi-header
        System.out.println("Content-type: text/plain");
        System.out.println();


        long maxRunningTime = 60000; // the cgi gets a 1 Minute timeout
        String sMaxRunningTime = System.getProperty("maxRunningTime");
        if (sMaxRunningTime != null) {
          maxRunningTime = Integer.parseInt(sMaxRunningTime) * 1000;
        }


        RoutingEngine re = new RoutingEngine(null, null, new File(args[0]), wplist, rc);

        re.doRun(maxRunningTime);
        if (re.getErrorMessage() != null) {
          System.out.println(re.getErrorMessage());
        }
      } catch (Throwable e) {
        System.out.println("unexpected exception: " + e);
      }
      System.exit(0);
    }
    System.out.println("BRouter " + BRouter.class.getPackage().getImplementationVersion());
    if (args.length < 5) {
      System.out.println("Find routes in an OSM map");
      System.out.println("usage: java -jar brouter.jar <segmentdir> <profiledir> <engineMode> <profile> <lonlats-list> [parameter-list] [profile-parameter-list] ");
      System.out.println("   or: java -cp %CLASSPATH% btools.server.BRouter <segmentdir>> <profiledir> <engineMode> <profile> <lonlats-list> [parameter-list] [profile-parameter-list]");
      System.out.println("   or: java -jar brouter.jar <segmentdir> <profiledir> <parameter-list> ");
      System.exit(0);
    }
    RoutingEngine re = null;
    if ("seed".equals(args[3])) {
      List<OsmNodeNamed> wplist = new ArrayList<>();
      wplist.add(readPosition(args, 1, "from"));
      int searchRadius = Integer.parseInt(args[4]); // if = 0 search a 5x5 square

      String filename = SearchBoundary.getFileName(wplist.get(0));
      DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("traffic/" + filename)));

      for (int direction = 0; direction < 8; direction++) {
        RoutingContext rc = readRoutingContext(args);
        SearchBoundary boundary = new SearchBoundary(wplist.get(0), searchRadius, direction / 2);
        rc.trafficOutputStream = dos;
        rc.inverseDirection = (direction & 1) != 0;
        re = new RoutingEngine("mytrack", "mylog", new File(args[0]), wplist, rc);
        re.boundary = boundary;
        re.airDistanceCostFactor = rc.trafficDirectionFactor;
        rc.countTraffic = true;
        re.doSearch();
        if (re.getErrorMessage() != null) {
          break;
        }
      }
      dos.close();
    } else {
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
        if (engineMode==RoutingEngine.BROUTER_ENGINEMODE_GETELEV) {
          re = new RoutingEngine("testinfo", null, new File(args[0]), wplist, rc, engineMode);
        } else {
          re = new RoutingEngine("testtrack", null, new File(args[0]), wplist, rc, engineMode);
        }
        re.doRun(0);
      } catch (Exception e) {
        System.out.println(e.getMessage());
      }
    }
  }


  private static OsmNodeNamed readPosition(String[] args, int idx, String name) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int) ((Double.parseDouble(args[idx]) + 180.) * 1000000. + 0.5);
    n.ilat = (int) ((Double.parseDouble(args[idx + 1]) + 90.) * 1000000. + 0.5);
    return n;
  }

  private static RoutingContext readRoutingContext(String[] args) {
    RoutingContext c = new RoutingContext();
    if (args.length > 5) {
      c.localFunction = args[5];
      if (args.length > 6) {
        c.setAlternativeIdx(Integer.parseInt(args[6]));
      }
    }
    c.memoryclass = (int) (Runtime.getRuntime().maxMemory() / 1024 / 1024);
    // c.startDirection= Integer.valueOf( 150 );
    return c;
  }
}
