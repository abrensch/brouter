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

  NodesCache nodesCache;
  private SortedHeap<OsmPath> openSet = new SortedHeap<>();
  private boolean finished = false;

  protected List<OsmNodeNamed> waypoints = null;
  List<OsmNodeNamed> extraWaypoints = null;
  protected List<MatchedWaypoint> matchedWaypoints;
  private int linksProcessed = 0;

  private int nodeLimit; // used for target island search
  private int MAXNODES_ISLAND_CHECK = 500;
  OsmNodePairSet islandNodePairs = new OsmNodePairSet(MAXNODES_ISLAND_CHECK);
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

  long startTime;
  long maxRunningTime;
  public SearchBoundary boundary;

  public boolean quite = false;

  private Object[] extract;

  private boolean directWeaving = !Boolean.getBoolean("disableDirectWeaving");
  private String outfile;

  double roundTripSearchRadius = 0;

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
      if (direction == -1) {
        direction = getRandomDirectionFromData(waypoints.get(0), searchRadius);
        direction += directionAdd;
      }

      // Resolve effective algorithm
      RoundTripAlgorithm algo = routingContext.roundTripAlgorithm;
      // Legacy compat: if roundTripIsochrone was set but roundTripAlgorithm wasn't explicitly changed
      if (algo == RoundTripAlgorithm.AUTO && routingContext.roundTripIsochrone) {
        algo = RoundTripAlgorithm.ISOCHRONE;
      }
      if (algo == RoundTripAlgorithm.AUTO) {
        algo = selectRoundTripAlgorithm(searchRadius);
      }
      logInfo("round trip algorithm: " + algo);

      if (algo == RoundTripAlgorithm.GREEDY) {
        doGreedyRoundTrip(searchRadius, direction);
      } else {
        doWaypointBasedRoundTrip(searchRadius, direction, algo);
      }

      // Check distance accuracy and warn if terrain prevents a compact loop
      if (foundTrack != null && foundTrack.distance > 0) {
        double expectedDistance = 2 * Math.PI * searchRadius;
        double ratio = foundTrack.distance / expectedDistance;
        if (ratio > 1.5) {
          String warning = String.format(
            "Warning: route distance (%dkm) exceeds requested loop distance (%dkm) by %.0f%%. "
            + "The road network in this area is too constrained for a compact loop at this distance. "
            + "Consider a shorter distance or an out-and-back route.",
            foundTrack.distance / 1000, (int) (expectedDistance / 1000), (ratio - 1) * 100);
          logInfo(warning);
          if (foundTrack.message == null) {
            foundTrack.message = warning;
          } else {
            foundTrack.message += " " + warning;
          }
        }
      }

      long endTime = System.currentTimeMillis();
      logInfo("round trip execution time = " + (endTime - startTime) / 1000. + " seconds");
    } catch (Exception e) {
      e.getStackTrace();
      logException(e);
    }

  }

  static RoundTripAlgorithm selectRoundTripAlgorithm(double searchRadius) {
    // Greedy excels at medium-long loops where geometry matters.
    // searchRadius maps to total loop distance ~ 2*PI*radius.
    // For short loops (< ~30km total, radius < 5km), existing strategies work well.
    if (searchRadius >= 5000) return RoundTripAlgorithm.GREEDY;
    return RoundTripAlgorithm.ISOCHRONE;
  }

  private void doWaypointBasedRoundTrip(double searchRadius, double direction, RoundTripAlgorithm algo) {
    if (routingContext.allowSamewayback) {
      int[] pos = CheapRuler.destination(waypoints.get(0).ilon, waypoints.get(0).ilat, searchRadius, direction);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt1";
      waypoints.add(onn);
    } else {
      List<OsmNodeNamed> userViaPoints = new ArrayList<>(waypoints.subList(1, waypoints.size()));
      waypoints.subList(1, waypoints.size()).clear();

      int targetPoints = routingContext.roundTripPoints == null ?
        Math.max(5, Math.min(15, (int) (searchRadius / 1500) + 3)) :
        routingContext.roundTripPoints;

      if (algo == RoundTripAlgorithm.ISOCHRONE) {
        double[] probeDirections = probeReachableDirections(waypoints.get(0), searchRadius);
        double[][] frontier = runIsochroneExpansion(waypoints.get(0), searchRadius);
        double[][] merged = mergeIsochroneWithProbe(frontier, probeDirections, searchRadius);
        if (merged != null && merged.length >= 3) {
          placeWaypointsFromIsochrone(waypoints, merged, searchRadius, direction, targetPoints);
        } else if (probeDirections != null && probeDirections.length >= 3) {
          logInfo("isochrone merge insufficient, falling back to probe directions");
          placeWaypointsFromEnvelope(waypoints, probeDirections, searchRadius, direction, targetPoints);
        } else {
          logInfo("both isochrone and probe insufficient, falling back to circle");
          buildPointsFromCircle(waypoints, direction, searchRadius, targetPoints);
        }
      } else {
        double[] viableDirections = probeReachableDirections(waypoints.get(0), searchRadius);
        if (viableDirections != null && viableDirections.length >= 3) {
          placeWaypointsFromEnvelope(waypoints, viableDirections, searchRadius, direction, targetPoints);
        } else {
          logInfo("reachability probe returned < 3 directions, falling back to circle");
          buildPointsFromCircle(waypoints, direction, searchRadius, targetPoints);
        }
      }

      validateAndAdjustWaypoints(waypoints, searchRadius);

      if (!userViaPoints.isEmpty()) {
        mergeUserWaypointsIntoLoop(waypoints, userViaPoints, direction, targetPoints);
      }
    }

    routingContext.waypointCatchingRange = 250;
    roundTripSearchRadius = searchRadius;
    doRouting(0);
  }

  void doGreedyRoundTrip(double searchRadius, double direction) {
    // Initialize nodesCache — needed before the planner can match waypoints to the graph.
    // Other strategies (probe, isochrone) initialize this as a side effect of their own
    // resetCache calls, but the greedy path goes straight to the planner.
    resetCache(false);

    OsmNodeNamed start = waypoints.get(0);
    double desiredDistance = 2 * Math.PI * searchRadius;
    logInfo("greedy round trip: desired distance=" + (int) desiredDistance
      + "m, searchRadius=" + (int) searchRadius + "m, direction=" + (int) direction);

    GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(this);
    RoundTripResult result = planner.plan(start, desiredDistance, direction);

    if (result != null && result.getTrack() != null) {
      foundTrack = result.getTrack();
      for (String diag : result.getDiagnostics()) {
        logInfo("greedy: " + diag);
      }
      if (!result.isWithinTolerance()) {
        logInfo("greedy: fallback used — " + result.getFallbackReason());
      }
      logInfo("greedy: distance=" + result.getTotalDistanceMeters()
        + "m, subRoutes=" + result.getSubRoutesChosen()
        + ", attempts=" + result.getAttemptsUsed()
        + ", reuse=" + String.format("%.1f%%", result.getReusedEdgeRatio() * 100));
    } else {
      logInfo("greedy round trip planner returned no result, falling back to waypoint strategy");
      doWaypointBasedRoundTrip(searchRadius, direction, RoundTripAlgorithm.WAYPOINT);
    }
  }

  /**
   * Merge user-provided via waypoints into the round-trip loop.
   * Inserts user waypoints among the circle-generated waypoints,
   * sorted by bearing angle from the start point. If the total
   * number of intermediate waypoints exceeds targetPoints,
   * circle waypoints closest in angle to a user waypoint are removed.
   */
  void mergeUserWaypointsIntoLoop(List<OsmNodeNamed> waypoints, List<OsmNodeNamed> userViaPoints, double startAngle, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    OsmNodeNamed closingPoint = waypoints.remove(waypoints.size() - 1);
    List<OsmNodeNamed> circlePoints = new ArrayList<>(waypoints.subList(1, waypoints.size()));
    waypoints.subList(1, waypoints.size()).clear();

    for (int i = 0; i < userViaPoints.size(); i++) {
      OsmNodeNamed wp = userViaPoints.get(i);
      if (wp.name == null || wp.name.isEmpty()) {
        wp.name = "via" + (i + 1);
      }
    }

    // Precompute user waypoint bearings (constant across removal iterations)
    double[] userBearings = new double[userViaPoints.size()];
    for (int i = 0; i < userViaPoints.size(); i++) {
      userBearings[i] = CheapAngleMeter.getDirection(start.ilon, start.ilat,
        userViaPoints.get(i).ilon, userViaPoints.get(i).ilat);
    }

    int maxCirclePoints = Math.max(1, targetPoints - userViaPoints.size());
    while (circlePoints.size() > maxCirclePoints) {
      int removeIdx = -1;
      double smallestGap = Double.MAX_VALUE;
      for (int ci = 0; ci < circlePoints.size(); ci++) {
        double circBearing = CheapAngleMeter.getDirection(start.ilon, start.ilat,
          circlePoints.get(ci).ilon, circlePoints.get(ci).ilat);
        for (double userBearing : userBearings) {
          double gap = CheapAngleMeter.getDifferenceFromDirection(circBearing, userBearing);
          if (gap < smallestGap) {
            smallestGap = gap;
            removeIdx = ci;
          }
        }
      }
      logInfo("mergeUserWaypoints: removing circle point " + circlePoints.get(removeIdx).name
        + " (angular gap " + (int) smallestGap + "° to nearest user waypoint)");
      circlePoints.remove(removeIdx);
    }

    List<OsmNodeNamed> allIntermediates = new ArrayList<>();
    allIntermediates.addAll(circlePoints);
    allIntermediates.addAll(userViaPoints);

    allIntermediates.sort((a, b) -> {
      double relA = CheapAngleMeter.normalizeRelative(
        CheapAngleMeter.getDirection(start.ilon, start.ilat, a.ilon, a.ilat) - startAngle);
      double relB = CheapAngleMeter.normalizeRelative(
        CheapAngleMeter.getDirection(start.ilon, start.ilat, b.ilon, b.ilat) - startAngle);
      return Double.compare(relA, relB);
    });

    waypoints.addAll(allIntermediates);
    waypoints.add(closingPoint);

    logInfo("mergeUserWaypoints: loop has " + allIntermediates.size() + " intermediate waypoints ("
      + userViaPoints.size() + " user, " + circlePoints.size() + " circle)");
  }

  /**
   * Filter round-trip waypoints that matched to bad positions.
   * Removes intermediate waypoints (named "rt*") where:
   * - The snap distance (waypoint to crosspoint) exceeds maxSnapDistance
   * - Consecutive matched positions are too close together
   * Never removes the first or last waypoint (start/end of loop).
   * Preserves at least one intermediate waypoint.
   */
  void filterRoundTripWaypoints(List<MatchedWaypoint> waypoints) {
    double maxSnapDistance = roundTripSearchRadius * 0.5;
    double minWaypointDistance = roundTripSearchRadius / 10.0;
    // Max edge length between node1 and node2 for a valid road match.
    // Ferry routes have sparse nodes with multi-km edges; road segments are typically < 1km.
    int maxSegmentLength = 1500;

    // Count intermediate round-trip waypoints
    int rtCount = 0;
    for (int i = 1; i < waypoints.size() - 1; i++) {
      if (waypoints.get(i).name != null && waypoints.get(i).name.startsWith("rt")) {
        rtCount++;
      }
    }

    // Remove waypoints matched to ferry-like segments (very long edges)
    for (int i = waypoints.size() - 2; i >= 1; i--) {
      MatchedWaypoint mwp = waypoints.get(i);
      if (mwp.name == null || !mwp.name.startsWith("rt")) continue;
      if (rtCount <= 1) break;

      if (mwp.node1 != null && mwp.node2 != null) {
        int segLen = mwp.node1.calcDistance(mwp.node2);
        if (segLen > maxSegmentLength) {
          logInfo("filterRoundTrip: removing " + mwp.name + " matched to long segment (" + segLen + "m, likely ferry)");
          waypoints.remove(i);
          rtCount--;
        }
      }
    }

    // Remove waypoints that snapped too far
    for (int i = waypoints.size() - 2; i >= 1; i--) {
      MatchedWaypoint mwp = waypoints.get(i);
      if (mwp.name == null || !mwp.name.startsWith("rt")) continue;
      if (rtCount <= 1) break; // preserve at least one intermediate waypoint

      if (mwp.radius > maxSnapDistance) {
        logInfo("filterRoundTrip: removing " + mwp.name + " snap=" + (int) mwp.radius + "m > max=" + (int) maxSnapDistance + "m");
        waypoints.remove(i);
        rtCount--;
      }
    }

    if (rtCount <= 1) return;

    // Remove consecutive round-trip waypoints that matched too close together
    for (int i = waypoints.size() - 2; i >= 2; i--) {
      if (rtCount <= 1) break;
      MatchedWaypoint curr = waypoints.get(i);
      MatchedWaypoint prev = waypoints.get(i - 1);
      if (curr.name == null || !curr.name.startsWith("rt")) continue;
      if (prev.name == null || !prev.name.startsWith("rt")) continue;

      double dist = curr.crosspoint.calcDistance(prev.crosspoint);
      if (dist < minWaypointDistance) {
        logInfo("filterRoundTrip: removing " + curr.name + " too close to " + prev.name + " dist=" + (int) dist + "m");
        waypoints.remove(i);
        rtCount--;
      }
    }
  }

  /**
   * Snap intermediate roundtrip waypoints to the nearest intersection node.
   * When a waypoint is matched to the middle of an edge (crosspoint between
   * node1 and node2), routing to that mid-edge point can create small
   * out-and-back tails. By moving the crosspoint to the closer of node1/node2
   * (a real intersection), we avoid these tails entirely.
   * This is analogous to GraphHopper's getBaseNode() approach.
   * Only applied to generated roundtrip waypoints (rt*), not user waypoints
   * or the start/end points.
   */
  void snapToIntersection(List<MatchedWaypoint> waypoints) {
    for (int i = 1; i < waypoints.size() - 1; i++) {
      MatchedWaypoint mwp = waypoints.get(i);
      if (mwp.name == null || !mwp.name.startsWith("rt")) continue;
      if (mwp.node1 == null || mwp.node2 == null || mwp.crosspoint == null) continue;

      int distToNode1 = mwp.crosspoint.calcDistance(mwp.node1);
      int distToNode2 = mwp.crosspoint.calcDistance(mwp.node2);
      OsmNode closerNode = distToNode1 <= distToNode2 ? mwp.node1 : mwp.node2;

      logInfo("snapToIntersection: " + mwp.name + " moved crosspoint "
        + (distToNode1 <= distToNode2 ? distToNode1 : distToNode2) + "m to nearest intersection");
      mwp.crosspoint = new OsmNode(closerNode.ilon, closerNode.ilat);
    }
  }

  /**
   * Validate round-trip waypoints against segment data before routing.
   * For each generated waypoint, checks if profile-compatible ways exist
   * nearby by matching against the routing segments. Waypoints that fall
   * in unreachable areas (water, no compatible ways) are relocated by
   * trying alternative positions at varied angles and distances from the
   * start point. Unmatched waypoints are removed if enough remain.
   *
   * The matching is profile-aware: during segment decoding, only ways
   * with accessType >= 2 (compatible with the current routing profile)
   * are considered.
   */
  void validateAndAdjustWaypoints(List<OsmNodeNamed> waypoints, double searchRadius) {
    resetCache(false);
    OsmNodeNamed start = waypoints.get(0);
    double maxSnapDist = Math.min(searchRadius * 0.3, 2000);

    double[] angleOffsets = {0, -15, 15, -30, 30};
    double[] distFactors = {1.0, 0.8, 1.2, 0.6, 1.4};

    int intermediateCount = waypoints.size() - 2;
    List<List<MatchedWaypoint>> candidateGroups = new ArrayList<>();
    List<MatchedWaypoint> allCandidates = new ArrayList<>();

    for (int i = 1; i <= intermediateCount; i++) {
      OsmNodeNamed wp = waypoints.get(i);
      double[] scales = CheapRuler.getLonLatToMeterScales((start.ilat + wp.ilat) >> 1);
      double bearing = Math.toDegrees(Math.atan2(
        (wp.ilon - start.ilon) * scales[0],
        (wp.ilat - start.ilat) * scales[1]));
      double dist = CheapRuler.distance(start.ilon, start.ilat, wp.ilon, wp.ilat);

      List<MatchedWaypoint> group = new ArrayList<>();

      for (double da : angleOffsets) {
        for (double df : distFactors) {
          // skip extreme combinations to limit candidate count
          if (Math.abs(da) > 15 && Math.abs(df - 1.0) > 0.25) continue;

          int ilon, ilat;
          if (da == 0 && df == 1.0) {
            ilon = wp.ilon;
            ilat = wp.ilat;
          } else {
            int[] pos = CheapRuler.destination(start.ilon, start.ilat, dist * df, bearing + da);
            ilon = pos[0];
            ilat = pos[1];
          }

          MatchedWaypoint mwp = new MatchedWaypoint();
          mwp.waypoint = new OsmNode(ilon, ilat);
          mwp.name = wp.name + "_c" + group.size();
          group.add(mwp);
          allCandidates.add(mwp);
        }
      }

      candidateGroups.add(group);
    }

    // Match all candidates at once — profile-aware via segment decoding
    nodesCache.matchWaypointsToNodes(allCandidates, maxSnapDist, islandNodePairs);

    // Pick the best candidate for each waypoint or remove it.
    // Use direction-aware scoring: prefer roads perpendicular to the travel
    // direction (the route naturally crosses them) over parallel roads
    // (which often require a detour to reach).
    int minWaypoints = 3;
    int remaining = intermediateCount;

    for (int i = intermediateCount; i >= 1; i--) {
      List<MatchedWaypoint> group = candidateGroups.get(i - 1);

      // Travel bearing: direction from previous to next waypoint
      OsmNodeNamed prev = waypoints.get(i - 1);
      OsmNodeNamed next = waypoints.get(i + 1);
      double travelBearing = CheapAngleMeter.getDirection(prev.ilon, prev.ilat, next.ilon, next.ilat);

      MatchedWaypoint best = null;
      double bestScore = Double.MAX_VALUE;
      for (MatchedWaypoint mwp : group) {
        if (mwp.crosspoint == null) continue;

        // Skip matches to ferry-like segments (very long edges between node1/node2).
        // Ferry routes have sparse nodes spanning several km over water; road edges are < 1km.
        if (mwp.node1 != null && mwp.node2 != null && mwp.node1.calcDistance(mwp.node2) > 1500) {
          continue;
        }

        double snapDist = mwp.radius;

        // Road bearing at snap point
        double roadBearing = CheapAngleMeter.getDirection(
          mwp.node1.ilon, mwp.node1.ilat, mwp.node2.ilon, mwp.node2.ilat);

        // Angle between road and travel direction (0-90°, road is bidirectional)
        double angleDiff = CheapAngleMeter.getDifferenceFromDirection(roadBearing, travelBearing);
        if (angleDiff > 90) angleDiff = 180 - angleDiff;

        // parallelFactor: 1.0 for parallel, 0.0 for perpendicular
        double parallelFactor = 1.0 - angleDiff / 90.0;

        // Penalize parallel roads: effective snap distance increases up to 50%.
        // This favors roads the route would naturally cross without detour.
        double score = snapDist * (1.0 + 0.5 * parallelFactor * parallelFactor);

        if (score < bestScore) {
          best = mwp;
          bestScore = score;
        }
      }

      OsmNodeNamed wp = waypoints.get(i);
      if (best != null && best.radius <= maxSnapDist) {
        if (wp.ilon != best.waypoint.ilon || wp.ilat != best.waypoint.ilat) {
          logInfo("validateWaypoints: relocated " + wp.name + " snap=" + (int) best.radius + "m");
          wp.ilon = best.waypoint.ilon;
          wp.ilat = best.waypoint.ilat;
        }
      } else if (remaining > minWaypoints) {
        logInfo("validateWaypoints: removing unreachable " + wp.name
          + " (best=" + (best == null ? "none" : (int) best.radius + "m") + ")");
        waypoints.remove(i);
        remaining--;
      } else {
        logInfo("validateWaypoints: keeping marginal " + wp.name + " (min waypoint count reached)");
      }
    }
    logInfo("validateWaypoints: " + remaining + "/" + intermediateCount + " waypoints validated");
  }

  /**
   * Remove back-and-forth segments from a round-trip track.
   * At each waypoint boundary, the route may retrace the same road
   * it arrived on before diverging. This method detects such overlaps
   * by walking outward from each waypoint in both directions and
   * comparing node positions. The overlapping nodes on the outgoing
   * side are removed, keeping the waypoint itself.
   */
  void removeBackAndForthSegments(OsmTrack track, List<MatchedWaypoint> waypoints) {
    List<OsmPathElement> nodes = track.nodes;

    for (int wi = 1; wi < waypoints.size() - 1; wi++) {
      int wptIdx = waypoints.get(wi).indexInTrack;
      if (wptIdx <= 0 || wptIdx >= nodes.size() - 1) continue;

      int overlapCount = 0;
      int proximityThreshold = 30; // meters — catch near-overlaps (dual carriageways, parallel roads)
      int maxSteps = Math.min(wptIdx, nodes.size() - 1 - wptIdx);
      for (int step = 1; step <= maxSteps; step++) {
        OsmPathElement before = nodes.get(wptIdx - step);
        OsmPathElement after = nodes.get(wptIdx + step);
        if (before.getIdFromPos() == after.getIdFromPos()
            || before.calcDistance(after) <= proximityThreshold) {
          overlapCount = step;
        } else {
          break;
        }
      }

      if (overlapCount > 0) {
        logInfo("removeBackAndForth: at waypoint " + waypoints.get(wi).name
          + " removing " + overlapCount + " overlapping nodes");
        nodes.subList(wptIdx + 1, wptIdx + overlapCount + 1).clear();
        for (int wj = wi + 1; wj < waypoints.size(); wj++) {
          waypoints.get(wj).indexInTrack -= overlapCount;
        }
      }
    }
  }

  /**
   * Remove micro-detours: small loops where the route returns to the same
   * area within a short distance. Uses proximity matching (not just exact
   * node identity) to catch detours through parallel roads or dual
   * carriageways where the route returns to a nearby but distinct node.
   *
   * @param maxLoopDistance maximum route distance of a loop to be considered a micro-detour (in meters)
   */
  void removeMicroDetours(OsmTrack track, int maxLoopDistance, List<MatchedWaypoint> waypoints) {
    List<OsmPathElement> nodes = track.nodes;
    int proximityThreshold = 50; // meters — catch returns to nearby (not just identical) nodes
    // Grid cell size in internal coords: ~35-55m depending on latitude.
    // Checking the 9-cell neighborhood covers up to ~100-160m, well above the proximity threshold.
    int cellSize = 500;
    int waypointProximity = 200; // meters — relaxed ratio zone around generated waypoints
    boolean changed = true;

    while (changed) {
      changed = false;
      Map<Long, List<Integer>> grid = new HashMap<>();

      for (int i = 0; i < nodes.size(); i++) {
        int ilon = nodes.get(i).getILon();
        int ilat = nodes.get(i).getILat();
        int cx = ilon / cellSize;
        int cy = ilat / cellSize;

        // Search the 9-cell neighborhood for the closest previously visited node
        // (by distance, ties broken by latest index = shortest loop)
        int matchIdx = -1;
        int matchDist = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
          for (int dy = -1; dy <= 1; dy++) {
            long neighborCell = ((long) (cx + dx)) << 32 | ((cy + dy) & 0xFFFFFFFFL);
            List<Integer> entries = grid.get(neighborCell);
            if (entries != null) {
              for (int idx : entries) {
                int dist = nodes.get(idx).calcDistance(nodes.get(i));
                if (dist <= proximityThreshold && (dist < matchDist || (dist == matchDist && idx > matchIdx))) {
                  matchIdx = idx;
                  matchDist = dist;
                }
              }
            }
          }
        }

        if (matchIdx >= 0) {
          // Use raw distance for ratio check (calcDistance floors to 1, distorting the ratio)
          double crowFly = CheapRuler.distance(
            nodes.get(matchIdx).getILon(), nodes.get(matchIdx).getILat(),
            nodes.get(i).getILon(), nodes.get(i).getILat());
          int loopDist = 0;
          for (int j = matchIdx + 1; j <= i; j++) {
            loopDist += nodes.get(j).calcDistance(nodes.get(j - 1));
          }

          // Near generated roundtrip waypoints, use a relaxed ratio (2x instead of 3x)
          // since detours there are artifacts of synthetic waypoint placement.
          double ratioThreshold = 3.0;
          if (isNearGeneratedWaypoint(nodes, matchIdx, i, waypoints, waypointProximity)) {
            ratioThreshold = 2.0;
          }

          // A genuine detour has route distance much larger than crow-fly distance
          // (the route went elsewhere and came back). Normal forward progression
          // has route distance ≈ crow-fly distance.
          if (loopDist <= maxLoopDistance && loopDist > 0 && loopDist > crowFly * ratioThreshold) {
            logInfo("removeMicroDetours: removing " + (i - matchIdx) + " nodes (loop of " + loopDist + "m, crow-fly " + (int) crowFly + "m, ratio " + String.format("%.1f", ratioThreshold) + "x at index " + matchIdx + ")");
            int removeCount = i - matchIdx;
            nodes.subList(matchIdx + 1, i + 1).clear();
            adjustWaypointIndices(waypoints, matchIdx, i, removeCount);
            changed = true;
            break; // restart scan since indices shifted
          }
        }

        // Register this node in the grid (even if it matched — we advanced past the match)
        long cell = ((long) cx) << 32 | (cy & 0xFFFFFFFFL);
        grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(i);
      }
    }
  }

  /**
   * Check if a loop (between matchIdx and currentIdx in the track) is near
   * a generated roundtrip waypoint (name starting with "rt").
   */
  private boolean isNearGeneratedWaypoint(List<OsmPathElement> nodes, int matchIdx, int currentIdx,
                                          List<MatchedWaypoint> waypoints, int proximityMeters) {
    // Check both endpoints and midpoint of the loop for proximity to waypoints
    int midIdx = (matchIdx + currentIdx) / 2;
    for (int checkIdx : new int[]{matchIdx, midIdx, currentIdx}) {
      if (checkIdx < 0 || checkIdx >= nodes.size()) continue;
      OsmPathElement refNode = nodes.get(checkIdx);
      for (MatchedWaypoint mwp : waypoints) {
        if (mwp.name == null || !mwp.name.startsWith("rt")) continue;
        if (mwp.crosspoint == null) continue;
        if (refNode.calcDistance(mwp.crosspoint) <= proximityMeters) return true;
      }
    }
    return false;
  }

  /**
   * Adjust waypoint track indices after removing a range of nodes.
   * Waypoints beyond the removed range are shifted back; waypoints
   * inside the range are clamped to rangeStart.
   */
  private static void adjustWaypointIndices(List<MatchedWaypoint> waypoints, int rangeStart, int rangeEnd, int removeCount) {
    for (MatchedWaypoint mwp : waypoints) {
      if (mwp.indexInTrack > rangeEnd) {
        mwp.indexInTrack -= removeCount;
      } else if (mwp.indexInTrack > rangeStart) {
        mwp.indexInTrack = rangeStart;
      }
    }
  }

  /**
   * Probe the surrounding area for road reachability in all directions.
   * Sends probes at 15° intervals (24 directions) at three distances
   * (0.7R, 1.0R, 1.3R) and snaps each to the road network. Returns
   * direction+distance pairs for viable directions, where the distance
   * is the actual distance from start to the best-matched road position.
   *
   * @param start        the start waypoint
   * @param searchRadius the round-trip search radius in meters
   * @return array of viable directions in degrees, sorted ascending; null if probe fails
   */
  double[] probeReachableDirections(OsmNodeNamed start, double searchRadius) {
    resetCache(false);
    double maxSnapDist = Math.min(searchRadius * 0.3, 2000);
    double[] distFactors = {0.7, 1.0, 1.3};
    int probeCount = 24; // every 15 degrees
    double angleStep = 360.0 / probeCount;

    List<MatchedWaypoint> allProbes = new ArrayList<>();
    int probesPerDirection = distFactors.length;

    // Include the start point itself to ensure its segment is loaded
    MatchedWaypoint startProbe = new MatchedWaypoint();
    startProbe.waypoint = new OsmNode(start.ilon, start.ilat);
    startProbe.name = "probe_start";
    allProbes.add(startProbe);

    for (int d = 0; d < probeCount; d++) {
      double angle = d * angleStep;
      for (double df : distFactors) {
        int[] pos = CheapRuler.destination(start.ilon, start.ilat, searchRadius * df, angle);
        MatchedWaypoint mwp = new MatchedWaypoint();
        mwp.waypoint = new OsmNode(pos[0], pos[1]);
        mwp.name = "probe_" + d + "_" + (int) (df * 100);
        allProbes.add(mwp);
      }
    }

    // Batch-match all probes against the road network
    try {
      nodesCache.matchWaypointsToNodes(allProbes, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      logInfo("reachability probe failed: " + e.getMessage());
      return null;
    }

    // Collect viable directions (skip index 0 which is the start probe)
    int probeOffset = 1; // start probe is at index 0
    List<Double> viable = new ArrayList<>();
    for (int d = 0; d < probeCount; d++) {
      for (int f = 0; f < probesPerDirection; f++) {
        MatchedWaypoint mwp = allProbes.get(probeOffset + d * probesPerDirection + f);
        if (mwp.crosspoint != null && mwp.radius <= maxSnapDist) {
          viable.add(d * angleStep);
          break; // one match is enough for this direction
        }
      }
    }

    logInfo("reachability probe: " + viable.size() + "/" + probeCount + " directions viable");
    if (viable.isEmpty()) return null;

    double[] result = new double[viable.size()];
    for (int i = 0; i < viable.size(); i++) result[i] = viable.get(i);
    return result;
  }

  /**
   * Run a cost-limited Dijkstra expansion from the start point to discover
   * the reachable road network frontier in all directions. Returns an array
   * of [direction_degrees, distance_meters] pairs representing the farthest
   * reachable road node in each angular bucket.
   *
   * Uses the match → resetCache → getGraphNode pattern from _findTrack to
   * correctly initialize graph nodes from production segment files.
   *
   * @param start        the start waypoint
   * @param searchRadius the round-trip search radius in meters
   * @return array of [direction, distance] pairs per populated angular bucket; null on failure
   */
  double[][] runIsochroneExpansion(OsmNodeNamed start, double searchRadius) {
    // Phase 1: Match start point (loads segments via directWeaving, consumes node data)
    resetCache(false);
    MatchedWaypoint startMwp = new MatchedWaypoint();
    startMwp.waypoint = new OsmNode(start.ilon, start.ilat);
    startMwp.name = "iso_start";
    List<MatchedWaypoint> mwpList = new ArrayList<>();
    mwpList.add(startMwp);
    double maxSnapDist = Math.min(searchRadius * 0.3, 2000);
    try {
      nodesCache.matchWaypointsToNodes(mwpList, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      logInfo("isochrone: match failed: " + e.getMessage());
      return null;
    }
    if (startMwp.crosspoint == null || startMwp.node1 == null || startMwp.node2 == null) {
      logInfo("isochrone: start match incomplete");
      return null;
    }

    // Phase 2: Reset cache — creates fresh nodesMap but preserves fileRows (cached segments).
    // This is the critical step: matchWaypointsToNodes consumed segment data via directWeaving,
    // so obtainNonHollowNode would fail without this reset. The reset makes the segments
    // re-parseable while keeping file handles open. Same pattern as findTrack → _findTrack.
    resetCache(false);
    nodesCache.nodesMap.cleanupMode = 1;

    // Phase 3: Get graph nodes — now obtainNonHollowNode can re-parse from cached segments
    OsmNode n1 = nodesCache.getGraphNode(startMwp.node1);
    OsmNode n2 = nodesCache.getGraphNode(startMwp.node2);
    if (!nodesCache.obtainNonHollowNode(n1) || !nodesCache.obtainNonHollowNode(n2)) {
      logInfo("isochrone: could not obtain start nodes");
      return null;
    }
    nodesCache.expandHollowLinkTargets(n1);
    nodesCache.expandHollowLinkTargets(n2);

    OsmPath startPath1 = getStartPath(n1, n2, startMwp, null, false);
    OsmPath startPath2 = getStartPath(n2, n1, startMwp, null, false);

    // Cost budget: searchRadius * 4 to ensure the expansion reaches the full searchRadius
    // in all directions. Profile costfactor (~1.3) × road indirectness (~1.5) × safety margin.
    int costBudget = (int) (searchRadius * 4);
    // Geographic cutoff: don't expand beyond 1.5× searchRadius (prevents runaway)
    double geoRadiusCutoff = searchRadius * 1.5;
    int maxNodes = 100000;

    // Angular bucketing: 36 buckets of 10 degrees
    int bucketCount = 36;
    double bucketSize = 360.0 / bucketCount;
    double[] bucketMaxDist = new double[bucketCount];
    int[] bucketCostAtMaxDist = new int[bucketCount]; // routing cost at the farthest node per bucket

    // Local open set — not the instance field, to avoid state contamination
    SortedHeap<OsmPath> isoOpenSet = new SortedHeap<>();
    if (startPath1 != null) isoOpenSet.add(startPath1.cost, startPath1);
    if (startPath2 != null) isoOpenSet.add(startPath2.cost, startPath2);

    int nodesExpanded = 0;

    for (;;) {
      OsmPath path = isoOpenSet.popLowestKeyValue();
      if (path == null) break;
      if (path.airdistance == -1) continue; // invalidated

      // Cost cutoff — Dijkstra: once popped cost exceeds budget, all remaining do too
      if (path.cost > costBudget) break;

      nodesExpanded++;
      if (nodesExpanded > maxNodes) break;

      OsmLink currentLink = path.getLink();
      OsmNode sourceNode = path.getSourceNode();
      OsmNode currentNode = path.getTargetNode();
      if (currentLink.isLinkUnused()) continue;

      // Record this node in angular buckets using true bearing (longitude-scaled)
      double dist = CheapRuler.distance(start.ilon, start.ilat,
        currentNode.getILon(), currentNode.getILat());
      if (dist > 50) { // skip very close nodes (noisy bearings)
        // Use CheapRuler scale factors for correct bearing at all latitudes.
        // CheapAngleMeter.getDirection uses raw ilon/ilat which distorts E-W at high latitudes.
        double[] scales = CheapRuler.getLonLatToMeterScales((start.ilat + currentNode.getILat()) >> 1);
        double dx = (currentNode.getILon() - start.ilon) * scales[0];
        double dy = (currentNode.getILat() - start.ilat) * scales[1];
        double bearing = Math.toDegrees(Math.atan2(dx, dy));
        if (bearing < 0) bearing += 360;
        int bucket = ((int) (bearing / bucketSize)) % bucketCount;
        if (bucket < 0) bucket += bucketCount;
        if (dist > bucketMaxDist[bucket]) {
          bucketMaxDist[bucket] = dist;
          bucketCostAtMaxDist[bucket] = path.cost;
        }
      }

      // Invalidate existing path holders for this link
      OsmLinkHolder firstLinkHolder = currentLink.getFirstLinkHolder(sourceNode);
      for (OsmLinkHolder lh = firstLinkHolder; lh != null; lh = lh.getNextForLink()) {
        ((OsmPath) lh).airdistance = -1;
      }

      // Unlink processed link
      if (path.treedepth > 1) {
        boolean isBidir = currentLink.isBidirectional();
        sourceNode.unlinkLink(currentLink);
        if (isBidir && currentLink.getFirstLinkHolder(currentNode) == null
          && !routingContext.considerTurnRestrictions) {
          currentNode.unlinkLink(currentLink);
        }
      }

      // Don't expand beyond geographic radius
      if (dist > geoRadiusCutoff) continue;

      // Two-pass neighbor expansion (prePath + path creation)
      routingContext.firstPrePath = null;
      for (OsmLink link = currentNode.firstlink; link != null; link = link.getNext(currentNode)) {
        OsmNode nextNode = link.getTarget(currentNode);
        if (!nodesCache.obtainNonHollowNode(nextNode)) continue;
        if (nextNode.firstlink == null) continue;
        if (nextNode == sourceNode) continue;

        OsmPrePath prePath = routingContext.createPrePath(path, link);
        if (prePath != null) {
          prePath.next = routingContext.firstPrePath;
          routingContext.firstPrePath = prePath;
        }
      }

      for (OsmLink link = currentNode.firstlink; link != null; link = link.getNext(currentNode)) {
        OsmNode nextNode = link.getTarget(currentNode);
        if (!nodesCache.obtainNonHollowNode(nextNode)) continue;
        if (nextNode.firstlink == null) continue;
        if (nextNode == sourceNode) continue;

        OsmPath bestPath = null;
        for (OsmLinkHolder lh = firstLinkHolder; lh != null; lh = lh.getNextForLink()) {
          OsmPath otherPath = (OsmPath) lh;
          OsmPath testPath = routingContext.createPath(otherPath, link, null, false);
          if (testPath.cost >= 0 && (bestPath == null || testPath.cost < bestPath.cost)
            && testPath.sourceNode.getIdFromPos() != testPath.targetNode.getIdFromPos()) {
            bestPath = testPath;
          }
        }

        if (bestPath != null) {
          bestPath.airdistance = 0; // pure Dijkstra — no heuristic

          // Domination check
          OsmLinkHolder dominator = link.getFirstLinkHolder(currentNode);
          while (dominator != null) {
            OsmPath dp = (OsmPath) dominator;
            if (dp.airdistance != -1 && bestPath.definitlyWorseThan(dp)) break;
            dominator = dominator.getNextForLink();
          }
          if (dominator == null) {
            bestPath.treedepth = path.treedepth + 1;
            link.addLinkHolder(bestPath, currentNode);
            isoOpenSet.add(bestPath.cost, bestPath);
          }
        }
      }
    }

    // Compile results: [direction, airDistance, routeCost] per populated bucket
    List<double[]> results = new ArrayList<>();
    for (int b = 0; b < bucketCount; b++) {
      if (bucketMaxDist[b] > 0) {
        results.add(new double[]{b * bucketSize + bucketSize / 2.0, bucketMaxDist[b], bucketCostAtMaxDist[b]});
      }
    }

    logInfo("isochrone: " + nodesExpanded + " nodes expanded"
      + (nodesExpanded >= maxNodes ? " (maxNodes limit)" : "")
      + ", " + results.size() + "/" + bucketCount + " buckets populated");
    if (results.isEmpty()) return null;
    return results.toArray(new double[0][]);
  }

  /**
   * Place waypoints using per-direction indirectness from the isochrone expansion.
   *
   * The isochrone gives [direction, airDistance, routeCost] per angular bucket.
   * The ratio cost/airDist is the road indirectness factor for that direction:
   * - In a flat valley (E-W at Innsbruck): indirectness ~1.3 (roads follow valley)
   * - Across mountains (N-S at Innsbruck): indirectness ~3-5× (switchbacks)
   *
   * To hit the target loop distance, we compute a per-leg route-distance budget
   * and convert it to air distance using the per-direction indirectness:
   *   airDist = targetLegRouteDistance / indirectness
   *
   * This naturally produces elongated loops in valleys and compact loops in open terrain.
   */
  void placeWaypointsFromIsochrone(List<OsmNodeNamed> waypoints, double[][] frontierData,
                                   double searchRadius, double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    int n = frontierData.length;
    int needed = targetPoints - 1;
    if (needed > n) needed = n;
    if (needed < 2) needed = 2;

    // Extract per-direction data: [direction, airDist, cost]
    double[] directions = new double[n];
    Map<Double, double[]> dirToData = new HashMap<>(); // dir -> [airDist, cost]
    for (int i = 0; i < n; i++) {
      directions[i] = frontierData[i][0];
      double airDist = frontierData[i][1];
      double cost = frontierData[i].length > 2 ? frontierData[i][2] : airDist * 1.3; // fallback
      dirToData.put(frontierData[i][0], new double[]{airDist, cost});
    }

    double[] selected;
    if (needed >= n) {
      selected = directions;
    } else {
      selected = selectSpreadDirections(directions, needed, startDirection);
    }
    selected = sortDirectionsForLoop(selected, startDirection);

    // Base radius: scale searchRadius by the geometric factor (same as probe strategy)
    // to match v1.7.8's expected loop distance for this angular spread.
    double baseRadius = searchRadius * computeRadiusScale(selected, targetPoints);

    // Compute per-direction indirectness: cost / airDist at the frontier.
    // High indirectness = roads are indirect (mountains), low = roads are direct (valley floor).
    double[] indirectnesses = new double[n];
    for (int i = 0; i < n; i++) {
      double[] data = dirToData.get(directions[i]);
      indirectnesses[i] = (data[0] > 50) ? Math.max(1.0, data[1] / data[0]) : 1.5;
    }
    double[] sortedInd = indirectnesses.clone();
    java.util.Arrays.sort(sortedInd);
    double medianIndirectness = Math.max(1.0, sortedInd[n / 2]);

    // Compute per-direction redistribution factors.
    // Raw factor = medianIndirectness / dirIndirectness:
    //   indirect dirs (mountains) → factor < 1 → closer
    //   direct dirs (valley floor) → factor > 1 → farther
    // Normalize so the average factor = 1.0 (mean-preserving).
    double[] rawFactors = new double[selected.length];
    double factorSum = 0;
    for (int i = 0; i < selected.length; i++) {
      double[] data = dirToData.get(selected[i]);
      double ind = (data != null && data[0] > 50) ? Math.max(1.0, data[1] / data[0]) : medianIndirectness;
      rawFactors[i] = medianIndirectness / ind;
      factorSum += rawFactors[i];
    }
    double normalization = selected.length / factorSum; // makes avg factor = 1.0

    for (int i = 0; i < selected.length; i++) {
      double factor = rawFactors[i] * normalization;
      // Clamp to prevent extreme distortion
      factor = Math.max(0.5, Math.min(2.0, factor));
      double airDist = baseRadius * factor;

      // Clamp to prevent extremes
      double maxDist = searchRadius * 1.5;
      double minDist = searchRadius * 0.15;
      airDist = Math.max(minDist, Math.min(maxDist, airDist));

      int[] pos = CheapRuler.destination(start.ilon, start.ilat, airDist, selected[i]);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + (i + 1);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    logInfo("placeWaypointsFromIsochrone: " + selected.length + " waypoints"
      + ", baseRadius=" + (int) baseRadius + "m"
      + ", medianIndirectness=" + String.format("%.2f", medianIndirectness)
      + ", searchRadius=" + (int) searchRadius + "m");
  }

  /**
   * Merge isochrone frontier data with probe directions for gap-filling.
   * Isochrone entries have [direction, airDist, cost]; probe-only entries
   * get [direction, searchRadius, searchRadius*1.3] (estimated cost).
   *
   * @param frontier        isochrone [direction, airDist, cost] triples (may be null)
   * @param probeDirections probe viable directions in degrees (may be null)
   * @param searchRadius    fallback distance for probe-only directions
   * @return merged [direction, airDist, cost] triples; null if both inputs empty
   */
  static double[][] mergeIsochroneWithProbe(double[][] frontier, double[] probeDirections, double searchRadius) {
    Map<Integer, double[]> merged = new HashMap<>();

    if (frontier != null) {
      for (double[] entry : frontier) {
        int bucket = (int) Math.round(entry[0]);
        merged.put(bucket, entry);
      }
    }

    // Add probe directions where isochrone has no data
    if (probeDirections != null) {
      for (double dir : probeDirections) {
        int bucket = (int) Math.round(dir);
        boolean covered = false;
        for (int key : merged.keySet()) {
          if (angleDiff(key, bucket) <= 5) {
            covered = true;
            break;
          }
        }
        if (!covered) {
          // Probe-only: use searchRadius as airDist, estimate cost with default indirectness
          merged.put(bucket, new double[]{dir, searchRadius, searchRadius * 1.3});
        }
      }
    }

    if (merged.isEmpty()) return null;

    List<double[]> result = new ArrayList<>(merged.values());
    result.sort((a, b) -> Double.compare(a[0], b[0]));
    return result.toArray(new double[0][]);
  }

  /**
   * Place waypoints from the reachability envelope at a scaled search radius.
   * Selects N directions from the viable set that maximize angular spread,
   * then scales the radius to match v1.7.8's expected loop distance.
   */
  void placeWaypointsFromEnvelope(List<OsmNodeNamed> waypoints, double[] viableDirections,
                                  double searchRadius, double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    int n = viableDirections.length;
    int needed = targetPoints - 1;
    if (needed > n) needed = n;
    if (needed < 2) needed = 2;

    double[] selected;
    if (needed >= n) {
      selected = viableDirections;
    } else {
      selected = selectSpreadDirections(viableDirections, needed, startDirection);
    }

    selected = sortDirectionsForLoop(selected, startDirection);

    // Scale radius so the loop perimeter matches v1.7.8's expected distance.
    // v1.7.8 uses buildPointsFromCircle which creates a narrow arc (108-152°).
    // The probe's wider direction spread produces longer loops at the same radius.
    double adjustedRadius = searchRadius * computeRadiusScale(selected, targetPoints);

    for (int i = 0; i < selected.length; i++) {
      int[] pos = CheapRuler.destination(start.ilon, start.ilat, adjustedRadius, selected[i]);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + (i + 1);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    logInfo("placeWaypointsFromEnvelope: " + selected.length + " waypoints, radius "
      + (int) searchRadius + "m -> " + (int) adjustedRadius + "m (scale "
      + String.format("%.2f", adjustedRadius / searchRadius) + ")");
  }

  /**
   * Select N directions from the viable set that maximize angular spread.
   * Uses a greedy approach: start with the direction closest to startDirection,
   * then iteratively pick the direction that maximizes the minimum angular
   * distance to all already-selected directions.
   */
  static double[] selectSpreadDirections(double[] viable, int count, double startDirection) {
    int n = viable.length;
    boolean[] used = new boolean[n];
    double[] selected = new double[count];

    // Find starting direction closest to requested
    int startIdx = 0;
    double minDiff = Double.MAX_VALUE;
    for (int i = 0; i < n; i++) {
      double diff = angleDiff(viable[i], startDirection);
      if (diff < minDiff) {
        minDiff = diff;
        startIdx = i;
      }
    }
    used[startIdx] = true;
    selected[0] = viable[startIdx];

    // Greedy: pick the direction that maximizes minimum distance to selected set
    for (int s = 1; s < count; s++) {
      int bestIdx = -1;
      double bestMinDist = -1;
      for (int i = 0; i < n; i++) {
        if (used[i]) continue;
        double minDistToSelected = Double.MAX_VALUE;
        for (int j = 0; j < s; j++) {
          double d = angleDiff(viable[i], selected[j]);
          if (d < minDistToSelected) minDistToSelected = d;
        }
        if (minDistToSelected > bestMinDist) {
          bestMinDist = minDistToSelected;
          bestIdx = i;
        }
      }
      if (bestIdx < 0) break;
      used[bestIdx] = true;
      selected[s] = viable[bestIdx];
    }

    return selected;
  }

  /**
   * Sort directions to form a coherent loop, starting from the direction
   * closest to startDirection and proceeding clockwise.
   */
  static double[] sortDirectionsForLoop(double[] directions, double startDirection) {
    int n = directions.length;
    // Compute relative angles from startDirection, normalized to [0, 360)
    double[][] relative = new double[n][2]; // [relativeAngle, originalAngle]
    for (int i = 0; i < n; i++) {
      double rel = directions[i] - startDirection;
      if (rel < 0) rel += 360;
      if (rel >= 360) rel -= 360;
      relative[i][0] = rel;
      relative[i][1] = directions[i];
    }
    // Sort by relative angle
    java.util.Arrays.sort(relative, (a, b) -> Double.compare(a[0], b[0]));
    double[] sorted = new double[n];
    for (int i = 0; i < n; i++) sorted[i] = relative[i][1];
    return sorted;
  }

  /** Absolute angular difference in [0, 180] degrees. Delegates to CheapAngleMeter. */
  static double angleDiff(double a, double b) {
    return CheapAngleMeter.getDifferenceFromDirection(a, b);
  }

  /**
   * Compute a radius scaling factor so that a loop through the given sorted directions
   * produces the same perimeter as v1.7.8's buildPointsFromCircle for the same targetPoints.
   *
   * v1.7.8 creates a narrow arc (108-152° depending on point count), while
   * probe/isochrone create wider loops (270-360°). Without scaling, wider loops
   * produce proportionally longer routes. This factor compensates for that.
   *
   * The geometric loop perimeter for waypoints at radius R is:
   *   P = 2R (radial legs from center to circle) + sum of chord lengths between consecutive waypoints
   * where each chord = 2R * sin(angularGap/2).
   */
  static double computeRadiusScale(double[] sortedDirections, int targetPoints) {
    double actualPerim = computeLoopPerimeterFactor(sortedDirections);
    double refPerim = computeReferencePerimeterFactor(targetPoints);
    double scale = refPerim / actualPerim;
    // Clamp to [0.5, 1.0]:
    // - Never enlarge (>1.0): narrow arcs mean the road network is constrained in some
    //   directions (e.g., coastal, valley). Enlarging would push waypoints beyond
    //   reachable roads or segment data coverage, causing routing failures.
    //   Shorter-than-target loops are acceptable; unreachable waypoints are not.
    // - At least 0.5 to avoid degenerate tiny loops from extreme angular gaps.
    return Math.max(0.5, Math.min(1.0, scale));
  }

  /**
   * Compute the geometric loop perimeter / R for waypoints at the given sorted directions.
   * Includes 2 radial legs (center to first waypoint, last waypoint back to center)
   * plus chords between consecutive waypoints.
   */
  static double computeLoopPerimeterFactor(double[] sortedDirs) {
    int n = sortedDirs.length;
    if (n < 2) return 4.0; // degenerate: 2 radial legs of R each

    double chordSum = 0;
    for (int i = 0; i < n - 1; i++) {
      double gap = sortedDirs[i + 1] - sortedDirs[i];
      if (gap < 0) gap += 360;
      if (gap > 180) gap = 360 - gap; // use shortest arc
      chordSum += 2 * Math.sin(Math.toRadians(gap) / 2);
    }
    return 2 + chordSum; // 2R for radial legs + chord sum
  }

  /**
   * Compute the geometric loop perimeter / R for v1.7.8's buildPointsFromCircle.
   * v1.7.8 creates (targetPoints-1) intermediate waypoints in an arc whose
   * half-span = 90 - 180/targetPoints degrees.
   */
  static double computeReferencePerimeterFactor(int targetPoints) {
    int intermediates = targetPoints - 1;
    if (intermediates < 2) return 4.0;

    double arcHalfSpanDeg = 90.0 - 180.0 / targetPoints;
    double arcSpanRad = Math.toRadians(2 * arcHalfSpanDeg);
    int numGaps = intermediates - 1;
    double gapAngle = arcSpanRad / numGaps;

    double chordSum = 0;
    for (int i = 0; i < numGaps; i++) {
      chordSum += 2 * Math.sin(gapAngle / 2);
    }
    return 2 + chordSum;
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

      // filter bad round-trip waypoints after matching
      if (roundTripSearchRadius > 0) {
        int beforeFilter = matchedWaypoints.size();
        filterRoundTripWaypoints(matchedWaypoints);
        if (matchedWaypoints.size() != beforeFilter) {
          logInfo("filterRoundTrip: reduced waypoints from " + beforeFilter + " to " + matchedWaypoints.size());
          refTracks = new OsmTrack[matchedWaypoints.size() - 1];
          lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        }
        // Snap intermediate waypoints to nearest intersection to avoid mid-edge detour tails
        snapToIntersection(matchedWaypoints);
      }

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

    // For roundtrip mode, accumulate all previous legs so each new leg
    // penalizes reuse of edges from earlier legs (similar to GraphHopper's
    // AvoidEdgesWeighting). BRouter's existing refTrack mechanism doubles
    // the cost of edges found in the refTrack, discouraging road reuse.
    OsmTrack roundTripPreviousLegs = (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP) ? new OsmTrack() : null;

    OsmPath.seg = 1; // set segment counter
    for (int i = 0; i < matchedWaypoints.size() - 1; i++) {
      if (lastTracks[i] != null) {
        if (refTracks[i] == null) refTracks[i] = new OsmTrack();
        refTracks[i].addNodes(lastTracks[i]);
      }

      // In roundtrip mode, use accumulated previous legs as the refTrack
      // to discourage reusing roads from earlier legs of the loop.
      // Always create a fresh OsmTrack to avoid mutating refTracks[i] via alias.
      OsmTrack effectiveRefTrack;
      if (roundTripPreviousLegs != null && roundTripPreviousLegs.nodes != null
          && !roundTripPreviousLegs.nodes.isEmpty()) {
        effectiveRefTrack = new OsmTrack();
        if (refTracks[i] != null) {
          effectiveRefTrack.addNodes(refTracks[i]);
        }
        effectiveRefTrack.addNodes(roundTripPreviousLegs);
      } else {
        effectiveRefTrack = refTracks[i];
      }

      OsmTrack seg;
      int wptIndex;
      if (routingContext.inverseRouting) {
        routingContext.inverseDirection = true;
        seg = searchTrack(matchedWaypoints.get(i + 1), matchedWaypoints.get(i), null, effectiveRefTrack);
        routingContext.inverseDirection = false;
        wptIndex = i + 1;
      } else {
        seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), i == matchedWaypoints.size() - 2 ? nearbyTrack : null, effectiveRefTrack);
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

      // Accumulate this leg for roundtrip edge-avoidance on subsequent legs
      if (roundTripPreviousLegs != null) {
        roundTripPreviousLegs.addNodes(seg);
      }
    }

    postElevationCheck(totaltrack);

    if (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP) {
      removeBackAndForthSegments(totaltrack, matchedWaypoints);
      removeMicroDetours(totaltrack, 1500, matchedWaypoints);
    }

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
  void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints) {
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


  void resetCache(boolean detailed) {
    if (hasInfo() && nodesCache != null) {
      logInfo("NodesCache status before reset=" + nodesCache.formatStatus());
    }
    long maxmem = routingContext.memoryclass * 1024L * 1024L; // in MB

    nodesCache = new NodesCache(segmentDir, routingContext.expctxWay, routingContext.forceSecondaryData, maxmem, nodesCache, detailed);
    islandNodePairs.clearTempPairs();
  }

  OsmPath getStartPath(OsmNode n1, OsmNode n2, MatchedWaypoint mwp, OsmNodeNamed endPos, boolean sameSegmentSearch) {
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


  OsmPath getStartPath(OsmNode n1, OsmNode n2, OsmNodeNamed wp, OsmNodeNamed endPos, boolean sameSegmentSearch) {
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

  OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
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
