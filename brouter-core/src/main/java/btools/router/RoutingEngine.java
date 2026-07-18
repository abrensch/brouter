package btools.router;

import btools.mapaccess.*;
import btools.router.roundtrip.*;
import btools.util.*;

import java.io.*;
import java.util.*;

public class RoutingEngine extends Thread {

  public final static int BROUTER_ENGINEMODE_ROUTING = 0;
  public final static int BROUTER_ENGINEMODE_SEED = 1;
  public final static int BROUTER_ENGINEMODE_GETELEV = 2;
  public final static int BROUTER_ENGINEMODE_GETINFO = 3;
  public final static int BROUTER_ENGINEMODE_ROUNDTRIP = 4;

  NodesCache nodesCache;
  private SortedHeap<OsmPath> openSet = new SortedHeap<>();
  private volatile boolean finished = false;

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

  private static final String PROFILE_PARAM_ALLOW_FERRIES = "allow_ferries";

  // A loop whose start/end gap exceeds this never returned to the origin.
  private static final int MAX_ROUNDTRIP_CLOSURE_METERS = 400;
  /** searchRadius for a 30km loop (=30km/2π); maxNodes baseline scales relative to this. */
  private static final double REFERENCE_LOOP_RADIUS_M = 30_000.0 / (2 * Math.PI);
  /** Per-area base maxNodes for isochrone Dijkstra at the reference radius. */
  private static final int BASE_ISOCHRONE_MAX_NODES = 300_000;
  /** Absolute ceiling for isochrone Dijkstra maxNodes (circuit breaker). */
  private static final int CEILING_ISOCHRONE_MAX_NODES = 1_500_000;

  /**
   * Isochrone Dijkstra cost-budget calibration. A fixed cost budget gives very
   * different physical pool depths per profile (fastbike reaches ~2× searchRadius;
   * a high-penalty escape profile collapsed to ~0.45× — half-length loops). So the
   * budget is calibrated in flight: pops in
   * {@code [ISO_CALIBRATION_SAMPLE_LO, 1.0] × searchRadius} yield a median
   * cost-per-air-meter, and at the first pop past searchRadius the budget becomes
   * {@code ISO_TARGET_REACH_FACTOR × searchRadius × medianCostEff}, clamped to
   * [floor, cap] × searchRadius. Safe: the floor keeps every contour target at or
   * after the checkpoint, and a fired raise resets the frontier/contour
   * best-scores, so nothing that could win is discarded; with no raise, behavior
   * is bit-identical to the historical fixed budget. The 1.5× geographic cutoff,
   * {@code maxNodes}, and the expansion deadline still bound worst-case work.
   */
  static final double ISO_BUDGET_FLOOR_FACTOR = 4.0;
  static final double ISO_BUDGET_CAP_FACTOR = 12.0;
  /** Target air reach as a multiple of searchRadius (33% margin past the 1.5× geo cutoff). */
  static final double ISO_TARGET_REACH_FACTOR = 2.0;
  /** Lower edge of the calibration sampling band, as a fraction of searchRadius (upper edge = 1.0). */
  static final double ISO_CALIBRATION_SAMPLE_LO = 0.7;
  /** Below this many band samples the calibration is skipped (sparse graph → keep the floor). */
  static final int ISO_CALIBRATION_MIN_SAMPLES = 30;

  private int MAX_DYNAMIC_RANGE = 60000;

  protected OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  /**
   * Round-trip track rejected by the quality gate ({@link #foundTrack} is
   * nulled on rejection), kept for diagnostics. Null in plain routing.
   */
  private OsmTrack lastRejectedTrack;
  private RoundTripResult lastRoundTripResult;
  private RoundTripQualityResult lastRoundTripQuality;
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

  OsmTrack[] greedyLegTracks; // per-leg cost-cutting tracks from greedy planner

  private OsmPathElement matchPath;

  // Saved/restored across leg attempts by GreedyRoundTripPlanner.timedFindTrack
  // and read by the _findTrack timeout arithmetic — all on the same worker
  // thread (the cross-thread watchdog channel is the `terminated` flag, not
  // these fields). volatile is defensive: it keeps the 64-bit reads/writes
  // atomic should a watchdog ever read them, and is harmless otherwise.
  volatile long startTime;
  volatile long maxRunningTime;
  // Wall-clock budget (ms) for the routing legs of a round trip, captured from
  // doRun() so the WAYPOINT/ISOCHRONE/greedy-fallthrough doRouting() calls are
  // bounded. 0 (the CLI default) keeps the legacy no-timeout behaviour.
  private long roundTripRoutingBudgetMs;
  /**
   * Wall-clock deadline (epoch ms) for the whole round-trip request, set once
   * by doRun() and consulted by every retry layer and the isochrone expansion
   * loop — retries cannot multiply the request budget. 0 = unbounded.
   */
  volatile long roundTripRequestDeadline;

  /** Milliseconds left until {@link #roundTripRequestDeadline} (MAX_VALUE when unbounded). */
  long remainingRequestBudgetMs() {
    return roundTripRequestDeadline == 0
      ? Long.MAX_VALUE
      : roundTripRequestDeadline - System.currentTimeMillis();
  }

  /**
   * The request's resolved effort preset: BALANCED pins BOUNDED, QUALITY pins
   * MAX, AUTO resolves from context ({@link RoundTripEffortPolicy#resolveAuto}).
   * Planners read their knobs from it; AUTO child engines inherit it.
   */
  RoundTripEffortPolicy roundTripEffortPolicy = RoundTripEffortPolicy.STANDARD_PRESET;

  /**
   * Wall-clock bound (epoch ms, 0 = none) for the next
   * {@link #runIsochroneExpansion}, set/cleared by the greedy planner — the
   * expansion loop has no other time or termination check, only cost/geo/node
   * caps.
   */
  volatile long transientExpansionDeadline;
  public SearchBoundary boundary;

  public boolean quite = false;

  /**
   * Reachability-cloud cell size for pocket-avoiding waypoint placement: every
   * node an isochrone expansion pops is bucketed into cells of roughly this
   * many meters. ~150m keeps a 5×5 neighborhood at ~750m — local enough that a
   * dead-end corridor (cells along one line) is distinguishable from a
   * junction-rich neighborhood (filled square).
   */
  static final int REACHABILITY_CELL_M = 150;
  private boolean suppressRoutingIslandGuard = false;

  private Object[] extract;

  private boolean directWeaving = !Boolean.getBoolean("disableDirectWeaving");
  private String outfile;

  double roundTripSearchRadius = 0;

  /**
   * True while routing a user-supplied-via round trip. Micro-detour and
   * back-and-forth removal are skipped in this mode: they target auto-generated
   * loops and would delete a route whose closing waypoint sits on the start
   * (crow-fly 0 always trips the ratio threshold) — and user-picked shapes must
   * not be post-edited away.
   */
  boolean explicitViaRoundTrip = false;

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
    if (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP) {
      // Mark the context as round-trip up front: this gates the anti-reuse
      // refTrack penalty in OsmPath to its edge-membership form (see
      // RoutingContext.roundTrip) so loop legs avoid retracing traveled ways,
      // while general routing keeps the historic node-membership test unchanged.
      rc.roundTrip = true;
      applyRoundTripProfileDefaults(rc);
    }

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

  /**
   * Generated loops default to no ferries: loop generation must not discover a
   * ferry shortcut unless the caller opts in via {@code profile:allow_ferries=true}.
   * Point-to-point routing keeps the profile defaults.
   */
  private static void applyRoundTripProfileDefaults(RoutingContext rc) {
    if (rc == null) return;
    if (rc.keyValues == null) {
      rc.keyValues = new HashMap<>();
      rc.keyValues.put(PROFILE_PARAM_ALLOW_FERRIES, "0");
      return;
    }
    if (!rc.keyValues.containsKey(PROFILE_PARAM_ALLOW_FERRIES)) {
      rc.keyValues = new HashMap<>(rc.keyValues);
      rc.keyValues.put(PROFILE_PARAM_ALLOW_FERRIES, "0");
    }
  }

  private boolean roundTripFerriesAllowed() {
    if (routingContext == null || routingContext.keyValues == null) return false;
    String v = routingContext.keyValues.get(PROFILE_PARAM_ALLOW_FERRIES);
    return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v));
  }

  private boolean hasInfo() {
    return infoLogEnabled || infoLogWriter != null;
  }

  void logInfo(String s) {
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
    // Note: this.maxRunningTime is set by the branches that route (doRouting
    // sets it; the round-trip branch sets it for the competition). GETINFO/
    // GETELEV deliberately leave it at its default so they stay untimed.
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
        // Capture the request's wall-clock budget so the round-trip routing
        // legs (WAYPOINT/ISOCHRONE/greedy fallthrough) honour it instead of
        // running untimed, and so the AUTO competition can share it. 0 keeps
        // the legacy unbounded behaviour for the CLI.
        this.maxRunningTime = maxRunningTime;
        roundTripRoutingBudgetMs = maxRunningTime;
        // Anchor the engine clock for searches that run outside doRouting /
        // timedFindTrack (e.g. the repairViaPinnedBulges connector search in
        // the greedy bypass path). Before this, engine.startTime stayed 0 in
        // that path, so with maxRunningTime > 0 the connector's timeout check
        // (now - startTime > budget) fired instantly and every bulge repair
        // silently failed on servers.
        this.startTime = System.currentTimeMillis();
        // Absolute wall-clock deadline for the WHOLE round-trip request. This
        // is what the greedy planner ladder, the isochrone expansions and the
        // fallback doRouting consult so retries can never multiply the
        // request budget (the historical minutes-long worst case). 0 keeps
        // untimed callers (CLI, doRun(0) tests) unbounded.
        roundTripRequestDeadline = maxRunningTime > 0
          ? this.startTime + maxRunningTime : 0;
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
      // Signal termination to outside pollers — but NOT for the round-trip path.
      // In round-trip mode doRouting only produces the raw loop skeleton; the
      // outer doRoundTrip still runs the quality gate afterwards and can null
      // foundTrack / set errorMessage. Publishing `finished` here would let a
      // polling caller (e.g. BRouterView) read an intermediate result. The
      // round-trip path publishes `finished` in cleanupRoutingResources(), which
      // runs in doRoundTrip's finally after the gate has decided.
      if (engineMode != BROUTER_ENGINEMODE_ROUNDTRIP) {
        finished = true; // this signals termination to outside
      }

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

  private RoundTripOrchestrator roundTripOrchestrator;

  /** The round-trip tier ladder and candidate competition, behind the ops seam. */
  RoundTripOrchestrator roundTripOrchestrator() {
    if (roundTripOrchestrator == null) {
      roundTripOrchestrator = new RoundTripOrchestrator(roundTripOps());
    }
    return roundTripOrchestrator;
  }

  /** Round-trip entry point (kept for API compatibility; delegates to the orchestrator). */
  public void doRoundTrip() {
    roundTripOrchestrator().doRoundTrip();
  }

  /**
   * Single source of truth for lenient/strict acceptance: STRUCTURAL failures
   * (broken / un-routable / not-a-loop) always hard-reject; QUALITY failures
   * (rideable but suboptimal) are advisory unless
   * {@link RoutingContext#roundTripStrictQuality} is set. Shared by the gate
   * path and the AUTO best-effort fallback so the two never drift apart.
   */
  private boolean roundTripQualityHardReject(RoundTripQualityResult quality) {
    return quality.getRejectionTier() != RoundTripQualityResult.RejectionTier.QUALITY
      || routingContext.roundTripStrictQuality;
  }

  /**
   * Round-trip equivalent of the {@link #doRouting(long)} finally block:
   * release profile/cache/log resources and signal {@link #isFinished()}.
   * Idempotent — some round-trip paths already clean up via doRouting, and
   * direct planner-track adoption paths never enter it.
   */
  private void cleanupRoutingResources() {
    if (hasInfo() && routingContext.expctxWay != null) {
      logInfo("expression cache stats=" + routingContext.expctxWay.cacheStats());
    }
    ProfileCache.releaseProfile(routingContext);
    if (nodesCache != null) {
      if (hasInfo()) {
        logInfo("NodesCache status before close=" + nodesCache.formatStatus());
      }
      nodesCache.close();
      nodesCache = null;
    }
    openSet.clear();
    finished = true;

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

  /**
   * Format and persist a candidate-adopted track in the configured
   * {@code outputFormat}. When {@link #outfileBase} is null, keep the formatted
   * output in {@link #outputMessage} and print it unless {@link #quite} is set.
   * Mirrors the per-iteration write logic from {@link #doRouting} so the
   * AUTO-competition path produces the same output artefacts as the direct
   * algorithm dispatch.
   */
  private void writeAdoptedTrackOutput(OsmTrack track) {
    if (track == null) return;
    if (track.name == null) {
      track.name = "brouter_" + routingContext.getProfileName() + "_0";
    }
    track.exportWaypoints = routingContext.exportWaypoints;
    track.exportCorrectedWaypoints = routingContext.exportCorrectedWaypoints;
    String output;
    try {
      switch (routingContext.outputFormat) {
        case "gpx":     output = new FormatGpx(routingContext).format(track); break;
        case "geojson":
        case "json":
          output = new FormatJson(routingContext).format(track);
          break;
        case "kml":     output = new FormatKml(routingContext).format(track); break;
        case "csv":     output = null; break;
        default:        output = null;
      }
      outputMessage = output;
      if (outfileBase == null) {
        if (!quite && output != null) {
          System.out.println(output);
        }
        return;
      }
      String filename = outfileBase + "0." + routingContext.outputFormat;
      if ("csv".equals(routingContext.outputFormat)) {
        new FormatCsv(routingContext).write(filename, track);
      }
      if (output != null) {
        try (FileWriter fw = new FileWriter(filename)) {
          fw.write(output);
        }
      }
      outfile = filename;
      alternativeIndex = 0;
    } catch (Exception e) {
      logInfo("AUTO: failed to write adopted track: " + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  // Hints closer together than this are treated as one maneuver for round-trip cleanup.
  private static final double ROUNDTRIP_VOICEHINT_MERGE_DIST = 25.0; // meters

  /**
   * Collapse voice-hint clusters from synthetic round-trip geometry: within a
   * run of hints closer than {@link #ROUNDTRIP_VOICEHINT_MERGE_DIST}, a
   * near-straight net turn drops the whole cluster, otherwise only the dominant
   * turn stays. Roundabouts, beelines, and the end marker are never merged.
   * Round-trip only.
   */
  private void consolidateRoundTripVoiceHints(OsmTrack track) {
    if (track.voiceHints == null || track.voiceHints.list.size() < 2) return;
    List<VoiceHint> in = track.voiceHints.list;
    List<VoiceHint> out = new ArrayList<>();
    int i = 0;
    while (i < in.size()) {
      VoiceHint cur = in.get(i);
      if (cur.cmd == VoiceHint.BL || cur.cmd == VoiceHint.END || cur.isRoundabout()) {
        out.add(cur);
        i++;
        continue;
      }
      int j = i;
      float netAngle = (cur.angle == Float.MAX_VALUE) ? 0f : cur.angle;
      VoiceHint dominant = cur;
      while (j + 1 < in.size()) {
        VoiceHint next = in.get(j + 1);
        if (next.cmd == VoiceHint.BL || next.cmd == VoiceHint.END || next.isRoundabout()) break;
        if (in.get(j).distanceToNext >= ROUNDTRIP_VOICEHINT_MERGE_DIST) break;
        netAngle += (next.angle == Float.MAX_VALUE) ? 0f : next.angle;
        if (Math.abs(next.angle) > Math.abs(dominant.angle)) dominant = next;
        j++;
      }
      if (j > i) {
        if (Math.abs(netAngle) >= VoiceHintProcessor.SIGNIFICANT_ANGLE) {
          // keep the cluster's sharpest turn, carrying the trailing distance forward
          dominant.distanceToNext = in.get(j).distanceToNext;
          out.add(dominant);
        } else if (!out.isEmpty()) {
          // net-straight wiggle — drop the cluster, but preserve its distance so the
          // previous instruction's "distance to next" still reaches the following hint.
          double dropped = 0;
          for (int k = i; k <= j; k++) dropped += in.get(k).distanceToNext;
          out.get(out.size() - 1).distanceToNext += dropped;
        }
        i = j + 1;
      } else {
        out.add(cur);
        i++;
      }
    }
    if (out.size() != in.size()) {
      logInfo("roundtrip voicehints: consolidated " + in.size() + " -> " + out.size());
      track.voiceHints.list.clear();
      track.voiceHints.list.addAll(out);
    }
  }

  /**
   * Reachability guard: {@code true} unless {@code viaMatch}'s road component
   * is a small island that cannot reach the start within
   * {@link #MAXNODES_ISLAND_CHECK} nodes — lets FAST placement drop islanded
   * vias before routing instead of failing the whole loop. Cheap: the bounded
   * search exhausts a small island quickly and gives up on large (reachable)
   * components at the node budget.
   */
  private boolean isViaReachableFromStart(MatchedWaypoint viaMatch, MatchedWaypoint startMatch) {
    if (viaMatch == null || viaMatch.node1 == null || viaMatch.node2 == null
        || startMatch == null || startMatch.node1 == null || startMatch.node2 == null) {
      return true; // cannot test -> keep (conservative)
    }
    boolean savedInverse = routingContext.inverseDirection;
    double savedAir = airDistanceCostFactor;
    int savedNodeLimit = nodeLimit;
    try {
      routingContext.inverseDirection = true;
      airDistanceCostFactor = 0.0;
      nodeLimit = MAXNODES_ISLAND_CHECK;
      OsmTrack seg = findTrack("rt-fast-island-check", viaMatch, startMatch, null, null, false);
      // Reachable if a bounded path was found. null with budget left also means the
      // via's whole component is a small island -> unreachable.
      return !(seg == null && nodeLimit > 0);
    } catch (RoutingIslandException rie) {
      // The bounded search exhausted a small island around the via -> unreachable.
      return false;
    } catch (RuntimeException e) {
      // Best-effort guard: a budget timeout or any other transient failure must
      // not fail the request — keep the via (conservative) and let routing decide.
      return true;
    } finally {
      routingContext.inverseDirection = savedInverse;
      airDistanceCostFactor = savedAir;
      nodeLimit = savedNodeLimit;
    }
  }

  private GeometricWaypointPlacer waypointPlacer;

  /** Envelope/isochrone via placement. */
  GeometricWaypointPlacer waypointPlacer() {
    if (waypointPlacer == null) {
      waypointPlacer = new GeometricWaypointPlacer(roundTripOps());
    }
    return waypointPlacer;
  }

  private RoundTripTrackCleanup trackCleanup;

  /** Round-trip track post-processing. */
  RoundTripTrackCleanup trackCleanup() {
    if (trackCleanup == null) {
      RoundTripEngineOps ops = roundTripOps();
      trackCleanup = new RoundTripTrackCleanup(waypointSnapper(), ops, ops, ops);
    }
    return trackCleanup;
  }

  private WaypointSnapper waypointSnapper;

  /** Round-trip snap/validate/probe helpers. */
  WaypointSnapper waypointSnapper() {
    if (waypointSnapper == null) {
      RoundTripEngineOps ops = roundTripOps();
      waypointSnapper = new WaypointSnapper(ops, ops, ops);
    }
    return waypointSnapper;
  }

  /**
   * Production adapter for the round-trip engine seam: delegates to the
   * engine's leg router, matcher, expansion, timers, and logging, so engine
   * members stay package-private. Delegates qualify with
   * {@code RoutingEngine.this} so engine subclass overrides (tests) still
   * receive the call.
   */
  public RoundTripEngineOps roundTripOps() {
    return new RoundTripEngineOps() {
      @Override
      public RoutingContext routingContext() {
        return RoutingEngine.this.routingContext;
      }

      @Override
      public void logInfo(String msg) {
        RoutingEngine.this.logInfo(msg);
      }

      @Override
      public boolean isTerminated() {
        return RoutingEngine.this.isTerminated();
      }

      @Override
      public long startTime() {
        return RoutingEngine.this.startTime;
      }

      @Override
      public long maxRunningTime() {
        return RoutingEngine.this.maxRunningTime;
      }

      @Override
      public double roundTripSearchRadius() {
        return RoutingEngine.this.roundTripSearchRadius;
      }

      @Override
      public boolean isRoundTripMode() {
        return engineMode == BROUTER_ENGINEMODE_ROUNDTRIP;
      }

      @Override
      public boolean explicitViaRoundTrip() {
        return RoutingEngine.this.explicitViaRoundTrip;
      }

      @Override
      public void recalcTrack(OsmTrack track) {
        RoutingEngine.this.recalcTrack(track);
      }

      @Override
      public void buildPointsFromCircle(List<OsmNodeNamed> waypoints, double startAngle,
                                        double searchRadius, int points) {
        RoutingEngine.this.buildPointsFromCircle(waypoints, startAngle, searchRadius, points);
      }

      @Override
      public void consolidateRoundTripVoiceHints(OsmTrack track) {
        RoutingEngine.this.consolidateRoundTripVoiceHints(track);
      }

      @Override
      public void setMatchedWaypoints(List<MatchedWaypoint> waypoints) {
        matchedWaypoints = waypoints;
      }

      @Override
      public List<MatchedWaypoint> matchedWaypoints() {
        return matchedWaypoints;
      }

      @Override
      public List<OsmNodeNamed> waypoints() {
        return RoutingEngine.this.waypoints;
      }

      @Override
      public OsmTrack foundTrack() {
        return foundTrack;
      }

      @Override
      public void setFoundTrack(OsmTrack track) {
        foundTrack = track;
      }

      @Override
      public String errorMessage() {
        return errorMessage;
      }

      @Override
      public void setErrorMessage(String message) {
        errorMessage = message;
      }

      @Override
      public RoutingOutcome doRouting(long budgetMs) {
        RoutingEngine.this.doRouting(budgetMs);
        return new RoutingOutcome(foundTrack, errorMessage);
      }

      @Override
      public RoundTripEffortPolicy roundTripEffortPolicy() {
        return roundTripEffortPolicy;
      }

      @Override
      public void setRoundTripEffortPolicy(RoundTripEffortPolicy policy) {
        roundTripEffortPolicy = policy;
      }

      @Override
      public long roundTripRequestDeadline() {
        return roundTripRequestDeadline;
      }

      @Override
      public void setRoundTripRuntimeHints(RoundTripRuntimeHints hints) {
        roundTripSearchRadius = hints.searchRadius;
        roundTripRequestDeadline = hints.requestDeadline;
        explicitViaRoundTrip = hints.explicitViaRoundTrip;
        greedyLegTracks = hints.greedyLegTracks();
      }

      @Override
      public long roundTripRoutingBudgetMs() {
        return roundTripRoutingBudgetMs;
      }

      @Override
      public void setLastRoundTripResult(RoundTripResult result) {
        lastRoundTripResult = result;
      }

      @Override
      public void setLastRoundTripQuality(RoundTripQualityResult quality) {
        lastRoundTripQuality = quality;
      }

      @Override
      public IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius) {
        return RoutingEngine.this.runIsochroneExpansion(start, searchRadius);
      }

      @Override
      public void writeAdoptedTrackOutput(OsmTrack track) {
        RoutingEngine.this.writeAdoptedTrackOutput(track);
      }

      @Override
      public void terminate() {
        RoutingEngine.this.terminate();
      }

      @Override
      public void addTerminationHook(Runnable hook) {
        RoutingEngine.this.addTerminationHook(hook);
      }

      @Override
      public void cleanupRoutingResources() {
        RoutingEngine.this.cleanupRoutingResources();
      }

      @Override
      public void logException(Throwable t) {
        RoutingEngine.this.logException(t);
      }

      @Override
      public FastPlacementOps fastPlacementOps() {
        return RoutingEngine.this.fastPlacementOps();
      }

      @Override
      public double getRandomDirectionFromData(OsmNodeNamed wp, double searchRadius) {
        return RoutingEngine.this.getRandomDirectionFromData(wp, searchRadius);
      }

      @Override
      public void setLastRejectedTrack(OsmTrack track) {
        lastRejectedTrack = track;
      }

      @Override
      public boolean roundTripFerriesAllowed() {
        return RoutingEngine.this.roundTripFerriesAllowed();
      }

      @Override
      public boolean roundTripQualityHardReject(RoundTripQualityResult quality) {
        return RoutingEngine.this.roundTripQualityHardReject(quality);
      }

      @Override
      public long remainingRequestBudgetMs() {
        return RoutingEngine.this.remainingRequestBudgetMs();
      }

      @Override
      public File segmentDir() {
        return RoutingEngine.this.segmentDir;
      }

      @Override
      public void logThrowable(Throwable t) {
        RoutingEngine.this.logThrowable(t);
      }

      @Override
      public void addLinksProcessed(long links) {
        RoutingEngine.this.addLinksProcessed((int) links);
      }

      @Override
      public void setMaxRunningTime(long maxRunningTimeMillis) {
        RoutingEngine.this.maxRunningTime = maxRunningTimeMillis;
      }

      @Override
      public void setTransientExpansionDeadline(long deadlineMillis) {
        RoutingEngine.this.transientExpansionDeadline = deadlineMillis;
      }

      @Override
      public OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                                OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc) {
        return RoutingEngine.this.findTrack(operationName, startWp, endWp,
          costCuttingTrack, refTrack, fastPartialRecalc);
      }

      @Override
      public OsmTrack retrackForDetail(OsmTrack rawTrack, MatchedWaypoint startWp, MatchedWaypoint endWp,
                                       OsmTrack refTrack) {
        return RoutingEngine.this.retrackForDetail(rawTrack, startWp, endWp, refTrack);
      }

      @Override
      public MatchedWaypoint profileAwareMatchPoint(int ilon, int ilat, String name, double maxSnapDist) {
        return waypointSnapper().profileAwareMatchPoint(ilon, ilat, name, maxSnapDist);
      }

      @Override
      public void resetCache(boolean detailed) {
        RoutingEngine.this.resetCache(detailed);
      }

      @Override
      public OsmTrack findTrackTimed(String operationName, MatchedWaypoint startWp,
                                     MatchedWaypoint endWp, OsmTrack refTrack, long budgetMs) {
        return RoutingEngine.this.findTrackTimed(operationName, startWp, endWp, refTrack, budgetMs);
      }

      @Override
      public OsmTrack findTrackUnguided(String operationName, MatchedWaypoint startWp,
                                        MatchedWaypoint endWp) {
        OsmTrack savedGuide = guideTrack;
        guideTrack = null; // a live guide track would corrupt the local search
        try {
          return RoutingEngine.this.findTrack(operationName, startWp, endWp, null, null, false);
        } finally {
          guideTrack = savedGuide;
        }
      }

      @Override
      public void matchWaypointsToNodes(List<MatchedWaypoint> waypoints, double maxDistance) {
        nodesCache.matchWaypointsToNodes(waypoints, maxDistance, islandNodePairs);
      }

      @Override
      public IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius,
                                                            OsmTrack refTrack, boolean includeCandidateTracks) {
        return RoutingEngine.this.runIsochroneExpansion(start, searchRadius, refTrack, includeCandidateTracks);
      }
    };
  }

  /**
   * Weight of the air-distance "reach bonus" in the cost-contour scoring rule.
   * The bonus is a soft tiebreaker; this weight is the trade-off threshold,
   * i.e. a 10% normalized cost error completely cancels the max air-reach
   * bonus (chosen so cost dominates whenever it's meaningfully different).
   */
  static final double AIR_REACH_BONUS_WEIGHT = 0.10;

  /**
   * Score a Dijkstra-popped node against a target cost level; lower wins.
   * Normalized cost error, minus a soft air-reach tiebreaker for
   * farther-reached nodes. Used by {@link #runIsochroneExpansion} for the
   * per-bucket frontier node and the 25/50/75% contour candidates.
   */
  static double costContourScore(int pathCost, int targetCost, double dist, double searchRadius) {
    return costContourScore(pathCost, targetCost, clampedAirReachBonus(dist, searchRadius));
  }

  /**
   * Hot-loop overload: caller has already computed {@code airReachBonus} via
   * {@link #clampedAirReachBonus} so the same value can be reused across the
   * frontier + 3 contour evaluations per Dijkstra pop.
   */
  static double costContourScore(int pathCost, int targetCost, double airReachBonus) {
    if (targetCost <= 0) return Double.POSITIVE_INFINITY;
    double costError = Math.abs((double) pathCost - targetCost) / targetCost;
    return costError - AIR_REACH_BONUS_WEIGHT * airReachBonus;
  }

  /**
   * Calibrated isochrone cost budget from the sampled frontier band (see the
   * ISO_BUDGET_* class comment): {@code ISO_TARGET_REACH_FACTOR × searchRadius
   * × median cost-per-air-meter}. Returns the floor when the band is too
   * sparse to trust ({@code sampleCount < ISO_CALIBRATION_MIN_SAMPLES}).
   * Never below the floor (the historical fixed budget), never above the cap.
   */
  static int calibratedIsoBudget(double[] samples, int sampleCount, double searchRadius) {
    int floor = (int) (searchRadius * ISO_BUDGET_FLOOR_FACTOR);
    if (sampleCount < ISO_CALIBRATION_MIN_SAMPLES) return floor;
    double[] band = Arrays.copyOf(samples, sampleCount);
    Arrays.sort(band);
    double medianCostEff = band[sampleCount / 2];
    double budget = ISO_TARGET_REACH_FACTOR * searchRadius * medianCostEff;
    double cap = searchRadius * ISO_BUDGET_CAP_FACTOR;
    return (int) Math.min(cap, Math.max(floor, budget));
  }

  /** {@code clamp(dist / searchRadius, 0, 1)}; 0 when searchRadius is non-positive (avoids a 0/0 NaN). */
  static double clampedAirReachBonus(double dist, double searchRadius) {
    if (searchRadius <= 0.0) {
      return 0.0;
    }
    return Math.min(1.0, Math.max(0.0, dist / searchRadius));
  }

  /**
   * Decide whether the new candidate replaces the current best. Lower score wins;
   * ties broken in order by (1) higher path cost, (2) higher air-distance, (3)
   * existing candidate remains. See {@link #costContourScore}.
   */
  static boolean isBetterCandidate(double newScore, int newCost, double newDist,
                                   double bestScore, int bestCost, double bestDist) {
    if (newScore < bestScore) return true;
    if (newScore > bestScore) return false;
    if (newCost > bestCost) return true;
    if (newCost < bestCost) return false;
    return newDist > bestDist;
  }

  /**
   * Cost-limited Dijkstra expansion from the start: the reachable road-network
   * frontier in all directions. Returns frontier table + road-native candidate
   * pool, or {@code null} on failure.
   */
  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius) {
    // Start-centered expansion (ISO_GREEDY pool, frontier table): budget
    // calibration ON — searchRadius here is the loop radius the reach target
    // is defined against.
    return runIsochroneExpansion(start, searchRadius, null, false, true);
  }

  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius,
                                                 OsmTrack refTrack,
                                                 boolean includeCandidateTracks) {
    // Per-step callers (GraphNativeCandidateProvider expands a local disk
    // around the current node each step): calibration OFF — their radius is a
    // step window, not the loop radius, so the reach-target formula does not
    // apply and the historical fixed budget is the correct sizing.
    return runIsochroneExpansion(start, searchRadius, refTrack, includeCandidateTracks, false);
  }

  private IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius,
                                                         OsmTrack refTrack,
                                                         boolean includeCandidateTracks,
                                                         boolean calibrateBudget) {
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

    // Provisional cost budget = the floor (the historical fixed constant). The
    // in-flight calibration below can only RAISE it — see the class-level
    // ISO_BUDGET_* comment. Healthy profiles (fastbike: costEff ≈ 2.0 →
    // calibrated 4×) land on the floor and keep bit-identical behavior.
    int costBudget = (int) (searchRadius * ISO_BUDGET_FLOOR_FACTOR);
    // Calibration state: sample cost-per-air-meter in the band
    // [ISO_CALIBRATION_SAMPLE_LO, 1.0] × searchRadius, finalize at the first
    // pop past the checkpoint. Pops arrive in increasing cost order, so the
    // band is populated completely before the checkpoint fires.
    final int calibrationCheckpointCost = (int) searchRadius;
    final int calibrationSampleLoCost = (int) (searchRadius * ISO_CALIBRATION_SAMPLE_LO);
    // Starting "already calibrated" disables both the sampling and the
    // finalize hook — per-step callers keep the fixed floor budget.
    boolean isoBudgetCalibrated = !calibrateBudget;
    double[] costEffSamples = calibrateBudget ? new double[256] : null;
    int costEffSampleCount = 0;
    // Geographic cutoff: don't expand beyond 1.5× searchRadius (prevents runaway)
    double geoRadiusCutoff = searchRadius * 1.5;
    // Scale maxNodes with search area so dense regions (Berlin) reach the cost
    // budget instead of getting cut off at ~1/3 of it — without that headroom
    // the indirectness signal is dominated by per-link amortization noise.
    double radiusRatio = searchRadius / REFERENCE_LOOP_RADIUS_M;
    double areaScale = Math.max(1.0, radiusRatio * radiusRatio);
    int maxNodes = (int) Math.min(CEILING_ISOCHRONE_MAX_NODES, BASE_ISOCHRONE_MAX_NODES * areaScale);

    // Angular bucketing: 36 buckets of 10 degrees. Per-bucket "best frontier
    // candidate" is picked by cost-contour score — a far-by-air dead-end can
    // sit at low cost and would outrank a budget-cost node on a usable road if
    // we sorted by air-distance alone. See costContourScore + isBetterCandidate.
    int bucketCount = 36;
    double bucketSize = 360.0 / bucketCount;
    double[] bucketBestScore = new double[bucketCount];
    Arrays.fill(bucketBestScore, Double.POSITIVE_INFINITY);
    double[] bucketBestDist = new double[bucketCount];
    int[] bucketBestCost = new int[bucketCount];
    int[] bucketBestIlon = new int[bucketCount];
    int[] bucketBestIlat = new int[bucketCount];
    OsmPath[] bucketBestPath = new OsmPath[bucketCount];
    int[] bucketHits = new int[bucketCount]; // population count per bucket (sparseness signal)

    // Cost contours for ISO_GREEDY candidate extraction. Per bucket, record the
    // node whose path.cost is closest to each intermediate cost level — yields a
    // road-native pool spread across both direction and cost depth.
    int[] contourLabels = {25, 50, 75};
    int contourCount = contourLabels.length;
    int[] contourCosts = new int[contourCount];
    for (int k = 0; k < contourCount; k++) contourCosts[k] = (int) (contourLabels[k] * 0.01 * costBudget);
    double[][] bucketContourBestScore = new double[bucketCount][contourCount];
    for (double[] row : bucketContourBestScore) Arrays.fill(row, Double.POSITIVE_INFINITY);
    double[][] bucketContourDist = new double[bucketCount][contourCount];
    int[][] bucketContourCost = new int[bucketCount][contourCount];
    int[][] bucketContourIlon = new int[bucketCount][contourCount];
    int[][] bucketContourIlat = new int[bucketCount][contourCount];
    OsmPath[][] bucketContourPath = new OsmPath[bucketCount][contourCount];

    // Local open set — not the instance field, to avoid state contamination
    SortedHeap<OsmPath> isoOpenSet = new SortedHeap<>();
    if (startPath1 != null) isoOpenSet.add(startPath1.cost, startPath1);
    if (startPath2 != null) isoOpenSet.add(startPath2.cost, startPath2);

    int nodesExpanded = 0;

    // Reachability cloud (pocket-avoiding placement): fixed per-expansion
    // scale, captured once at the start latitude — CheapRuler's banded scale
    // cache could otherwise map one physical point into two cells.
    double[] cellKxKy = CheapRuler.getLonLatToMeterScales(start.ilat);
    int cellDivLon = Math.max(1, (int) (REACHABILITY_CELL_M / cellKxKy[0]));
    int cellDivLat = Math.max(1, (int) (REACHABILITY_CELL_M / cellKxKy[1]));
    // Cell -> min Dijkstra cost. Pops arrive in cost order, so the first touch
    // of a cell records its minimum (putIfAbsent); key presence doubles as the
    // reachability cloud, the value feeds the ReturnDistanceOracle.
    Map<Long, Integer> cellMinCost = new HashMap<>(4096);

    long expansionDeadline = transientExpansionDeadline;
    if (roundTripRequestDeadline > 0) {
      expansionDeadline = expansionDeadline > 0
        ? Math.min(expansionDeadline, roundTripRequestDeadline) : roundTripRequestDeadline;
    }

    int popTick = 0;
    for (;;) {
      // Wall-clock + watchdog guard (same contract as _findTrack's pop loop):
      // stop expanding and return the partial frontier — callers already
      // handle sparse candidate sets gracefully, and a partial frontier beats
      // an un-killable multi-second expansion overrunning every deadline.
      // The volatile kill flag is checked every pop; the wall clock only every
      // 4096 pops (a currentTimeMillis per pop is measurable at ~1.5M pops,
      // and 4096 pops complete in well under any deadline granularity).
      if (terminated
          || (expansionDeadline > 0 && (++popTick & 0xFFF) == 0
              && System.currentTimeMillis() > expansionDeadline)) {
        logInfo("isochrone: expansion stopped early (" + (terminated ? "terminated" : "deadline")
          + ") after " + nodesExpanded + " nodes");
        break;
      }

      OsmPath path = isoOpenSet.popLowestKeyValue();
      if (path == null) break;
      if (path.airdistance == -1) continue; // invalidated

      // In-flight budget calibration: finalize at the first pop past the
      // checkpoint (pops arrive in increasing cost order, so the sample band
      // below is complete here). Raising the budget resets the frontier and
      // contour picks — every competitive fit for the raised targets (all
      // ≥ checkpoint, guaranteed by the floor) pops after this point, so the
      // reset discards nothing that could have won. No raise = bit-identical
      // to the historical fixed budget.
      if (!isoBudgetCalibrated && path.cost > calibrationCheckpointCost) {
        isoBudgetCalibrated = true;
        int calibrated = calibratedIsoBudget(costEffSamples, costEffSampleCount, searchRadius);
        if (calibrated > costBudget) {
          logInfo("isochrone: calibrated cost budget " + costBudget + " -> " + calibrated
            + " (x" + String.format(Locale.ROOT, "%.1f",
              calibrated / searchRadius) + " searchRadius, "
            + costEffSampleCount + " band samples)");
          costBudget = calibrated;
          for (int k = 0; k < contourCount; k++) {
            contourCosts[k] = (int) (contourLabels[k] * 0.01 * costBudget);
          }
          Arrays.fill(bucketBestScore, Double.POSITIVE_INFINITY);
          for (double[] row : bucketContourBestScore) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
          }
        }
      }

      // Cost cutoff — Dijkstra: once popped cost exceeds budget, all remaining do too
      if (path.cost > costBudget) break;

      OsmLink currentLink = path.getLink();
      OsmNode sourceNode = path.getSourceNode();
      OsmNode currentNode = path.getTargetNode();
      if (currentLink.isLinkUnused()) continue;

      // Count expansions only for real link processing — skipped links shouldn't
      // consume the budget (could prematurely truncate exploration in dense graphs).
      nodesExpanded++;
      if (nodesExpanded > maxNodes) break;

      // Record this node in angular buckets using true bearing (longitude-scaled).
      // Selection is by cost-contour score; air-distance is only a soft tiebreaker
      // (see AIR_REACH_BONUS_WEIGHT).
      int curIlon = currentNode.getILon();
      int curIlat = currentNode.getILat();
      long cmcKey = (((long) (curIlon / cellDivLon)) << 32) | ((curIlat / cellDivLat) & 0xFFFFFFFFL);
      if (!cellMinCost.containsKey(cmcKey)) {
        cellMinCost.put(cmcKey, path.cost);
      }
      double dist = CheapRuler.distance(start.ilon, start.ilat, curIlon, curIlat);
      if (dist > 50) { // skip very close nodes (noisy bearings)
        int pcost = path.cost;
        // Calibration band sample: cost-per-air-meter of frontier-band pops.
        // The finalize check above flips the flag at the first pop past the
        // checkpoint, so this band is exactly [SAMPLE_LO, 1.0] × searchRadius.
        if (!isoBudgetCalibrated && pcost >= calibrationSampleLoCost) {
          if (costEffSampleCount == costEffSamples.length) {
            costEffSamples = Arrays.copyOf(costEffSamples, costEffSamples.length * 2);
          }
          costEffSamples[costEffSampleCount++] = pcost / dist;
        }
        double bearing = CheapRuler.getScaledBearing(start.ilon, start.ilat, curIlon, curIlat);
        int bucket = ((int) (bearing / bucketSize)) % bucketCount;
        if (bucket < 0) bucket += bucketCount;
        bucketHits[bucket]++;
        double airReachBonus = clampedAirReachBonus(dist, searchRadius);

        // Frontier candidate: target = full cost budget (cost envelope edge).
        double frontierScore = costContourScore(pcost, costBudget, airReachBonus);
        if (isBetterCandidate(frontierScore, pcost, dist,
          bucketBestScore[bucket], bucketBestCost[bucket], bucketBestDist[bucket])) {
          bucketBestScore[bucket] = frontierScore;
          bucketBestCost[bucket] = pcost;
          bucketBestDist[bucket] = dist;
          bucketBestIlon[bucket] = curIlon;
          bucketBestIlat[bucket] = curIlat;
          bucketBestPath[bucket] = path;
        }

        // Contour candidates: targets at 25/50/75% of budget. Score-based
        // selection allows above-contour wins, so every pop is evaluated against
        // every contour (3 cheap compares). Row hoist keeps bounds-check
        // elimination working on the inner index.
        double[] rowScore = bucketContourBestScore[bucket];
        int[]    rowCost  = bucketContourCost[bucket];
        double[] rowDist  = bucketContourDist[bucket];
        int[]    rowIlon  = bucketContourIlon[bucket];
        int[]    rowIlat  = bucketContourIlat[bucket];
        for (int k = 0; k < contourCount; k++) {
          double cscore = costContourScore(pcost, contourCosts[k], airReachBonus);
          if (isBetterCandidate(cscore, pcost, dist, rowScore[k], rowCost[k], rowDist[k])) {
            rowScore[k] = cscore;
            rowCost[k] = pcost;
            rowDist[k] = dist;
            rowIlon[k] = curIlon;
            rowIlat[k] = curIlat;
            bucketContourPath[bucket][k] = path;
          }
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
          OsmPath testPath = routingContext.createPath(otherPath, link, refTrack, false);
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

    // Compile per-bucket frontier entries — see IsochroneExpansionResult.frontier.
    // hits<3 is the dead-end signal used by downstream filters.
    List<double[]> results = new ArrayList<>();
    // Road-native candidate list for ISO_GREEDY. Each populated bucket
    // contributes one candidate per contour plus the frontier-max.
    List<IsoCandidate> candidatePool = new ArrayList<>();
    for (int b = 0; b < bucketCount; b++) {
      if (bucketBestScore[b] < Double.POSITIVE_INFINITY) {
        double bucketBearing = b * bucketSize + bucketSize / 2.0;
        results.add(new double[]{
          bucketBearing,
          bucketBestDist[b],
          bucketBestCost[b],
          bucketHits[b],
          bucketBestIlon[b],
          bucketBestIlat[b]});
        for (int k = 0; k < contourCount; k++) {
          if (bucketContourBestScore[b][k] < Double.POSITIVE_INFINITY) {
            candidatePool.add(new IsoCandidate(
              bucketContourIlon[b][k], bucketContourIlat[b][k],
              bucketBearing, bucketContourDist[b][k], bucketContourCost[b][k],
              b, bucketHits[b], contourLabels[k],
              includeCandidateTracks ? compileCandidateTrack(bucketContourPath[b][k]) : null));
          }
        }
        candidatePool.add(new IsoCandidate(
          bucketBestIlon[b], bucketBestIlat[b],
          bucketBearing, bucketBestDist[b], bucketBestCost[b],
          b, bucketHits[b], 100,
          includeCandidateTracks ? compileCandidateTrack(bucketBestPath[b]) : null));
      }
    }
    logInfo("isochrone: " + nodesExpanded + " nodes expanded"
      + (nodesExpanded >= maxNodes ? " (maxNodes limit)" : "")
      + ", " + results.size() + "/" + bucketCount + " buckets populated");
    if (results.isEmpty()) return null;
    return new IsochroneExpansionResult(results.toArray(new double[0][]), candidatePool,
      cellMinCost, cellDivLon, cellDivLat);
  }

  private OsmTrack compileCandidateTrack(OsmPath path) {
    if (path == null) return null;
    try {
      return compileTrack(path, false);
    } catch (RuntimeException e) {
      logInfo("graph-native candidate track compile failed: " + e.getMessage());
      return null;
    }
  }

  /**
   * Production adapter for the {@link FastWaypointPlanner} seam. Placement uses
   * per-direction indirectness from the isochrone (route cost / air distance:
   * ~1.3 along a valley, 3–5× across mountains) to convert the per-leg
   * route-distance budget into air distance — elongated loops in valleys,
   * compact loops in open terrain.
   */
  private FastPlacementOps fastPlacementOps() {
    return new FastPlacementOps() {
      @Override
      public ProbeResult probe(OsmNodeNamed start, double searchRadius, double[] bearings) {
        return waypointSnapper().probeReachableDirectionsFast(start, searchRadius, bearings);
      }

      @Override
      public SnapUsability snapUsability(MatchedWaypoint m) {
        return waypointSnapper().snapUsability(m);
      }

      @Override
      public boolean isViaReachable(MatchedWaypoint via, MatchedWaypoint startMatch) {
        return isViaReachableFromStart(via, startMatch);
      }

      @Override
      public void circleFallbackValidated(List<OsmNodeNamed> skeleton, double direction,
                                          double searchRadius, int targetPoints) {
        buildPointsFromCircle(skeleton, direction, searchRadius, targetPoints);
        waypointSnapper().validateAndAdjustWaypoints(skeleton, searchRadius);
      }

      @Override
      public void log(String msg) {
        logInfo(msg);
      }
    };
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
        double selev = track.nodes.get(startIdx > 1 ? startIdx - 2 : startIdx - 1).getSElev();
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
        mwp.generated = waypoints.get(i).generated;
        matchedWaypoints.add(mwp);
      }
      int startSize = matchedWaypoints.size();
      matchWaypointsToNodes(matchedWaypoints);

      // filter bad round-trip waypoints after matching
      if (roundTripSearchRadius > 0) {
        int beforeFilter = matchedWaypoints.size();
        waypointSnapper().filterRoundTripWaypoints(matchedWaypoints);
        if (matchedWaypoints.size() != beforeFilter) {
          logInfo("filterRoundTrip: reduced waypoints from " + beforeFilter + " to " + matchedWaypoints.size());
          refTracks = new OsmTrack[matchedWaypoints.size() - 1];
          lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        }
        // Snap intermediate waypoints to nearest intersection to avoid mid-edge detour tails
        waypointSnapper().snapToIntersection(matchedWaypoints);
        // No-beeline invariant: round-trip routes must not contain DIRECT
        // segments. matchWaypointsToNodes flags DIRECT for points beyond
        // catchingRange; fail rather than emit a beeline in a successful loop.
        for (MatchedWaypoint mwp : matchedWaypoints) {
          if (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
            throw new IllegalArgumentException(
              "round-trip waypoint " + mwp.name + " could not be road-matched"
                + " (would force beeline segment); aborting");
          }
        }
      }

      if (startSize < matchedWaypoints.size()) {
        refTracks = new OsmTrack[matchedWaypoints.size() - 1]; // used ways for alternatives
        lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        hasDirectRouting = true;
      }

      // greedyLegTracks is indexed by leg position and only valid while the
      // matched-waypoint count is unchanged. If matching/filtering above added or
      // removed a waypoint, the leg-to-waypoint correspondence is broken, so drop
      // the corridor constraints rather than route through a misaligned leg track.
      if (greedyLegTracks != null && greedyLegTracks.length != matchedWaypoints.size() - 1) {
        logInfo("greedy leg tracks (" + greedyLegTracks.length + ") no longer match "
          + (matchedWaypoints.size() - 1) + " legs after matching/filtering; "
          + "dropping corridor constraints");
        greedyLegTracks = null;
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
        OsmTrack legNearbyTrack = (greedyLegTracks != null && i < greedyLegTracks.length)
          ? greedyLegTracks[i]
          : (i == matchedWaypoints.size() - 2 ? nearbyTrack : null);
        if (legNearbyTrack != null && legNearbyTrack != nearbyTrack) {
          // Corridor-constrained routing: try with greedy leg track first,
          // fall back to unconstrained routing if it fails.
          try {
            seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), legNearbyTrack, effectiveRefTrack);
          } catch (IllegalArgumentException e) {
            seg = null;
          }
          if (seg == null) {
            seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), null, effectiveRefTrack);
          }
        } else {
          seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), legNearbyTrack, effectiveRefTrack);
        }
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
      // allowSamewayback is an out-and-back: it intentionally retraces the outbound leg.
      // Back-and-forth/micro-detour removal would see the two legs as an overlap and delete
      // one of them, leaving a one-way segment that no longer closes — so skip it here.
      // (This also affected loops that reduced to a single intermediate waypoint.)
      //
      // explicit-via round-trip mode hits the same problem: the closing waypoint sits at
      // the same position as the start, so crow-fly between the first and last matched
      // waypoint is 0 and removeMicroDetours sees the entire route as a "micro detour"
      // and deletes it. User-via routes are also shape-preserving by intent — the user
      // picked exact via points and does not want the engine to micro-edit them away.
      if (!routingContext.allowSamewayback && !explicitViaRoundTrip) {
        trackCleanup().removeBackAndForthSegments(totaltrack, matchedWaypoints);
        trackCleanup().removeMicroDetours(totaltrack, 1500, matchedWaypoints);
        // Same artifact-repair chain as the greedy adoption path
        // (finalizeAdoptedRoundTripTrack): probe/isochrone are fast fallback
        // algorithms worth keeping, and their generated "rt*" waypoints suffer
        // the same via-pinned bulges and near-revisit petals. Both passes
        // recognize rt-named waypoints as generated and carry the full guard
        // set (user-via protection, distance floor, crossing guard).
        waypointSnapper().repairViaPinnedBulges(totaltrack, matchedWaypoints);
        trackCleanup().removeArtifactSpurSpans(totaltrack, matchedWaypoints);
      }
      // removeBackAndForthSegments/removeMicroDetours edit the nodes list in place but
      // leave each node's origin back-pointer dangling through the removed nodes.
      // processVoiceHints() walks the origin chain (not the list), so a chain longer
      // than the list drives its node counter negative — producing voice hints with
      // negative indexInTrack and stale, out-of-range turn angles at the loop seam.
      // Relink origins to the surviving list order to restore the chain == list invariant.
      trackCleanup().rebuildOriginChain(totaltrack);
    }

    recalcTrack(totaltrack);

    matchedWaypoints.get(matchedWaypoints.size() - 1).indexInTrack = totaltrack.nodes.size() - 1;
    totaltrack.matchedWaypoints = matchedWaypoints;
    totaltrack.processVoiceHints(routingContext);
    if (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP) {
      consolidateRoundTripVoiceHints(totaltrack);
    }
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


  /**
   * Fallback time budget for the guided detail-retrack when the caller set no
   * budget ({@code maxRunningTime <= 0}, e.g. an untimed CLI run): past the
   * guide-track cost cap the pass can fall back to a free search, and this
   * bounds it — on timeout the raw track is returned. Timed runs are already
   * bounded by the request budget.
   */
  private static final long RETRACK_DETAIL_FALLBACK_BUDGET_MS = 60_000;

  /**
   * Re-run a raw single-pass {@link #findTrack} result at full detail: walks
   * the same nodes via {@code guideTrack} and fills the per-edge
   * {@code MessageData} (the {@code wayKeyValues} the gate's paved-hostility
   * check needs) without the 2-pass routing cost. {@code refTrack} is accepted
   * for call-site compatibility but not applied — reuse penalties belong to
   * route choice, not to annotating an already-chosen route.
   */
  OsmTrack retrackForDetail(OsmTrack rawTrack, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack refTrack) {
    if (rawTrack == null || rawTrack.nodes == null || rawTrack.nodes.size() < 2) return rawTrack;
    double savedAirDistFactor = airDistanceCostFactor;
    double savedLastFactor = lastAirDistanceCostFactor;
    OsmTrack savedGuide = guideTrack;
    long savedStartTime = startTime;
    long savedMaxRunningTime = maxRunningTime;
    boolean savedSuppressIslandGuard = suppressRoutingIslandGuard;
    airDistanceCostFactor = 0.;
    lastAirDistanceCostFactor = 0.;
    guideTrack = rawTrack;
    startTime = System.currentTimeMillis();
    // Bound the retrack when the caller set no time budget (see constant above);
    // production paths pass a positive maxRunningTime and are unaffected.
    if (maxRunningTime <= 0) {
      maxRunningTime = RETRACK_DETAIL_FALLBACK_BUDGET_MS;
    }
    // Guided retracking visits few nodes (the route is already known), so
    // the island-check guard `nodesVisited < MAXNODES_ISLAND_CHECK` would
    // false-positive every call. Suppress it only for this scoped retrack;
    // do not mutate islandNodePairs.freezeCount, because the rest of the
    // planner still needs normal island detection.
    suppressRoutingIslandGuard = true;
    try {
      // The guide track already fixes the exact node sequence. Reuse
      // poisoning is useful while choosing a route, but it can make this
      // metadata-only pass exceed the guide-track cost cap and fall back to
      // the raw no-message track. Keep retracking purely descriptive.
      OsmTrack detailed = findTrack("re-tracking", startWp, endWp, null, null, false);
      return detailed != null ? detailed : rawTrack;
    } catch (IllegalArgumentException | RoutingIslandException e) {
      logInfo("retrackForDetail failed: " + e.getClass().getSimpleName() + " "
        + (e.getMessage() == null ? "" : e.getMessage()) + " — using raw track");
      return rawTrack;
    } finally {
      guideTrack = savedGuide;
      airDistanceCostFactor = savedAirDistFactor;
      lastAirDistanceCostFactor = savedLastFactor;
      startTime = savedStartTime;
      maxRunningTime = savedMaxRunningTime;
      suppressRoutingIslandGuard = savedSuppressIslandGuard;
    }
  }

  void resetCache(boolean detailed) {
    if (hasInfo() && nodesCache != null) {
      logInfo("NodesCache status before reset=" + nodesCache.formatStatus());
    }
    if (routingContext.expctxWay == null) {
      // A completed doRouting run in this same round-trip request released the
      // parsed profile in its finally (ProfileCache.releaseProfile nulls the
      // expression contexts). Round-trip flows legitimately probe and route
      // again afterwards — the FAST ring retry, the bounded tier's waypoint
      // fallback — so re-acquire the profile before building a NodesCache.
      ProfileCache.parseProfile(routingContext);
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

  /**
   * One leg search under its own time budget: saves and restores the engine
   * clock (startTime/maxRunningTime) and runs goal-directed at the profile's
   * pass-1 coefficient — planner legs don't need exact optimality (they are
   * re-scored on the routed result and detail-retracked on commit), and the
   * historical inherited 0.0 meant a full omnidirectional Dijkstra per leg.
   * Profiles that disable pass 1 (coefficient &le; 0) keep the exact search.
   */
  OsmTrack findTrackTimed(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp,
                          OsmTrack refTrack, long budgetMs) {
    long savedStartTime = startTime;
    long savedMaxRunningTime = maxRunningTime;
    double savedAirDistanceCostFactor = airDistanceCostFactor;
    try {
      startTime = System.currentTimeMillis();
      maxRunningTime = budgetMs;
      airDistanceCostFactor = Math.max(0.0, routingContext.pass1coefficient);
      return findTrack(operationName, startWp, endWp, null, refTrack, false);
    } finally {
      startTime = savedStartTime;
      maxRunningTime = savedMaxRunningTime;
      airDistanceCostFactor = savedAirDistanceCostFactor;
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

    if (!suppressRoutingIslandGuard
        && nodesVisited < MAXNODES_ISLAND_CHECK && islandNodePairs.getFreezeCount() < 5) {
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

  public synchronized int getLinksProcessed() {
    return linksProcessed;
  }

  /**
   * Aggregate a child engine's work count into this (parent) engine.
   * Synchronized to match {@link #getLinksProcessed()}, which progress
   * monitors may call from another thread while the request thread
   * aggregates. The engine's own hot-loop {@code linksProcessed++} stays
   * unsynchronized (single-threaded per engine).
   */
  private synchronized void addLinksProcessed(int childLinks) {
    linksProcessed += childLinks;
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

  /** The last round-trip planning result (carries the planned loop waypoints), or null. */
  public RoundTripResult getLastRoundTripResult() {
    return lastRoundTripResult;
  }

  /**
   * The last round-trip request's final quality-gate verdict (for the shipped
   * track, or — on a hard reject — the track in {@link #getLastRejectedTrack()}).
   * Null when the request never reached the gate (floors, budget, no loop).
   * The AUTO competition reads this off child engines so a candidate is gated
   * exactly once (in the child) instead of re-evaluated in the parent.
   */
  public RoundTripQualityResult getLastRoundTripQuality() {
    return lastRoundTripQuality;
  }

  /**
   * The last round-trip track that was rejected by the quality gate, if
   * any. {@link #getFoundTrack()} returns null on rejection; this method
   * returns the geometry that tripped the gate so post-mortem analysis
   * tools can inspect WHY each rejection occurred. Returns null if no
   * round-trip request was made or no track ever reached the gate.
   */
  public OsmTrack getLastRejectedTrack() {
    return lastRejectedTrack;
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

  /**
   * Hooks run when this engine is terminated — the cascade that lets a server
   * pre-emption reach the child engines a round-trip request is running
   * (children check their OWN kill flag per pop, so the parent's flag alone
   * cannot stop them). Thread-safe; hooks must be idempotent and fast
   * (typically {@code child::terminate}).
   */
  private final List<Runnable> terminationHooks =
    new java.util.concurrent.CopyOnWriteArrayList<>();

  public void terminate() {
    terminated = true;
    for (Runnable hook : terminationHooks) {
      hook.run();
    }
  }

  /**
   * Register a termination cascade hook. Registering on an already-terminated
   * engine runs the hook immediately — closes the race between a server
   * pre-emption and a child engine being constructed.
   */
  void addTerminationHook(Runnable hook) {
    terminationHooks.add(hook);
    if (terminated) {
      hook.run();
    }
  }

  public boolean isTerminated() {
    return terminated;
  }

  public String getOutfile() {
    return outfile;
  }
}
