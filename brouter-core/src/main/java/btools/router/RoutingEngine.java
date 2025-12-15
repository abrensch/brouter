package btools.router;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmLinkHolder;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodePairSet;
import btools.mapaccess.OsmPos;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;
import btools.util.CompactLongMap;
import btools.util.SortedHeap;
import btools.util.StackSampler;

public class RoutingEngine extends Thread {

  public final static int BROUTER_ENGINEMODE_ROUTING = 0;
  public final static int BROUTER_ENGINEMODE_SEED = 1;
  public final static int BROUTER_ENGINEMODE_GETELEV = 2;
  public final static int BROUTER_ENGINEMODE_GETINFO = 3;
  public final static int BROUTER_ENGINEMODE_ROUNDTRIP = 4;

  private NodesCache nodesCache;
  private SortedHeap<OsmPath> openSet = new SortedHeap<>();
  private boolean finished = false;

  protected List<OsmNodeNamed> waypoints = null;
  List<OsmNodeNamed> extraWaypoints = null;
  protected List<MatchedWaypoint> matchedWaypoints;
  private int linksProcessed = 0;

  private int nodeLimit; // used for target island search
  private int MAXNODES_ISLAND_CHECK = 500;
  private OsmNodePairSet islandNodePairs = new OsmNodePairSet(MAXNODES_ISLAND_CHECK);
  private boolean useNodePoints = false; // use the start/end nodes  instead of crosspoint

  private int engineMode = 0;

  private int MAX_STEPS_CHECK = 500;

  private int ROUNDTRIP_DEFAULT_DIRECTIONADD = 45;

  private int MAX_DYNAMIC_RANGE = 60000;

  protected OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  private int alternativeIndex = 0;

  protected String outputMessage = null;
  protected String errorMessage = null;

  private volatile boolean terminated;

  protected File segmentDir;
  private String outfileBase;
  private String logfileBase;
  private boolean infoLogEnabled;
  private Writer infoLogWriter;
  private StackSampler stackSampler;
  protected RoutingContext routingContext;

  public double airDistanceCostFactor;
  public double lastAirDistanceCostFactor;

  private OsmTrack guideTrack;

  private OsmPathElement matchPath;

  private long startTime;
  private long maxRunningTime;
  public SearchBoundary boundary;

  public boolean quite = false;

  private Object[] extract;

  private boolean directWeaving = !Boolean.getBoolean("disableDirectWeaving");
  private String outfile;

  public RoutingEngine(String outfileBase, String logfileBase, File segmentDir,
                       List<OsmNodeNamed> waypoints, RoutingContext rc) {
    this(outfileBase, logfileBase, segmentDir, waypoints, rc, 0);
  }

  public RoutingEngine(String outfileBase, String logfileBase, File segmentDir,
                       List<OsmNodeNamed> waypoints, RoutingContext rc, int engineMode) {
    this.segmentDir = segmentDir;
    this.outfileBase = outfileBase;
    this.logfileBase = logfileBase;
    this.waypoints = waypoints;
    this.infoLogEnabled = outfileBase != null;
    this.routingContext = rc;
    this.engineMode = engineMode;

    File baseFolder = new File(routingContext.localFunction).getParentFile();
    baseFolder = baseFolder == null ? null : baseFolder.getParentFile();
    if (baseFolder != null) {
      try {
        File debugLog = new File(baseFolder, "debug.txt");
        if (debugLog.exists()) {
          infoLogWriter = new FileWriter(debugLog, true);
          logInfo("********** start request at ");
          logInfo("********** " + new Date());
        }
      } catch (IOException ioe) {
        throw new RuntimeException("cannot open debug-log:" + ioe);
      }

      File stackLog = new File(baseFolder, "stacks.txt");
      if (stackLog.exists()) {
        stackSampler = new StackSampler(stackLog, 1000);
        stackSampler.start();
        logInfo("********** started stacksampling");
      }
    }
    boolean cachedProfile = ProfileCache.parseProfile(rc);
    if (hasInfo()) {
      logInfo("parsed profile " + rc.localFunction + " cached=" + cachedProfile);
    }

  }

  private boolean hasInfo() {
    return infoLogEnabled || infoLogWriter != null;
  }

  private void logInfo(String s) {
    if (infoLogEnabled) {
      System.out.println(s);
    }
    if (infoLogWriter != null) {
      try {
        infoLogWriter.write(s);
        infoLogWriter.write('\n');
        infoLogWriter.flush();
      } catch (IOException io) {
        infoLogWriter = null;
      }
    }
  }

  private void logThrowable(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    logInfo(sw.toString());
  }

  public void run() {
    doRun(0);
  }

  public void doRun(long maxRunningTime) {

    switch (engineMode) {
      case BROUTER_ENGINEMODE_ROUTING:
        if (waypoints.size() < 2) {
          throw new IllegalArgumentException("we need two lat/lon points at least!");
        }
        doRouting(maxRunningTime);
        break;
      case BROUTER_ENGINEMODE_SEED: /* do nothing, handled the old way */
        throw new IllegalArgumentException("not a valid engine mode");
      case BROUTER_ENGINEMODE_GETELEV:
      case BROUTER_ENGINEMODE_GETINFO:
        if (waypoints.size() < 1) {
          throw new IllegalArgumentException("we need one lat/lon point at least!");
        }
        doGetInfo();
        break;
      case BROUTER_ENGINEMODE_ROUNDTRIP:
        if (waypoints.size() < 1)
          throw new IllegalArgumentException("we need one lat/lon point at least!");
        doRoundTrip();
        break;
      default:
        throw new IllegalArgumentException("not a valid engine mode");
    }
  }


  public void doRouting(long maxRunningTime) {
    try {
      startTime = System.currentTimeMillis();
      long startTime0 = startTime;
      this.maxRunningTime = maxRunningTime;

      if (routingContext.allowSamewayback) {
        if (waypoints.size() == 2) {
          OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(waypoints.get(0).ilon, waypoints.get(0).ilat));
          onn.name = "to";
          waypoints.add(onn);
        } else {
          waypoints.get(waypoints.size() - 1).name = "via" + (waypoints.size() - 1) + "_center";
          List<OsmNodeNamed> newpoints = new ArrayList<>();
          for (int i = waypoints.size() - 2; i >= 0; i--) {
            // System.out.println("back " + waypoints.get(i));
            OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(waypoints.get(i).ilon, waypoints.get(i).ilat));
            onn.name = "via";
            newpoints.add(onn);
          }
          newpoints.get(newpoints.size() - 1).name = "to";
          waypoints.addAll(newpoints);
        }
      }

      int nsections = waypoints.size() - 1;
      OsmTrack[] refTracks = new OsmTrack[nsections]; // used ways for alternatives
      OsmTrack[] lastTracks = new OsmTrack[nsections];
      OsmTrack track = null;
      List<String> messageList = new ArrayList<>();
      for (int i = 0; ; i++) {
        track = findTrack(refTracks, lastTracks);

        // we are only looking for info
        if (routingContext.ai != null) return;

        track.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend
          + " plain-ascend = " + track.plainAscend + " cost=" + track.cost;
        if (track.energy != 0) {
          track.message += " energy=" + Formatter.getFormattedEnergy(track.energy) + " time=" + Formatter.getFormattedTime2(track.getTotalSeconds());
        }
        track.name = "brouter_" + routingContext.getProfileName() + "_" + i;

        messageList.add(track.message);
        track.messageList = messageList;
        if (outfileBase != null) {
          String filename = outfileBase + i + "." + routingContext.outputFormat;
          OsmTrack oldTrack = null;
          switch (routingContext.outputFormat) {
            case "gpx":
              oldTrack = new FormatGpx(routingContext).read(filename);
              break;
            case "geojson": // read only gpx at the moment
            case "json":
              // oldTrack = new FormatJson(routingContext).read(filename);
              break;
            case "kml":
              // oldTrack = new FormatJson(routingContext).read(filename);
              break;
            default:
              break;
          }
          if (oldTrack != null && track.equalsTrack(oldTrack)) {
            continue;
          }
          oldTrack = null;
          track.exportWaypoints = routingContext.exportWaypoints;
          track.exportCorrectedWaypoints = routingContext.exportCorrectedWaypoints;
          filename = outfileBase + i + "." + routingContext.outputFormat;
          switch (routingContext.outputFormat) {
            case "gpx":
              outputMessage = new FormatGpx(routingContext).format(track);
              break;
            case "geojson":
            case "json":
              outputMessage = new FormatJson(routingContext).format(track);
              break;
            case "kml":
              outputMessage = new FormatKml(routingContext).format(track);
              break;
            case "csv":
            default:
              outputMessage = null;
              break;
          }
          if (outputMessage != null) {
            File out = new File(filename);
            FileWriter fw = new FileWriter(filename);
            fw.write(outputMessage);
            fw.close();
            outputMessage = null;
          }

          foundTrack = track;
          alternativeIndex = i;
          outfile = filename;
        } else {
          if (i == routingContext.getAlternativeIdx(0, 3)) {
            if ("CSV".equals(System.getProperty("reportFormat"))) {
              String filename = outfileBase + i + ".csv";
              new FormatCsv(routingContext).write(filename, track);
            } else {
              if (!quite) {
                System.out.println(new FormatGpx(routingContext).format(track));
              }
            }
            foundTrack = track;
          } else {
            continue;
          }
        }
        if (logfileBase != null) {
          String logfilename = logfileBase + i + ".csv";
          new FormatCsv(routingContext).write(logfilename, track);
        }
        break;
      }
      long endTime = System.currentTimeMillis();
      logInfo("execution time = " + (endTime - startTime0) / 1000. + " seconds");
    } catch (IllegalArgumentException e) {
      logException(e);
    } catch (Exception e) {
      logException(e);
      logThrowable(e);
    } catch (Error e) {
      cleanOnOOM();
      logException(e);
      logThrowable(e);
    } finally {
      if (hasInfo() && routingContext.expctxWay != null) {
        logInfo("expression cache stats=" + routingContext.expctxWay.cacheStats());
      }

      ProfileCache.releaseProfile(routingContext);

      if (nodesCache != null) {
        if (hasInfo() && nodesCache != null) {
          logInfo("NodesCache status before close=" + nodesCache.formatStatus());
        }
        nodesCache.close();
        nodesCache = null;
      }
      openSet.clear();
      finished = true; // this signals termination to outside

      if (infoLogWriter != null) {
        try {
          infoLogWriter.close();
        } catch (Exception e) {
        }
        infoLogWriter = null;
      }

      if (stackSampler != null) {
        try {
          stackSampler.close();
        } catch (Exception e) {
        }
        stackSampler = null;
      }

    }
  }

  public void doGetInfo() {
    try {
      startTime = System.currentTimeMillis();

      routingContext.freeNoWays();

      MatchedWaypoint wpt1 = new MatchedWaypoint();
      wpt1.waypoint = waypoints.get(0);
      wpt1.name = "wpt_info";
      List<MatchedWaypoint> listOne = new ArrayList<>();
      listOne.add(wpt1);
      matchWaypointsToNodes(listOne);

      resetCache(true);
      nodesCache.nodesMap.cleanupMode = 0;

      OsmNode start1 = nodesCache.getGraphNode(listOne.get(0).node1);
      boolean b = nodesCache.obtainNonHollowNode(start1);

      guideTrack = new OsmTrack();
      guideTrack.addNode(OsmPathElement.create(wpt1.node2.ilon, wpt1.node2.ilat, (short) 0, null));
      guideTrack.addNode(OsmPathElement.create(wpt1.node1.ilon, wpt1.node1.ilat, (short) 0, null));

      matchedWaypoints = new ArrayList<>();
      MatchedWaypoint wp1 = new MatchedWaypoint();
      wp1.crosspoint = new OsmNode(wpt1.node1.ilon, wpt1.node1.ilat);
      wp1.node1 = new OsmNode(wpt1.node1.ilon, wpt1.node1.ilat);
      wp1.node2 = new OsmNode(wpt1.node2.ilon, wpt1.node2.ilat);
      matchedWaypoints.add(wp1);
      MatchedWaypoint wp2 = new MatchedWaypoint();
      wp2.crosspoint = new OsmNode(wpt1.node2.ilon, wpt1.node2.ilat);
      wp2.node1 = new OsmNode(wpt1.node1.ilon, wpt1.node1.ilat);
      wp2.node2 = new OsmNode(wpt1.node2.ilon, wpt1.node2.ilat);
      matchedWaypoints.add(wp2);

      OsmTrack t = findTrack("getinfo", wp1, wp2, null, null, false);
      if (t != null) {
        t.messageList = new ArrayList<>();
        t.matchedWaypoints = matchedWaypoints;
        t.name = (outfileBase == null ? "getinfo" : outfileBase);

        // find nearest point
        int mindist = 99999;
        int minIdx = -1;
        for (int i = 0; i < t.nodes.size(); i++) {
          OsmPathElement ope = t.nodes.get(i);
          int dist = ope.calcDistance(listOne.get(0).crosspoint);
          if (mindist > dist) {
            mindist = dist;
            minIdx = i;
          }
        }
        int otherIdx = 0;
        if (minIdx == t.nodes.size() - 1) {
          otherIdx = minIdx - 1;
        } else {
          otherIdx = minIdx + 1;
        }
        int otherdist = t.nodes.get(otherIdx).calcDistance(listOne.get(0).crosspoint);
        int minSElev = t.nodes.get(minIdx).getSElev();
        int otherSElev = t.nodes.get(otherIdx).getSElev();
        int diffSElev = 0;
        diffSElev = otherSElev - minSElev;
        double diff = (double) mindist / (mindist + otherdist) * diffSElev;


        OsmNodeNamed n = new OsmNodeNamed(listOne.get(0).crosspoint);
        n.name = wpt1.name;
        n.selev = minIdx != -1 ? (short) (minSElev + (int) diff) : Short.MIN_VALUE;
        if (engineMode == BROUTER_ENGINEMODE_GETINFO) {
          n.nodeDescription = (start1 != null && start1.firstlink != null ? start1.firstlink.descriptionBitmap : null);
          t.pois.add(n);
          //t.message = "get_info";
          //t.messageList.add(t.message);
          t.matchedWaypoints = listOne;
          t.exportWaypoints = routingContext.exportWaypoints;
        }

        switch (routingContext.outputFormat) {
          case "gpx":
            if (engineMode == BROUTER_ENGINEMODE_GETELEV) {
              outputMessage = new FormatGpx(routingContext).formatAsWaypoint(n);
            } else {
              outputMessage = new FormatGpx(routingContext).format(t);
            }
            break;
          case "geojson":
          case "json":
            if (engineMode == BROUTER_ENGINEMODE_GETELEV) {
              outputMessage = new FormatJson(routingContext).formatAsWaypoint(n);
            } else {
              outputMessage = new FormatJson(routingContext).format(t);
            }
            break;
          case "kml":
          case "csv":
          default:
            outputMessage = null;
            break;
        }
        if (outfileBase != null) {
          String filename = outfileBase + "." + routingContext.outputFormat;
          File out = new File(filename);
          FileWriter fw = new FileWriter(filename);
          fw.write(outputMessage);
          fw.close();
          outputMessage = null;
        } else {
          if (!quite && outputMessage != null) {
            System.out.println(outputMessage);
          }
        }

      } else {
        if (errorMessage == null) errorMessage = "no track found";
      }
      long endTime = System.currentTimeMillis();
      logInfo("execution time = " + (endTime - startTime) / 1000. + " seconds");
    } catch (Exception e) {
      e.getStackTrace();
      logException(e);
    }
  }

  public void doRoundTrip() {
    try {
      long startTime = System.currentTimeMillis();

      routingContext.useDynamicDistance = true;
      double searchRadius = (routingContext.roundTripDistance == null ? 1500 :routingContext.roundTripDistance);
      double direction = (routingContext.startDirection == null ? -1 :routingContext.startDirection);
      double directionAdd = (routingContext.roundTripDirectionAdd == null ? ROUNDTRIP_DEFAULT_DIRECTIONADD :routingContext.roundTripDirectionAdd);
      if (direction == -1) direction = getRandomDirectionFromData(waypoints.get(0), searchRadius);

      if (routingContext.allowSamewayback) {
        int[] pos = CheapRuler.destination(waypoints.get(0).ilon, waypoints.get(0).ilat, searchRadius, direction);
        MatchedWaypoint wpt2 = new MatchedWaypoint();
        wpt2.waypoint = new OsmNode(pos[0], pos[1]);
        wpt2.name = "rt1_" + direction;

        OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
        onn.name = "rt1";
        waypoints.add(onn);
      } else {
        buildPointsFromCircle(waypoints, direction, searchRadius, routingContext.roundTripPoints == null ? 5 : routingContext.roundTripPoints);
      }

      routingContext.waypointCatchingRange = 250;

      doRouting(0);

      long endTime = System.currentTimeMillis();
      logInfo("round trip execution time = " + (endTime - startTime) / 1000. + " seconds");
    } catch (Exception e) {
      e.getStackTrace();
      logException(e);
    }

  }

  void buildPointsFromCircle(List<OsmNodeNamed> waypoints, double startAngle, double searchRadius, int points) {
    //startAngle -= 90;
    for (int i = 1; i < points; i++) {
      double anAngle = 90 - (180.0 * i / points);
      int[] pos = CheapRuler.destination(waypoints.get(0).ilon, waypoints.get(0).ilat, searchRadius, startAngle - anAngle);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + i;
      waypoints.add(onn);
    }

    OsmNodeNamed onn = new OsmNodeNamed(waypoints.get(0));
    onn.name = "to_rt";
    waypoints.add(onn);
  }

  int getRandomDirectionFromData(OsmNodeNamed wp, double searchRadius) {

    long start = System.currentTimeMillis();

    int preferredRandomType = 0;
    boolean consider_elevation = routingContext.expctxWay.getVariableValue("consider_elevation", 0f) == 1f;
    boolean consider_forest = routingContext.expctxWay.getVariableValue("consider_forest", 0f) == 1f;
    boolean consider_river = routingContext.expctxWay.getVariableValue("consider_river", 0f) == 1f;
    if (consider_elevation) {
      preferredRandomType = AreaInfo.RESULT_TYPE_ELEV50;
    } else if (consider_forest) {
      preferredRandomType = AreaInfo.RESULT_TYPE_GREEN;
    } else if (consider_river) {
      preferredRandomType = AreaInfo.RESULT_TYPE_RIVER;
    } else {
      return (int) (Math.random()*360);
    }

    MatchedWaypoint wpt1 = new MatchedWaypoint();
    wpt1.waypoint = wp;
    wpt1.name = "info";
    wpt1.radius = searchRadius * 1.5;

    List<AreaInfo> ais = new ArrayList<>();
    AreaReader areareader = new AreaReader();
    if (routingContext.rawAreaPath != null) {
      File fai = new File(routingContext.rawAreaPath);
      if (fai.exists()) {
        areareader.readAreaInfo(fai, wpt1, ais);
      }
    }

    if (ais.isEmpty()) {
      List<MatchedWaypoint> listStart = new ArrayList<>();
      listStart.add(wpt1);

      List<OsmNodeNamed> wpliststart = new ArrayList<>();
      wpliststart.add(wp);

      List<OsmNodeNamed> listOne = new ArrayList<>();

      for (int a = 45; a < 360; a += 90) {
        int[] pos = CheapRuler.destination(wp.ilon, wp.ilat, searchRadius * 1.5, a);
        OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
        onn.name = "via" + a;
        listOne.add(onn);

        MatchedWaypoint wpt = new MatchedWaypoint();
        wpt.waypoint = onn;
        wpt.name = onn.name;
        listStart.add(wpt);
      }

      RoutingEngine re = null;
      RoutingContext rc = new RoutingContext();
      String name = routingContext.localFunction;
      int idx = name.lastIndexOf(File.separator);
      rc.localFunction = idx == -1 ? "dummy" : name.substring(0, idx + 1) + "dummy.brf";

      re = new RoutingEngine(null, null, segmentDir, wpliststart, rc, BROUTER_ENGINEMODE_ROUNDTRIP);
      rc.useDynamicDistance = true;
      re.matchWaypointsToNodes(listStart);
      re.resetCache(true);

      int numForest = rc.expctxWay.getLookupKey("estimated_forest_class");
      int numRiver = rc.expctxWay.getLookupKey("estimated_river_class");

      OsmNode start1 = re.nodesCache.getStartNode(listStart.get(0).node1.getIdFromPos());

      double elev = (start1 == null ? 0 : start1.getElev()); // listOne.get(0).crosspoint.getElev();

      int maxlon = Integer.MIN_VALUE;
      int minlon = Integer.MAX_VALUE;
      int maxlat = Integer.MIN_VALUE;
      int minlat = Integer.MAX_VALUE;
      for (OsmNodeNamed on : listOne) {
        maxlon = Math.max(on.ilon, maxlon);
        minlon = Math.min(on.ilon, minlon);
        maxlat = Math.max(on.ilat, maxlat);
        minlat = Math.min(on.ilat, minlat);
      }
      OsmNogoPolygon searchRect = new OsmNogoPolygon(true);
      searchRect.addVertex(maxlon, maxlat);
      searchRect.addVertex(maxlon, minlat);
      searchRect.addVertex(minlon, minlat);
      searchRect.addVertex(minlon, maxlat);

      for (int a = 0; a < 4; a++) {
        rc.ai = new AreaInfo(a * 90 + 90);
        rc.ai.elevStart = elev;
        rc.ai.numForest = numForest;
        rc.ai.numRiver = numRiver;

        rc.ai.polygon = new OsmNogoPolygon(true);
        rc.ai.polygon.addVertex(wp.ilon, wp.ilat);
        rc.ai.polygon.addVertex(listOne.get(a).ilon, listOne.get(a).ilat);
        if (a == 3)
          rc.ai.polygon.addVertex(listOne.get(0).ilon, listOne.get(0).ilat);
        else
          rc.ai.polygon.addVertex(listOne.get(a + 1).ilon, listOne.get(a + 1).ilat);

        ais.add(rc.ai);
      }

      int maxscale = Math.abs(searchRect.points.get(2).x - searchRect.points.get(0).x);
      maxscale = Math.max(1, Math.round(maxscale / 31250f / 2) + 1);

      areareader.getDirectAllData(segmentDir, rc, wp, maxscale, rc.expctxWay, searchRect, ais);

      if (routingContext.rawAreaPath != null) {
        try {
          wpt1.radius = searchRadius * 1.5;
          areareader.writeAreaInfo(routingContext.rawAreaPath, wpt1, ais);
        } catch (Exception e) {
        }
      }
      rc.ai = null;

    }

    logInfo("round trip execution time = " + (System.currentTimeMillis() - start) / 1000. + " seconds");

    // for (AreaInfo ai: ais) {
    //  System.out.println("\n" + ai.toString());
    //}

    switch (preferredRandomType) {
      case AreaInfo.RESULT_TYPE_ELEV50:
        Collections.sort(ais, new Comparator<>() {
          public int compare(AreaInfo o1, AreaInfo o2) {
            return o2.getElev50Weight() - o1.getElev50Weight();
          }
        });
        break;
      case AreaInfo.RESULT_TYPE_GREEN:
        Collections.sort(ais, new Comparator<>() {
          public int compare(AreaInfo o1, AreaInfo o2) {
            return o2.getGreen() - o1.getGreen();
          }
        });
        break;
      case AreaInfo.RESULT_TYPE_RIVER:
        Collections.sort(ais, new Comparator<>() {
          public int compare(AreaInfo o1, AreaInfo o2) {
            return o2.getRiver() - o1.getRiver();
          }
        });
        break;
      default:
        return (int) (Math.random()*360);
    }

    int angle = ais.get(0).direction;
    return angle - 30 + (int) (Math.random() * 60);
  }



  private void postElevationCheck(OsmTrack track) {
    OsmPathElement lastPt = null;
    OsmPathElement startPt = null;
    short lastElev = Short.MIN_VALUE;
    short startElev = Short.MIN_VALUE;
    short endElev = Short.MIN_VALUE;
    int startIdx = 0;
    int endIdx = -1;
    int dist = 0;
    int ourSize = track.nodes.size();
    for (int idx = 0; idx < ourSize; idx++) {
      OsmPathElement n = track.nodes.get(idx);
      if (n.getSElev() == Short.MIN_VALUE && lastElev != Short.MIN_VALUE && idx < ourSize - 1) {
        // start one point before entry point to get better elevation results
        if (idx > 1)
          startElev = track.nodes.get(idx - 2).getSElev();
        if (startElev == Short.MIN_VALUE)
          startElev = lastElev;
        startIdx = idx;
        startPt = lastPt;
        dist = 0;
        if (lastPt != null)
          dist += n.calcDistance(lastPt);
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startElev != Short.MIN_VALUE) {
        // end one point behind exit point to get better elevation results
        if (idx + 1 < track.nodes.size())
          endElev = track.nodes.get(idx + 1).getSElev();
        if (endElev == Short.MIN_VALUE)
          endElev = n.getSElev();
        endIdx = idx;
        OsmPathElement tmpPt = track.nodes.get(startIdx > 1 ? startIdx - 2 : startIdx - 1);
        int diffElev = endElev - startElev;
        dist += tmpPt.calcDistance(startPt);
        dist += n.calcDistance(lastPt);
        int distRest = dist;
        double incline = diffElev / (dist / 100.);
        String lastMsg = "";
        double tmpincline = 0;
        double startincline = 0;
        double selev = track.nodes.get(startIdx - 2).getSElev();
        boolean hasInclineTags = false;
        for (int i = startIdx - 1; i < endIdx + 1; i++) {
          OsmPathElement tmp = track.nodes.get(i);
          if (tmp.message != null) {
            MessageData md = tmp.message.copy();
            String msg = md.wayKeyValues;
            if (!msg.equals(lastMsg)) {
              boolean revers = msg.contains("reversedirection=yes");
              int pos = msg.indexOf("incline=");
              if (pos != -1) {
                hasInclineTags = true;
                String s = msg.substring(pos + 8);
                pos = s.indexOf(" ");
                if (pos != -1)
                  s = s.substring(0, pos);

                if (s.length() > 0) {
                  try {
                    int ind = s.indexOf("%");
                    if (ind != -1)
                      s = s.substring(0, ind);
                    ind = s.indexOf("°");
                    if (ind != -1)
                      s = s.substring(0, ind);
                    tmpincline = Double.parseDouble(s.trim());
                    if (revers)
                      tmpincline *= -1;
                  } catch (NumberFormatException e) {
                    tmpincline = 0;
                  }
                }
              } else {
                tmpincline = 0;
              }
              if (startincline == 0) {
                startincline = tmpincline;
              } else if (startincline < 0 && tmpincline > 0) {
                // for the way up find the exit point
                double diff = endElev - selev;
                tmpincline = diff / (distRest / 100.);
              }
            }
            lastMsg = msg;
          }
          int tmpdist = tmp.calcDistance(tmpPt);
          distRest -= tmpdist;
          if (hasInclineTags)
            incline = tmpincline;
          selev = (selev + (tmpdist / 100. * incline));
          tmp.setSElev((short) selev);
          tmp.message.ele = (short) selev;
          tmpPt = tmp;
        }
        dist = 0;
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startIdx == 0) {
        // fill at start
        for (int i = 0; i < idx; i++) {
          track.nodes.get(i).setSElev(n.getSElev());
        }
      } else if (n.getSElev() == Short.MIN_VALUE && idx == track.nodes.size() - 1) {
        // fill at end
        startIdx = idx;
        for (int i = startIdx; i < track.nodes.size(); i++) {
          track.nodes.get(i).setSElev(lastElev);
        }
      } else if (n.getSElev() == Short.MIN_VALUE) {
        if (lastPt != null)
          dist += n.calcDistance(lastPt);
      }
      lastElev = n.getSElev();
      lastPt = n;
    }

  }

  private void logException(Throwable t) {
    errorMessage = t instanceof RuntimeException ? t.getMessage() : t.toString();
    logInfo("Error (linksProcessed=" + linksProcessed + " open paths: " + openSet.getSize() + "): " + errorMessage);
  }


  public void doSearch() {
    try {
      MatchedWaypoint seedPoint = new MatchedWaypoint();
      seedPoint.waypoint = waypoints.get(0);
      List<MatchedWaypoint> listOne = new ArrayList<>();
      listOne.add(seedPoint);
      matchWaypointsToNodes(listOne);

      findTrack("seededSearch", seedPoint, null, null, null, false);
    } catch (IllegalArgumentException e) {
      logException(e);
    } catch (Exception e) {
      logException(e);
      logThrowable(e);
    } catch (Error e) {
      cleanOnOOM();
      logException(e);
      logThrowable(e);
    } finally {
      ProfileCache.releaseProfile(routingContext);
      if (nodesCache != null) {
        nodesCache.close();
        nodesCache = null;
      }
      openSet.clear();
      finished = true; // this signals termination to outside

      if (infoLogWriter != null) {
        try {
          infoLogWriter.close();
        } catch (Exception e) {
        }
        infoLogWriter = null;
      }
    }
  }

  public void cleanOnOOM() {
    terminate();
  }

  private OsmTrack findTrack(OsmTrack[] refTracks, OsmTrack[] lastTracks) {
    for (; ; ) {
      try {
        return tryFindTrack(refTracks, lastTracks);
      } catch (RoutingIslandException rie) {
        if (routingContext.useDynamicDistance) {
          for (MatchedWaypoint mwp : matchedWaypoints) {
            if (mwp.name.contains("_add")) {
              long n1 = mwp.node1.getIdFromPos();
              long n2 = mwp.node2.getIdFromPos();
              islandNodePairs.addTempPair(n1, n2);
            }
          }
        }
        islandNodePairs.freezeTempPairs();
        nodesCache.clean(true);
        matchedWaypoints = null;
      }
    }
  }

  private OsmTrack tryFindTrack(OsmTrack[] refTracks, OsmTrack[] lastTracks) {
    OsmTrack totaltrack = new OsmTrack();
    int nUnmatched = waypoints.size();
    boolean hasDirectRouting = false;

    if (useNodePoints && extraWaypoints != null) {
      // add extra waypoints from the last broken round
      for (OsmNodeNamed wp : extraWaypoints) {
        if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true;
        if (wp.name.startsWith("from")) {
          waypoints.add(1, wp);
          waypoints.get(0).wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
          nUnmatched++;
        } else {
          waypoints.add(waypoints.size() - 1, wp);
          waypoints.get(waypoints.size() - 2).wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
          nUnmatched++;
        }
      }
      extraWaypoints = null;
    }
    if (lastTracks.length < waypoints.size() - 1) {
      refTracks = new OsmTrack[waypoints.size() - 1]; // used ways for alternatives
      lastTracks = new OsmTrack[waypoints.size() - 1];
      hasDirectRouting = true;
    }
    for (OsmNodeNamed wp : waypoints) {
      if (hasInfo()) logInfo("wp=" + wp + (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT ? " beeline" : (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_MEETING ? " via" : "")));
      if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true;
    }

    // check for a track for that target
    OsmTrack nearbyTrack = null;
    if (!hasDirectRouting && lastTracks[waypoints.size() - 2] == null) {
      StringBuilder debugInfo = hasInfo() ? new StringBuilder() : null;
      nearbyTrack = OsmTrack.readBinary(routingContext.rawTrackPath, waypoints.get(waypoints.size() - 1), routingContext.getNogoChecksums(), routingContext.profileTimestamp, debugInfo);
      if (nearbyTrack != null) {
        nUnmatched--;
      }
      if (hasInfo()) {
        boolean found = nearbyTrack != null;
        boolean dirty = found && nearbyTrack.isDirty;
        logInfo("read referenceTrack, found=" + found + " dirty=" + dirty + " " + debugInfo);
      }
    }

    if (matchedWaypoints == null) { // could exist from the previous alternative level
      matchedWaypoints = new ArrayList<>();
      for (int i = 0; i < nUnmatched; i++) {
        MatchedWaypoint mwp = new MatchedWaypoint();
        mwp.waypoint = waypoints.get(i);
        mwp.name = waypoints.get(i).name;
        mwp.wpttype = waypoints.get(i).wpttype;
        matchedWaypoints.add(mwp);
      }
      int startSize = matchedWaypoints.size();
      matchWaypointsToNodes(matchedWaypoints);
      if (startSize < matchedWaypoints.size()) {
        refTracks = new OsmTrack[matchedWaypoints.size() - 1]; // used ways for alternatives
        lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        hasDirectRouting = true;
      }

      for (MatchedWaypoint mwp : matchedWaypoints) {
        if (hasInfo() && matchedWaypoints.size() != nUnmatched)
          logInfo("new wp=" + mwp.waypoint + " " + mwp.crosspoint + (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT ? " beeline" : (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_MEETING ? " via" : "")));
      }

      routingContext.checkMatchedWaypointAgainstNogos(matchedWaypoints);

      // detect target islands: restricted search in inverse direction
      routingContext.inverseDirection = !routingContext.inverseRouting;
      airDistanceCostFactor = 0.;
      for (int i = 0; i < matchedWaypoints.size() - 1; i++) {
        nodeLimit = MAXNODES_ISLAND_CHECK;
        if (matchedWaypoints.get(i).wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) continue;
        if (routingContext.inverseRouting) {
          OsmTrack seg = findTrack("start-island-check", matchedWaypoints.get(i), matchedWaypoints.get(i + 1), null, null, false);
          if (seg == null && nodeLimit > 0) {
            throw new IllegalArgumentException("start island detected for section " + i);
          }
        } else {
          OsmTrack seg = findTrack("target-island-check", matchedWaypoints.get(i + 1), matchedWaypoints.get(i), null, null, false);
          if (seg == null && nodeLimit > 0) {
            throw new IllegalArgumentException("target island detected for section " + i);
          }
        }
      }
      routingContext.inverseDirection = false;
      nodeLimit = 0;

      if (nearbyTrack != null) {
        matchedWaypoints.add(nearbyTrack.endPoint);
      }
    } else {
      if (lastTracks.length < matchedWaypoints.size() - 1) {
        refTracks = new OsmTrack[matchedWaypoints.size() - 1]; // used ways for alternatives
        lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        hasDirectRouting = true;
      }
    }
    for (MatchedWaypoint mwp : matchedWaypoints) {
      //System.out.println(FormatGpx.getWaypoint(mwp.waypoint.ilon, mwp.waypoint.ilat, mwp.name, null));
      //System.out.println(FormatGpx.getWaypoint(mwp.crosspoint.ilon, mwp.crosspoint.ilat, mwp.name+"_cp", null));
    }

    routingContext.hasDirectRouting = hasDirectRouting;

    OsmPath.seg = 1; // set segment counter
    for (int i = 0; i < matchedWaypoints.size() - 1; i++) {
      if (lastTracks[i] != null) {
        if (refTracks[i] == null) refTracks[i] = new OsmTrack();
        refTracks[i].addNodes(lastTracks[i]);
      }

      OsmTrack seg;
      int wptIndex;
      if (routingContext.inverseRouting) {
        routingContext.inverseDirection = true;
        seg = searchTrack(matchedWaypoints.get(i + 1), matchedWaypoints.get(i), null, refTracks[i]);
        routingContext.inverseDirection = false;
        wptIndex = i + 1;
      } else {
        seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), i == matchedWaypoints.size() - 2 ? nearbyTrack : null, refTracks[i]);
        wptIndex = i;
        if (routingContext.continueStraight) {
          if (i < matchedWaypoints.size() - 2) {
            OsmNode lastPoint = seg.containsNode(matchedWaypoints.get(i+1).node1) ? matchedWaypoints.get(i+1).node1 : matchedWaypoints.get(i+1).node2;
            OsmNodeNamed nogo = new OsmNodeNamed(lastPoint);
            nogo.radius = 5;
            nogo.name = "nogo" + (i+1);
            nogo.nogoWeight = 9999.;
            nogo.isNogo = true;
            if (routingContext.nogopoints == null) routingContext.nogopoints = new ArrayList<>();
            routingContext.nogopoints.add(nogo);
          }
        }
      }
      if (seg == null)
        return null;

      if (routingContext.ai != null) return null;

      boolean changed = false;
      if (routingContext.correctMisplacedViaPoints &&
          matchedWaypoints.get(i).wpttype != MatchedWaypoint.WAYPOINT_TYPE_DIRECT &&
          matchedWaypoints.get(i).wpttype != MatchedWaypoint.WAYPOINT_TYPE_MEETING &&
          !routingContext.allowSamewayback) {
        changed = snapPathConnection(totaltrack, seg, routingContext.inverseRouting ? matchedWaypoints.get(i + 1) : matchedWaypoints.get(i));
      }
      if (wptIndex > 0)
        matchedWaypoints.get(wptIndex).indexInTrack = totaltrack.nodes.size() - 1;

      totaltrack.appendTrack(seg);
      lastTracks[i] = seg;
    }

    postElevationCheck(totaltrack);

    recalcTrack(totaltrack);

    matchedWaypoints.get(matchedWaypoints.size() - 1).indexInTrack = totaltrack.nodes.size() - 1;
    totaltrack.matchedWaypoints = matchedWaypoints;
    totaltrack.processVoiceHints(routingContext);
    totaltrack.prepareSpeedProfile(routingContext);

    totaltrack.showTime = routingContext.showTime;
    totaltrack.params = routingContext.keyValues;

    if (routingContext.poipoints != null)
      totaltrack.pois = routingContext.poipoints;

    return totaltrack;
  }

  OsmTrack getExtraSegment(OsmPathElement start, OsmPathElement end) {

    if (start == null || end == null) return null;

    List<MatchedWaypoint> wptlist = new ArrayList<>();
    MatchedWaypoint wpt1 = new MatchedWaypoint();
    wpt1.waypoint = new OsmNode(start.getILon(), start.getILat());
    wpt1.name = "wptx1";
    wpt1.crosspoint = new OsmNode(start.getILon(), start.getILat());
    wpt1.node1 = new OsmNode(start.getILon(), start.getILat());
    wpt1.node2 = new OsmNode(end.getILon(), end.getILat());
    wptlist.add(wpt1);
    MatchedWaypoint wpt2 = new MatchedWaypoint();
    wpt2.waypoint = new OsmNode(end.getILon(), end.getILat());
    wpt2.name = "wptx2";
    wpt2.crosspoint = new OsmNode(end.getILon(), end.getILat());
    wpt2.node2 = new OsmNode(start.getILon(), start.getILat());
    wpt2.node1 = new OsmNode(end.getILon(), end.getILat());
    wptlist.add(wpt2);

    MatchedWaypoint mwp1 = wptlist.get(0);
    MatchedWaypoint mwp2 = wptlist.get(1);

    OsmTrack mid = null;

    boolean corr = routingContext.correctMisplacedViaPoints;
    routingContext.correctMisplacedViaPoints = false;

    guideTrack = new OsmTrack();
    guideTrack.addNode(start);
    guideTrack.addNode(end);

    mid = findTrack("getinfo", mwp1, mwp2, null, null, false);

    guideTrack = null;
    routingContext.correctMisplacedViaPoints = corr;

    return mid;
  }

  private int snapRoundaboutConnection(OsmTrack tt, OsmTrack t, int indexStart, int indexEnd, int indexMeeting, MatchedWaypoint startWp) {

    int indexMeetingBack = (indexMeeting == -1 ? tt.nodes.size() - 1 : indexMeeting);
    int indexMeetingFore = 0;
    int indexStartBack = indexStart;
    int indexStartFore = 0;

    OsmPathElement ptStart = tt.nodes.get(indexStartBack);
    OsmPathElement ptMeeting = tt.nodes.get(indexMeetingBack);
    OsmPathElement ptEnd = t.nodes.get(indexEnd);

    boolean bMeetingIsOnRoundabout = ptMeeting.message.isRoundabout();
    boolean bMeetsRoundaboutStart = false;
    int wayDistance = 0;

    int i;
    OsmPathElement last_n = null;

    for (i = 0; i < indexEnd; i++) {
      OsmPathElement n = t.nodes.get(i);
      if (last_n != null) wayDistance += n.calcDistance(last_n);
      last_n = n;
      if (n.positionEquals(ptStart)) {
        indexStartFore = i;
        bMeetsRoundaboutStart = true;
      }
      if (n.positionEquals(ptMeeting)) {
        indexMeetingFore = i;
      }

    }

    if (routingContext.correctMisplacedViaPointsDistance > 0 &&
      wayDistance > routingContext.correctMisplacedViaPointsDistance) {
      return 0;
    }

    if (!bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexMeetingFore;
    }
    if (bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexStartFore;
    }

    List<OsmPathElement> removeList = new ArrayList<>();
    if (!bMeetsRoundaboutStart) {
      indexStartBack = indexMeetingBack;
      while (!tt.nodes.get(indexStartBack).message.isRoundabout()) {
        indexStartBack--;
        if (indexStartBack == 2) break;
      }
    }

    for (i = indexStartBack + 1; i < tt.nodes.size(); i++) {
      OsmPathElement n = tt.nodes.get(i);
      OsmTrack.OsmPathElementHolder detours = tt.getFromDetourMap(n.getIdFromPos());
      if (detours != null) {
        OsmTrack.OsmPathElementHolder h = detours;
        while (h != null) {
          h = h.nextHolder;
        }
      }
      removeList.add(n);
    }

    OsmPathElement ttend = null;
    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {
      ttend = tt.nodes.get(indexStartBack);
      OsmTrack.OsmPathElementHolder ttend_detours = tt.getFromDetourMap(ttend.getIdFromPos());
      if (ttend_detours != null) {
        tt.registerDetourForId(ttend.getIdFromPos(), null);
      }
    }

    for (OsmPathElement e : removeList) {
      tt.nodes.remove(e);
    }
    removeList.clear();


    for (i = 0; i < indexEnd; i++) {
      OsmPathElement n = t.nodes.get(i);
      if (n.positionEquals(bMeetsRoundaboutStart ? ptStart : ptEnd)) break;
      if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart && n.message.isRoundabout()) break;

      OsmTrack.OsmPathElementHolder detours = t.getFromDetourMap(n.getIdFromPos());
      if (detours != null) {
        OsmTrack.OsmPathElementHolder h = detours;
        while (h != null) {
          h = h.nextHolder;
        }
      }
      removeList.add(n);
    }

    // time hold
    float atime = 0;
    float aenergy = 0;
    int acost = 0;
    if (i > 1) {
      atime = t.nodes.get(i).getTime();
      aenergy = t.nodes.get(i).getEnergy();
      acost = t.nodes.get(i).cost;
    }

    for (OsmPathElement e : removeList) {
      t.nodes.remove(e);
    }
    removeList.clear();

    if (atime > 0f) {
      for (OsmPathElement e : t.nodes) {
        e.setTime(e.getTime() - atime);
        e.setEnergy(e.getEnergy() - aenergy);
        e.cost = e.cost - acost;
      }
    }

    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {

      OsmTrack.OsmPathElementHolder ttend_detours = tt.getFromDetourMap(ttend.getIdFromPos());

      OsmTrack mid = null;
      if (ttend_detours != null && ttend_detours.node != null) {
        mid = getExtraSegment(ttend, ttend_detours.node);
      }
      OsmPathElement tt_end = tt.nodes.get(tt.nodes.size() - 1);

      int last_cost = tt_end.cost;
      float last_time = tt_end.getTime();
      float last_energy = tt_end.getEnergy();
      int tmp_cost = 0;
      float tmp_time = 0f;
      float tmp_energy = 0f;

      if (mid != null) {
        boolean start = false;
        for (OsmPathElement e : mid.nodes) {
          if (start) {
            if (e.positionEquals(ttend_detours.node)) {
              tmp_cost = e.cost;
              tmp_time = e.getTime();
              tmp_energy = e.getEnergy();
              break;
            }
            e.cost = last_cost + e.cost;
            e.setTime(last_time + e.getTime());
            e.setEnergy(last_energy + e.getEnergy());
            tt.nodes.add(e);
          }
          if (e.positionEquals(tt_end)) start = true;
        }

        ttend_detours.node.cost = last_cost + tmp_cost;
        ttend_detours.node.setTime(last_time + tmp_time);
        ttend_detours.node.setEnergy(last_energy + tmp_energy);
        tt.nodes.add(ttend_detours.node);
        t.nodes.add(0, ttend_detours.node);
      }

    }

    tt.cost = tt.nodes.get(tt.nodes.size()-1).cost;
    t.cost = t.nodes.get(t.nodes.size()-1).cost;

    startWp.correctedpoint = new OsmNode(ptStart.getILon(), ptStart.getILat());

    return (t.nodes.size());
  }

  // check for way back on way point
  private boolean snapPathConnection(OsmTrack tt, OsmTrack t, MatchedWaypoint startWp) {
    if (!startWp.name.startsWith("via") && !startWp.name.startsWith("rt"))
      return false;

    int ourSize = tt.nodes.size();
    if (ourSize > 0) {
      OsmPathElement testPoint = tt.nodes.get(ourSize - 1);
      if (routingContext.poipoints != null) {
        for (OsmNodeNamed node : routingContext.poipoints) {

          int lon0 = tt.nodes.get(ourSize - 2).getILon();
          int lat0 = tt.nodes.get(ourSize - 2).getILat();
          int lon1 = startWp.crosspoint.ilon;
          int lat1 = startWp.crosspoint.ilat;
          int lon2 = node.ilon;
          int lat2 = node.ilat;
          double angle3 = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2);
          int dist = node.calcDistance(startWp.crosspoint);
          if (dist < routingContext.waypointCatchingRange)
            return false;
        }
      }
      List<OsmPathElement> removeBackList = new ArrayList<>();
      List<OsmPathElement> removeForeList = new ArrayList<>();
      List<Integer> removeVoiceHintList = new ArrayList<>();
      OsmPathElement last = null;
      OsmPathElement lastJunction = null;
      CompactLongMap<OsmTrack.OsmPathElementHolder> lastJunctions = new CompactLongMap<>();
      OsmPathElement newJunction = null;
      OsmPathElement newTarget = null;
      OsmPathElement tmpback = null;
      OsmPathElement tmpfore = null;
      OsmPathElement tmpStart = null;
      int indexback = ourSize - 1;
      int indexfore = 0;
      int stop = (indexback - MAX_STEPS_CHECK > 1 ? indexback - MAX_STEPS_CHECK : 1);
      double wayDistance = 0;
      double nextDist = 0;
      boolean bCheckRoundAbout = false;
      boolean bBackRoundAbout = false;
      boolean bForeRoundAbout = false;
      int indexBackFound = 0;
      int indexForeFound = 0;
      int differentLanePoints = 0;
      int indexMeeting = -1;
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size()) {
        tmpback = tt.nodes.get(indexback);
        tmpfore = t.nodes.get(indexfore);
        if (!bBackRoundAbout && tmpback.message != null && tmpback.message.isRoundabout()) {
          bBackRoundAbout = true;
          indexBackFound = indexfore;
        }
        if (!bForeRoundAbout &&
           tmpfore.message != null && tmpfore.message.isRoundabout() ||
          (tmpback.positionEquals(tmpfore) && tmpback.message.isRoundabout())) {
          bForeRoundAbout = true;
          indexForeFound = indexfore;
        }
        if (indexfore == 0) {
          tmpStart = t.nodes.get(0);
        } else {
          double dirback = CheapAngleMeter.getDirection(tmpStart.getILon(), tmpStart.getILat(), tmpback.getILon(), tmpback.getILat());
          double dirfore = CheapAngleMeter.getDirection(tmpStart.getILon(), tmpStart.getILat(), tmpfore.getILon(), tmpfore.getILat());
          double dirdiff = CheapAngleMeter.getDifferenceFromDirection(dirback, dirfore);
          // walking wrong direction
          if (dirdiff > 60 && !bBackRoundAbout && !bForeRoundAbout) break;
        }
        // seems no roundabout, only on one end
        if (bBackRoundAbout != bForeRoundAbout && indexfore - Math.abs(indexForeFound - indexBackFound) > 8) break;
        if (!tmpback.positionEquals(tmpfore)) differentLanePoints++;
        if (tmpback.positionEquals(tmpfore)) indexMeeting = indexback;
        bCheckRoundAbout = bBackRoundAbout && bForeRoundAbout;
        if (bCheckRoundAbout) break;
        indexback--;
        indexfore++;
      }
      //System.out.println("snap round result " + indexback + ": " + bBackRoundAbout + " - " + indexfore + "; " + bForeRoundAbout + " pts " + differentLanePoints);
      if (bCheckRoundAbout) {

        tmpback = tt.nodes.get(--indexback);
        while (tmpback.message != null && tmpback.message.isRoundabout()) {
          tmpback = tt.nodes.get(--indexback);
        }

        int ifore = ++indexfore;
        OsmPathElement testfore = t.nodes.get(ifore);
        while (ifore < t.nodes.size() && testfore.message != null && testfore.message.isRoundabout()) {
          testfore = t.nodes.get(ifore);
          ifore++;
        }

        snapRoundaboutConnection(tt, t, indexback, --ifore, indexMeeting, startWp);

        // remove filled arrays
        removeVoiceHintList.clear();
        removeBackList.clear();
        removeForeList.clear();
        return true;
      }
      indexback = ourSize - 1;
      indexfore = 0;
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size()) {
        int junctions = 0;
        tmpback = tt.nodes.get(indexback);
        tmpfore = t.nodes.get(indexfore);
        if (tmpback.message != null && tmpback.message.isRoundabout()) {
          bCheckRoundAbout = true;
        }
        if (tmpfore.message != null && tmpfore.message.isRoundabout()) {
          bCheckRoundAbout = true;
        }
        {

          int dist = tmpback.calcDistance(tmpfore);
          OsmTrack.OsmPathElementHolder detours = tt.getFromDetourMap(tmpback.getIdFromPos());
          OsmTrack.OsmPathElementHolder h = detours;
          while (h != null) {
            junctions++;
            lastJunctions.put(h.node.getIdFromPos(), h);
            h = h.nextHolder;
          }

          if (dist == 1 && indexfore > 0) {
            if (indexfore == 1) {
              removeBackList.add(tt.nodes.get(tt.nodes.size() - 1)); // last and first should be equal, so drop only on second also equal
              removeForeList.add(t.nodes.get(0));
              removeBackList.add(tmpback);
              removeForeList.add(tmpfore);
              removeVoiceHintList.add(tt.nodes.size() - 1);
              removeVoiceHintList.add(indexback);
            } else {
              removeBackList.add(tmpback);
              removeForeList.add(tmpfore);
              removeVoiceHintList.add(indexback);
            }
            nextDist = t.nodes.get(indexfore - 1).calcDistance(tmpfore);
            wayDistance += nextDist;

          }
          if (dist > 1 || indexback == 1) {
            if (removeBackList.size() != 0) {
              // recover last - should be the cross point
              removeBackList.remove(removeBackList.get(removeBackList.size() - 1));
              removeForeList.remove(removeForeList.get(removeForeList.size() - 1));
              break;
            } else {
              return false;
            }
          }
          indexback--;
          indexfore++;

          if (routingContext.correctMisplacedViaPointsDistance > 0 &&
            wayDistance > routingContext.correctMisplacedViaPointsDistance) {
            removeVoiceHintList.clear();
            removeBackList.clear();
            removeForeList.clear();
            return false;
          }
        }
      }


      // time hold
      float atime = 0;
      float aenergy = 0;
      int acost = 0;
      if (removeForeList.size() > 1) {
        atime = t.nodes.get(indexfore -1).getTime();
        aenergy = t.nodes.get(indexfore -1).getEnergy();
        acost = t.nodes.get(indexfore -1).cost;
      }

      for (OsmPathElement e : removeBackList) {
        tt.nodes.remove(e);
      }
      for (OsmPathElement e : removeForeList) {
        t.nodes.remove(e);
      }
      for (Integer e : removeVoiceHintList) {
        tt.removeVoiceHint(e);
      }
      removeVoiceHintList.clear();
      removeBackList.clear();
      removeForeList.clear();

      if (atime > 0f) {
        for (OsmPathElement e : t.nodes) {
          e.setTime(e.getTime() - atime);
          e.setEnergy(e.getEnergy() - aenergy);
          e.cost = e.cost - acost;
        }
      }

      if (t.nodes.size() < 2)
        return true;
      if (tt.nodes.size() < 1)
        return true;
      if (tt.nodes.size() == 1) {
        last = tt.nodes.get(0);
      } else {
        last = tt.nodes.get(tt.nodes.size() - 2);
      }
      newJunction = t.nodes.get(0);
      newTarget = t.nodes.get(1);

      tt.cost = tt.nodes.get(tt.nodes.size()-1).cost;
      t.cost = t.nodes.get(t.nodes.size()-1).cost;

      // fill to correctedpoint
      startWp.correctedpoint = new OsmNode(newJunction.getILon(), newJunction.getILat());

      return true;
    }
    return false;
  }

  private void recalcTrack(OsmTrack t) {
    int totaldist = 0;
    int totaltime = 0;
    float lasttime = 0;
    float lastenergy = 0;
    float speed_min = 9999;
    Map<Integer, Integer> directMap = new HashMap<>();
    float tmptime = 1;
    float speed = 1;
    int dist;
    double angle;

    double ascend = 0;
    double ehb = 0.;
    int ourSize = t.nodes.size();

    short ele_start = Short.MIN_VALUE;
    short ele_end = Short.MIN_VALUE;
    double eleFactor = routingContext.inverseRouting ? 0.25 : -0.25;

    for (int i = 0; i < ourSize; i++) {
      OsmPathElement n = t.nodes.get(i);
      if (n.message == null) n.message = new MessageData();
      OsmPathElement nLast = null;
      if (i == 0) {
        angle = 0;
        dist = 0;
      } else if (i == 1) {
        angle = 0;
        nLast = t.nodes.get(0);
        dist = nLast.calcDistance(n);
      } else {
        int lon0 = t.nodes.get(i - 2).getILon();
        int lat0 = t.nodes.get(i - 2).getILat();
        int lon1 = t.nodes.get(i - 1).getILon();
        int lat1 = t.nodes.get(i - 1).getILat();
        int lon2 = t.nodes.get(i).getILon();
        int lat2 = t.nodes.get(i).getILat();
        angle = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2);
        nLast = t.nodes.get(i - 1);
        dist = nLast.calcDistance(n);
      }
      n.message.linkdist = dist;
      n.message.turnangle = (float) angle;
      totaldist += dist;
      totaltime += n.getTime();
      tmptime = (n.getTime() - lasttime);
      if (dist > 0) {
        speed = dist / tmptime * 3.6f;
        speed_min = Math.min(speed_min, speed);
      }
      if (tmptime == 1.f) { // no time used here
        directMap.put(i, dist);
      }

      lastenergy = n.getEnergy();
      lasttime = n.getTime();

      short ele = n.getSElev();
      if (ele != Short.MIN_VALUE)
        ele_end = ele;
      if (ele_start == Short.MIN_VALUE)
        ele_start = ele;

      if (nLast != null) {
        short ele_last = nLast.getSElev();
        if (ele_last != Short.MIN_VALUE) {
          ehb = ehb + (ele_last - ele) * eleFactor;
        }
        double filter = elevationFilter(n);
        if (ehb > 0) {
          ascend += ehb;
          ehb = 0;
        } else if (ehb < filter) {
          ehb = filter;
        }
      }

    }

    t.ascend = (int) ascend;
    t.plainAscend = (int) ((ele_start - ele_end) * eleFactor + 0.5);

    t.distance = totaldist;
    //t.energy = totalenergy;

    SortedSet<Integer> keys = new TreeSet<>(directMap.keySet());
    for (Integer key : keys) {
      int value = directMap.get(key);
      float addTime = (value / (speed_min / 3.6f));

      double addEnergy = 0;
      if (key > 0) {
        double GRAVITY = 9.81;  // in meters per second^(-2)
        double incline = (t.nodes.get(key - 1).getSElev() == Short.MIN_VALUE || t.nodes.get(key).getSElev() == Short.MIN_VALUE ? 0 : (t.nodes.get(key - 1).getElev() - t.nodes.get(key).getElev()) / value);
        double f_roll = routingContext.totalMass * GRAVITY * (routingContext.defaultC_r + incline);
        double spd = speed_min / 3.6;
        addEnergy = value * (routingContext.S_C_x * spd * spd + f_roll);
      }
      for (int j = key; j < ourSize; j++) {
        OsmPathElement n = t.nodes.get(j);
        n.setTime(n.getTime() + addTime);
        n.setEnergy(n.getEnergy() + (float) addEnergy);
      }
    }
    t.energy = (int) t.nodes.get(t.nodes.size() - 1).getEnergy();

    logInfo("track-length total = " + t.distance);
    logInfo("filtered ascend = " + t.ascend);
  }

  /**
   * find the elevation type for position
   * to determine the filter value
   *
   * @param n  the point
   * @return  the filter value for 1sec / 3sec elevation source
   */
  double elevationFilter(OsmPos n) {
    if (nodesCache != null) {
      int r = nodesCache.getElevationType(n.getILon(), n.getILat());
      if (r == 1) return -5.;
    }
    return -10.;
  }

  // geometric position matching finding the nearest routable way-section
  private void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints) {
    resetCache(false);
    boolean useDynamicDistance = routingContext.useDynamicDistance;
    boolean bAddBeeline = routingContext.buildBeelineOnRange;
    double range = routingContext.waypointCatchingRange;
    boolean ok = nodesCache.matchWaypointsToNodes(unmatchedWaypoints, range, islandNodePairs);
    if (!ok && useDynamicDistance) {
      logInfo("second check for way points");
      resetCache(false);
      range = -MAX_DYNAMIC_RANGE;
      List<MatchedWaypoint> tmp = new ArrayList<>();
      for (MatchedWaypoint mwp : unmatchedWaypoints) {
        if (mwp.crosspoint == null || mwp.radius >= routingContext.waypointCatchingRange)
          tmp.add(mwp);
      }
      ok = nodesCache.matchWaypointsToNodes(tmp, range, islandNodePairs);
    }
    if (!ok) {
      for (MatchedWaypoint mwp : unmatchedWaypoints) {
        if (mwp.crosspoint == null)
          throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
      }
    }
    // add beeline points when not already done
    if (useDynamicDistance && !useNodePoints && bAddBeeline) {
      List<MatchedWaypoint> waypoints = new ArrayList<>();
      for (int i = 0; i < unmatchedWaypoints.size(); i++) {
        MatchedWaypoint wp = unmatchedWaypoints.get(i);
        if (wp.waypoint.calcDistance(wp.crosspoint) > routingContext.waypointCatchingRange) {

          MatchedWaypoint nmw = new MatchedWaypoint();
          if (i == 0) {
            OsmNodeNamed onn = new OsmNodeNamed(wp.waypoint);
            onn.name = "from";
            nmw.waypoint = onn;
            nmw.name = onn.name;
            nmw.crosspoint = new OsmNode(wp.waypoint.ilon, wp.waypoint.ilat);
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
            onn = new OsmNodeNamed(wp.crosspoint);
            onn.name = wp.name + "_add";
            wp.waypoint = onn;
            waypoints.add(nmw);
            wp.name = wp.name + "_add";
            waypoints.add(wp);
          } else {
            OsmNodeNamed onn = new OsmNodeNamed(wp.crosspoint);
            onn.name = wp.name + "_add";
            nmw.waypoint = onn;
            nmw.crosspoint = new OsmNode(wp.crosspoint.ilon, wp.crosspoint.ilat);
            nmw.node1 = new OsmNode(wp.node1.ilon, wp.node1.ilat);
            nmw.node2 = new OsmNode(wp.node2.ilon, wp.node2.ilat);
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;

            if (wp.name != null) nmw.name = wp.name;
            waypoints.add(nmw);
            wp.name = wp.name + "_add";
            waypoints.add(wp);
            if (wp.name.startsWith("via")) {
              wp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
              MatchedWaypoint emw = new MatchedWaypoint();
              OsmNodeNamed onn2 = new OsmNodeNamed(wp.crosspoint);
              onn2.name = wp.name + "_2";
              emw.name = onn2.name;
              emw.waypoint = onn2;
              emw.crosspoint = new OsmNode(nmw.crosspoint.ilon, nmw.crosspoint.ilat);
              emw.node1 = new OsmNode(nmw.node1.ilon, nmw.node1.ilat);
              emw.node2 = new OsmNode(nmw.node2.ilon, nmw.node2.ilat);
              emw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_SHAPING;
              waypoints.add(emw);
            }
            wp.crosspoint = new OsmNode(wp.waypoint.ilon, wp.waypoint.ilat);
          }
        } else {
          waypoints.add(wp);
        }
      }
      unmatchedWaypoints.clear();
      unmatchedWaypoints.addAll(waypoints);
    }

  }

  private OsmTrack searchTrack(MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack nearbyTrack, OsmTrack refTrack) {
    // remove nogos with waypoints inside
    try {
      boolean calcBeeline = startWp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT;

      if (!calcBeeline)
        return searchRoutedTrack(startWp, endWp, nearbyTrack, refTrack);

      // we want a beeline-segment
      OsmPath path = routingContext.createPath(new OsmLink(null, startWp.crosspoint));
      path = routingContext.createPath(path, new OsmLink(startWp.crosspoint, endWp.crosspoint), null, false);
      return compileTrack(path, false);
    } finally {
      routingContext.restoreNogoList();
    }
  }

  private OsmTrack searchRoutedTrack(MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack nearbyTrack, OsmTrack refTrack) {
    OsmTrack track = null;
    double[] airDistanceCostFactors = new double[]{
      routingContext.pass1coefficient,
      routingContext.pass2coefficient
    };
    boolean isDirty = false;
    IllegalArgumentException dirtyMessage = null;

    if (nearbyTrack != null) {
      airDistanceCostFactor = 0.;
      try {
        track = findTrack("re-routing", startWp, endWp, nearbyTrack, refTrack, true);
      } catch (IllegalArgumentException iae) {
        if (terminated) throw iae;

        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        if (matchPath != null) {
          track = mergeTrack(matchPath, nearbyTrack);
          isDirty = true;
          dirtyMessage = iae;
          logInfo("using fast partial recalc");
        }
        if (maxRunningTime > 0) {
          maxRunningTime += System.currentTimeMillis() - startTime; // reset timeout...
        }
      }
    }

    if (track == null) {
      for (int cfi = 0; cfi < airDistanceCostFactors.length; cfi++) {
        if (cfi > 0) lastAirDistanceCostFactor = airDistanceCostFactors[cfi - 1];
        airDistanceCostFactor = airDistanceCostFactors[cfi];

        if (airDistanceCostFactor < 0.) {
          continue;
        }

        OsmTrack t;
        try {
          t = findTrack(cfi == 0 ? "pass0" : "pass1", startWp, endWp, track, refTrack, false);
          if (routingContext.ai != null) return t;
        } catch (IllegalArgumentException iae) {
          if (!terminated && matchPath != null) { // timeout, but eventually prepare a dirty ref track
            logInfo("supplying dirty reference track after timeout");
            foundRawTrack = mergeTrack(matchPath, track);
            foundRawTrack.endPoint = endWp;
            foundRawTrack.nogoChecksums = routingContext.getNogoChecksums();
            foundRawTrack.profileTimestamp = routingContext.profileTimestamp;
            foundRawTrack.isDirty = true;
          }
          throw iae;
        }

        if (t == null && track != null && matchPath != null) {
          // ups, didn't find it, use a merge
          t = mergeTrack(matchPath, track);
          logInfo("using sloppy merge cause pass1 didn't reach destination");
        }
        if (t != null) {
          track = t;
        } else {
          throw new IllegalArgumentException("no track found at pass=" + cfi);
        }
      }
    }
    if (track == null) throw new IllegalArgumentException("no track found");

    OsmPathElement lastElement = null;

    boolean wasClean = nearbyTrack != null && !nearbyTrack.isDirty;
    if (refTrack == null && !(wasClean && isDirty)) { // do not overwrite a clean with a dirty track
      logInfo("supplying new reference track, dirty=" + isDirty);
      track.endPoint = endWp;
      track.nogoChecksums = routingContext.getNogoChecksums();
      track.profileTimestamp = routingContext.profileTimestamp;
      track.isDirty = isDirty;
      foundRawTrack = track;
    }

    if (!wasClean && isDirty) {
      throw dirtyMessage;
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.;
    lastAirDistanceCostFactor = 0.;
    guideTrack = track;
    startTime = System.currentTimeMillis(); // reset timeout...
    try {
      OsmTrack tt = findTrack("re-tracking", startWp, endWp, null, refTrack, false);
      if (tt == null) throw new IllegalArgumentException("error re-tracking track");
      return tt;
    } finally {
      guideTrack = null;
    }
  }


  private void resetCache(boolean detailed) {
    if (hasInfo() && nodesCache != null) {
      logInfo("NodesCache status before reset=" + nodesCache.formatStatus());
    }
    long maxmem = routingContext.memoryclass * 1024L * 1024L; // in MB

    nodesCache = new NodesCache(segmentDir, routingContext.expctxWay, routingContext.forceSecondaryData, maxmem, nodesCache, detailed);
    islandNodePairs.clearTempPairs();
  }

  private OsmPath getStartPath(OsmNode n1, OsmNode n2, MatchedWaypoint mwp, OsmNodeNamed endPos, boolean sameSegmentSearch) {
    if (endPos != null) {
      endPos.radius = 1.5;
    }
    OsmPath p = getStartPath(n1, n2, new OsmNodeNamed(mwp.crosspoint), endPos, sameSegmentSearch);

    // special case: start+end on same segment
    if (p != null && p.cost >= 0 && sameSegmentSearch && endPos != null && endPos.radius < 1.5) {
      p.treedepth = 0; // hack: mark for the final-check
    }
    return p;
  }


  private OsmPath getStartPath(OsmNode n1, OsmNode n2, OsmNodeNamed wp, OsmNodeNamed endPos, boolean sameSegmentSearch) {
    try {
      routingContext.setWaypoint(wp, sameSegmentSearch ? endPos : null, false);
      OsmPath bestPath = null;
      OsmLink bestLink = null;
      OsmLink startLink = new OsmLink(null, n1);
      OsmPath startPath = routingContext.createPath(startLink);
      startLink.addLinkHolder(startPath, null);
      double minradius = 1e10;
      for (OsmLink link = n1.firstlink; link != null; link = link.getNext(n1)) {
        OsmNode nextNode = link.getTarget(n1);
        if (nextNode.isHollow())
          continue; // border node?
        if (nextNode.firstlink == null)
          continue; // don't care about dead ends
        if (nextNode == n1)
          continue; // ?
        if (nextNode != n2)
          continue; // just that link

        wp.radius = 1.5;
        OsmPath testPath = routingContext.createPath(startPath, link, null, guideTrack != null);
        testPath.airdistance = endPos == null ? 0 : nextNode.calcDistance(endPos);
        if (wp.radius < minradius) {
          bestPath = testPath;
          minradius = wp.radius;
          bestLink = link;
        }
      }
      if (bestLink != null) {
        bestLink.addLinkHolder(bestPath, n1);
      }
      if (bestPath != null) bestPath.treedepth = 1;

      return bestPath;
    } finally {
      routingContext.unsetWaypoint();
    }
  }

  private OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
    try {
      List<OsmNode> wpts2 = new ArrayList<>();
      if (startWp != null) wpts2.add(startWp.waypoint);
      if (endWp != null) wpts2.add(endWp.waypoint);
      routingContext.cleanNogoList(wpts2);

      boolean detailed = guideTrack != null;
      resetCache(detailed);
      nodesCache.nodesMap.cleanupMode = detailed ? 0 : (routingContext.considerTurnRestrictions ? 2 : 1);
      return _findTrack(operationName, startWp, endWp, costCuttingTrack, refTrack, fastPartialRecalc);
    } finally {
      routingContext.restoreNogoList();
      nodesCache.clean(false); // clean only non-virgin caches
    }
  }


  private OsmTrack _findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
    boolean verbose = guideTrack != null;

    int maxTotalCost = guideTrack != null ? guideTrack.cost + 5000 : 1000000000;
    int firstMatchCost = 1000000000;

    logInfo("findtrack with airDistanceCostFactor=" + airDistanceCostFactor);
    if (costCuttingTrack != null) logInfo("costCuttingTrack.cost=" + costCuttingTrack.cost);

    matchPath = null;
    int nodesVisited = 0;

    long startNodeId1 = startWp.node1.getIdFromPos();
    long startNodeId2 = startWp.node2.getIdFromPos();
    long endNodeId1 = endWp == null ? -1L : endWp.node1.getIdFromPos();
    long endNodeId2 = endWp == null ? -1L : endWp.node2.getIdFromPos();
    OsmNode end1 = null;
    OsmNode end2 = null;
    OsmNodeNamed endPos = null;

    boolean sameSegmentSearch = false;
    OsmNode start1 = nodesCache.getGraphNode(startWp.node1);
    OsmNode start2 = nodesCache.getGraphNode(startWp.node2);
    if (endWp != null) {
      end1 = nodesCache.getGraphNode(endWp.node1);
      end2 = nodesCache.getGraphNode(endWp.node2);
      nodesCache.nodesMap.endNode1 = end1;
      nodesCache.nodesMap.endNode2 = end2;
      endPos = new OsmNodeNamed(endWp.crosspoint);
      sameSegmentSearch = (start1 == end1 && start2 == end2) || (start1 == end2 && start2 == end1);
    }
    if (!nodesCache.obtainNonHollowNode(start1)) {
      return null;
    }
    nodesCache.expandHollowLinkTargets(start1);
    if (!nodesCache.obtainNonHollowNode(start2)) {
      return null;
    }
    nodesCache.expandHollowLinkTargets(start2);


    routingContext.startDirectionValid = routingContext.forceUseStartDirection || fastPartialRecalc;
    routingContext.startDirectionValid &= routingContext.startDirection != null && !routingContext.inverseDirection;
    if (routingContext.startDirectionValid) {
      logInfo("using start direction " + routingContext.startDirection);
    }

    OsmPath startPath1 = getStartPath(start1, start2, startWp, endPos, sameSegmentSearch);
    OsmPath startPath2 = getStartPath(start2, start1, startWp, endPos, sameSegmentSearch);

    // check for an INITIAL match with the cost-cutting-track
    if (costCuttingTrack != null) {
      OsmPathElement pe1 = costCuttingTrack.getLink(startNodeId1, startNodeId2);
      if (pe1 != null) {
        logInfo("initialMatch pe1.cost=" + pe1.cost);
        int c = startPath1.cost - pe1.cost;
        if (c < 0) c = 0;
        if (c < firstMatchCost) firstMatchCost = c;
      }

      OsmPathElement pe2 = costCuttingTrack.getLink(startNodeId2, startNodeId1);
      if (pe2 != null) {
        logInfo("initialMatch pe2.cost=" + pe2.cost);
        int c = startPath2.cost - pe2.cost;
        if (c < 0) c = 0;
        if (c < firstMatchCost) firstMatchCost = c;
      }

      if (firstMatchCost < 1000000000)
        logInfo("firstMatchCost from initial match=" + firstMatchCost);
    }

    if (startPath1 == null) return null;
    if (startPath2 == null) return null;

    synchronized (openSet) {
      openSet.clear();
      addToOpenset(startPath1);
      addToOpenset(startPath2);
    }
    List<OsmPath> openBorderList = new ArrayList<>(4096);
    boolean memoryPanicMode = false;
    boolean needNonPanicProcessing = false;

    for (; ; ) {
      if (terminated) {
        throw new IllegalArgumentException("operation killed by thread-priority-watchdog after " + (System.currentTimeMillis() - startTime) / 1000 + " seconds");
      }

      if (maxRunningTime > 0) {
        long timeout = (matchPath == null && fastPartialRecalc) ? maxRunningTime / 3 : maxRunningTime;
        if (System.currentTimeMillis() - startTime > timeout) {
          throw new IllegalArgumentException(operationName + " timeout after " + (timeout / 1000) + " seconds");
        }
      }

      synchronized (openSet) {

        OsmPath path = openSet.popLowestKeyValue();
        if (path == null) {
          if (openBorderList.isEmpty()) {
            break;
          }
          for (OsmPath p : openBorderList) {
            openSet.add(p.cost + (int) (p.airdistance * airDistanceCostFactor), p);
          }
          openBorderList.clear();
          memoryPanicMode = false;
          needNonPanicProcessing = true;
          continue;
        }

        if (path.airdistance == -1) {
          continue;
        }

        if (directWeaving && nodesCache.hasHollowLinkTargets(path.getTargetNode())) {
          if (!memoryPanicMode) {
            if (!nodesCache.nodesMap.isInMemoryBounds(openSet.getSize(), false)) {
              int nodesBefore = nodesCache.nodesMap.nodesCreated;
              int pathsBefore = openSet.getSize();

              nodesCache.nodesMap.collectOutreachers();
              for (; ; ) {
                OsmPath p3 = openSet.popLowestKeyValue();
                if (p3 == null) break;
                if (p3.airdistance != -1 && nodesCache.nodesMap.canEscape(p3.getTargetNode())) {
                  openBorderList.add(p3);
                }
              }
              nodesCache.nodesMap.clearTemp();
              for (OsmPath p : openBorderList) {
                openSet.add(p.cost + (int) (p.airdistance * airDistanceCostFactor), p);
              }
              openBorderList.clear();
              logInfo("collected, nodes/paths before=" + nodesBefore + "/" + pathsBefore + " after=" + nodesCache.nodesMap.nodesCreated + "/" + openSet.getSize() + " maxTotalCost=" + maxTotalCost);
              if (!nodesCache.nodesMap.isInMemoryBounds(openSet.getSize(), true)) {
                if (maxTotalCost < 1000000000 || needNonPanicProcessing || fastPartialRecalc) {
                  throw new IllegalArgumentException("memory limit reached");
                }
                memoryPanicMode = true;
                logInfo("************************ memory limit reached, enabled memory panic mode *************************");
              }
            }
          }
          if (memoryPanicMode) {
            openBorderList.add(path);
            continue;
          }
        }
        needNonPanicProcessing = false;


        if (fastPartialRecalc && matchPath != null && path.cost > 30L * firstMatchCost && !costCuttingTrack.isDirty) {
          logInfo("early exit: firstMatchCost=" + firstMatchCost + " path.cost=" + path.cost);

          // use an early exit, unless there's a realistc chance to complete within the timeout
          if (path.cost > maxTotalCost / 2 && System.currentTimeMillis() - startTime < maxRunningTime / 3) {
            logInfo("early exit supressed, running for completion, resetting timeout");
            startTime = System.currentTimeMillis();
            fastPartialRecalc = false;
          } else {
            throw new IllegalArgumentException("early exit for a close recalc");
          }
        }

        if (nodeLimit > 0) { // check node-limit for target island search
          if (--nodeLimit == 0) {
            return null;
          }
        }

        nodesVisited++;
        linksProcessed++;

        OsmLink currentLink = path.getLink();
        OsmNode sourceNode = path.getSourceNode();
        OsmNode currentNode = path.getTargetNode();

        if (currentLink.isLinkUnused()) {
          continue;
        }

        long currentNodeId = currentNode.getIdFromPos();
        long sourceNodeId = sourceNode.getIdFromPos();

        if (!path.didEnterDestinationArea()) {
          islandNodePairs.addTempPair(sourceNodeId, currentNodeId);
        }

        if (path.treedepth != 1) {
          if (path.treedepth == 0) { // hack: sameSegment Paths marked treedepth=0 to pass above check
            path.treedepth = 1;
          }

          if ((sourceNodeId == endNodeId1 && currentNodeId == endNodeId2)
            || (sourceNodeId == endNodeId2 && currentNodeId == endNodeId1)) {
            // track found, compile
            logInfo("found track at cost " + path.cost + " nodesVisited = " + nodesVisited);
            OsmTrack t = compileTrack(path, verbose);
            t.showspeed = routingContext.showspeed;
            t.showSpeedProfile = routingContext.showSpeedProfile;
            return t;
          }

          // check for a match with the cost-cutting-track
          if (costCuttingTrack != null) {
            OsmPathElement pe = costCuttingTrack.getLink(sourceNodeId, currentNodeId);
            if (pe != null) {
              // remember first match cost for fast termination of partial recalcs
              int parentcost = path.originElement == null ? 0 : path.originElement.cost;

              // hitting start-element of costCuttingTrack?
              int c = path.cost - parentcost - pe.cost;
              if (c > 0) parentcost += c;

              if (parentcost < firstMatchCost) firstMatchCost = parentcost;

              int costEstimate = path.cost
                + path.elevationCorrection()
                + (costCuttingTrack.cost - pe.cost);
              if (costEstimate <= maxTotalCost) {
                matchPath = OsmPathElement.create(path);
              }
              if (costEstimate < maxTotalCost) {
                logInfo("maxcost " + maxTotalCost + " -> " + costEstimate);
                maxTotalCost = costEstimate;
              }
            }
          }
        }

        OsmLinkHolder firstLinkHolder = currentLink.getFirstLinkHolder(sourceNode);
        for (OsmLinkHolder linkHolder = firstLinkHolder; linkHolder != null; linkHolder = linkHolder.getNextForLink()) {
          ((OsmPath) linkHolder).airdistance = -1; // invalidate the entry in the open set;
        }

        if (path.treedepth > 1) {
          boolean isBidir = currentLink.isBidirectional();
          sourceNode.unlinkLink(currentLink);

          // if the counterlink is alive and does not yet have a path, remove it
          if (isBidir && currentLink.getFirstLinkHolder(currentNode) == null && !routingContext.considerTurnRestrictions) {
            currentNode.unlinkLink(currentLink);
          }
        }

        // recheck cutoff before doing expensive stuff
        int addDiff = 100;
        if (path.cost + path.airdistance > maxTotalCost + addDiff) {
          continue;
        }

        nodesCache.nodesMap.currentMaxCost = maxTotalCost;
        nodesCache.nodesMap.currentPathCost = path.cost;
        nodesCache.nodesMap.destination = endPos;

        routingContext.firstPrePath = null;

        for (OsmLink link = currentNode.firstlink; link != null; link = link.getNext(currentNode)) {
          OsmNode nextNode = link.getTarget(currentNode);

          if (!nodesCache.obtainNonHollowNode(nextNode)) {
            continue; // border node?
          }
          if (nextNode.firstlink == null) {
            continue; // don't care about dead ends
          }
          if (nextNode == sourceNode) {
            continue; // border node?
          }

          OsmPrePath prePath = routingContext.createPrePath(path, link);
          if (prePath != null) {
            prePath.next = routingContext.firstPrePath;
            routingContext.firstPrePath = prePath;
          }
        }

        for (OsmLink link = currentNode.firstlink; link != null; link = link.getNext(currentNode)) {
          OsmNode nextNode = link.getTarget(currentNode);

          if (!nodesCache.obtainNonHollowNode(nextNode)) {
            continue; // border node?
          }
          if (nextNode.firstlink == null) {
            continue; // don't care about dead ends
          }
          if (nextNode == sourceNode) {
            continue; // border node?
          }

          if (guideTrack != null) {
            int gidx = path.treedepth + 1;
            if (gidx >= guideTrack.nodes.size()) {
              continue;
            }
            OsmPathElement guideNode = guideTrack.nodes.get(routingContext.inverseRouting ? guideTrack.nodes.size() - 1 - gidx : gidx);
            long nextId = nextNode.getIdFromPos();
            if (nextId != guideNode.getIdFromPos()) {
              // not along the guide-track, discard, but register for voice-hint processing
              if (routingContext.turnInstructionMode > 0) {
                OsmPath detour = routingContext.createPath(path, link, refTrack, true);
                if (detour.cost >= 0. && nextId != startNodeId1 && nextId != startNodeId2) {
                  guideTrack.registerDetourForId(currentNode.getIdFromPos(), OsmPathElement.create(detour));
                }
              }
              continue;
            }
          }

          OsmPath bestPath = null;

          boolean isFinalLink = false;
          long targetNodeId = nextNode.getIdFromPos();
          if (currentNodeId == endNodeId1 || currentNodeId == endNodeId2) {
            if (targetNodeId == endNodeId1 || targetNodeId == endNodeId2) {
              isFinalLink = true;
            }
          }

          for (OsmLinkHolder linkHolder = firstLinkHolder; linkHolder != null; linkHolder = linkHolder.getNextForLink()) {
            OsmPath otherPath = (OsmPath) linkHolder;
            try {
              if (isFinalLink) {
                endPos.radius = 1.5; // 1.5 meters is the upper limit that will not change the unit-test result..
                routingContext.setWaypoint(endPos, true);
              }
              OsmPath testPath = routingContext.createPath(otherPath, link, refTrack, guideTrack != null);
              if (testPath.cost >= 0 && (bestPath == null || testPath.cost < bestPath.cost) &&
                (testPath.sourceNode.getIdFromPos() != testPath.targetNode.getIdFromPos())) {
                bestPath = testPath;
              }
            } finally {
              if (isFinalLink) {
                routingContext.unsetWaypoint();
              }
            }
          }
          if (bestPath != null) {
            bestPath.airdistance = isFinalLink ? 0 : nextNode.calcDistance(endPos);

            boolean inRadius = boundary == null || boundary.isInBoundary(nextNode, bestPath.cost);

            if (inRadius && (isFinalLink || bestPath.cost + bestPath.airdistance <= (lastAirDistanceCostFactor != 0. ? maxTotalCost * lastAirDistanceCostFactor : maxTotalCost) + addDiff)) {
              // add only if this may beat an existing path for that link
              OsmLinkHolder dominator = link.getFirstLinkHolder(currentNode);
              while (dominator != null) {
                OsmPath dp = (OsmPath) dominator;
                if (dp.airdistance != -1 && bestPath.definitlyWorseThan(dp)) {
                  break;
                }
                dominator = dominator.getNextForLink();
              }

              if (dominator == null) {
                bestPath.treedepth = path.treedepth + 1;
                link.addLinkHolder(bestPath, currentNode);
                addToOpenset(bestPath);
              }
            }
          }
        }
      }
    }

    if (nodesVisited < MAXNODES_ISLAND_CHECK && islandNodePairs.getFreezeCount() < 5) {
      throw new RoutingIslandException();
    }

    return null;
  }

  private void addToOpenset(OsmPath path) {
    if (path.cost >= 0) {
      openSet.add(path.cost + (int) (path.airdistance * airDistanceCostFactor), path);
    }
  }

  private OsmTrack compileTrack(OsmPath path, boolean verbose) {
    OsmPathElement element = OsmPathElement.create(path);

    // for final track, cut endnode
    if (guideTrack != null && element.origin != null) {
      element = element.origin;
    }

    float totalTime = element.getTime();
    float totalEnergy = element.getEnergy();

    OsmTrack track = new OsmTrack();
    track.cost = path.cost;
    track.energy = (int) path.getTotalEnergy();

    int distance = 0;

    double eleFactor = routingContext.inverseRouting ? -0.25 : 0.25;
    while (element != null) {
      if (guideTrack != null && element.message == null) {
        element.message = new MessageData();
      }
      OsmPathElement nextElement = element.origin;
      // ignore double element
      if (nextElement != null && nextElement.positionEquals(element)) {
        element = nextElement;
        continue;
      }
      if (routingContext.inverseRouting) {
        element.setTime(totalTime - element.getTime());
        element.setEnergy(totalEnergy - element.getEnergy());
        track.nodes.add(element);
      } else {
        track.nodes.add(0, element);
      }

      if (nextElement != null) {
        distance += element.calcDistance(nextElement);
      }
      element = nextElement;
    }
    track.distance = distance;
    logInfo("track-length = " + track.distance);
    track.buildMap();

    // for final track..
    if (guideTrack != null) {
      track.copyDetours(guideTrack);
    }
    return track;
  }

  private OsmTrack mergeTrack(OsmPathElement match, OsmTrack oldTrack) {
    logInfo("**************** merging match=" + match.cost + " with oldTrack=" + oldTrack.cost);
    OsmPathElement element = match;
    OsmTrack track = new OsmTrack();
    track.cost = oldTrack.cost;

    while (element != null) {
      track.addNode(element);
      element = element.origin;
    }
    long lastId = 0;
    long id1 = match.getIdFromPos();
    long id0 = match.origin == null ? 0 : match.origin.getIdFromPos();
    boolean appending = false;
    for (OsmPathElement n : oldTrack.nodes) {
      if (appending) {
        track.nodes.add(n);
      }

      long id = n.getIdFromPos();
      if (id == id1 && lastId == id0) {
        appending = true;
      }
      lastId = id;
    }


    track.buildMap();
    return track;
  }

  public int getPathPeak() {
    synchronized (openSet) {
      return openSet.getPeakSize();
    }
  }

  public int[] getOpenSet() {
    if (extract == null) {
      extract = new Object[500];
    }

    synchronized (openSet) {
      if (guideTrack != null) {
        List<OsmPathElement> nodes = guideTrack.nodes;
        int[] res = new int[nodes.size() * 2];
        int i = 0;
        for (OsmPathElement n : nodes) {
          res[i++] = n.getILon();
          res[i++] = n.getILat();
        }
        return res;
      }

      int size = openSet.getExtract(extract);
      int[] res = new int[size * 2];
      for (int i = 0, j = 0; i < size; i++) {
        OsmPath p = (OsmPath) extract[i];
        extract[i] = null;
        OsmNode n = p.getTargetNode();
        res[j++] = n.ilon;
        res[j++] = n.ilat;
      }
      return res;
    }
  }

  public boolean isFinished() {
    return finished;
  }

  public int getLinksProcessed() {
    return linksProcessed;
  }

  public int getDistance() {
    return foundTrack.distance;
  }

  public int getAscend() {
    return foundTrack.ascend;
  }

  public int getPlainAscend() {
    return foundTrack.plainAscend;
  }

  public String getTime() {
    return Formatter.getFormattedTime2(foundTrack.getTotalSeconds());
  }

  public OsmTrack getFoundTrack() {
    return foundTrack;
  }

  public String getFoundInfo() {
    return outputMessage;
  }

  public int getAlternativeIndex() {
    return alternativeIndex;
  }

  public OsmTrack getFoundRawTrack() {
    return foundRawTrack;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void terminate() {
    terminated = true;
  }

  public boolean isTerminated() {
    return terminated;
  }

  public String getOutfile() {
    return outfile;
  }
}
