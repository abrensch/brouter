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
import java.util.Locale;
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

  private int ROUNDTRIP_DEFAULT_DIRECTIONADD = 45;
  private static final String PROFILE_PARAM_ALLOW_FERRIES = "allow_ferries";

  // A loop must enclose area: at least a triangle (start + 2 intermediate waypoints).
  // A single intermediate point is only an out-and-back, not a loop.
  private static final int MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS = 2;
  // A produced round-trip below either bound is a degenerate stub, not a loop.
  private static final int MIN_ROUNDTRIP_LOOP_NODES = 6;
  private static final int MIN_ROUNDTRIP_LOOP_METERS = 200;
  // A loop whose start/end gap exceeds this never returned to the origin.
  private static final int MAX_ROUNDTRIP_CLOSURE_METERS = 400;
  // A snap whose matched edge is longer than this is ferry-like (sparse nodes
  // spanning km over water), not a road edge — skip it when snapping waypoints.
  private static final int FERRY_LIKE_EDGE_METERS = 1500;
  // Display/log label stamped on generated arc-densification "bulge" waypoints.
  // It is NOT load-bearing: post-route spur cleanup targets generated points via
  // the typed MatchedWaypoint.generated flag, not this name.
  private static final String DENSIFIED_VIA_WAYPOINT_NAME = "dvia";

  /** searchRadius for a 30km loop (=30km/2π); maxNodes baseline scales relative to this. */
  private static final double REFERENCE_LOOP_RADIUS_M = 30_000.0 / (2 * Math.PI);
  /** Per-area base maxNodes for isochrone Dijkstra at the reference radius. */
  private static final int BASE_ISOCHRONE_MAX_NODES = 300_000;
  /** Absolute ceiling for isochrone Dijkstra maxNodes (circuit breaker). */
  private static final int CEILING_ISOCHRONE_MAX_NODES = 1_500_000;

  /** Reference road-geometry indirectness the geometric loop-radius calibration is tuned to. */
  private static final double REFERENCE_GEOM_INDIRECTNESS = 1.25;
  /**
   * Assumed road/air indirectness for a direction with NO observed isochrone
   * geometry (probe-only frontier entries, and the no-iso-data fallback).
   * Deliberately equal to {@link #REFERENCE_GEOM_INDIRECTNESS}: an unknown
   * direction is assumed to behave like the calibration baseline, so the number
   * of probe-only directions does not perturb the global indirectness
   * compensation. (Was an inline {@code 1.3} literal at two sites — 0.05 above
   * the calibration reference for no documented reason; unified here so the
   * compensation has a single indirectness baseline.) Validate against the
   * loop-quality corpus when changing.
   */
  private static final double DEFAULT_PROBE_INDIRECTNESS = REFERENCE_GEOM_INDIRECTNESS;
  /** Clamp range on the indirectness compensation factor (±20% of geometric base). */
  private static final double IND_COMPENSATION_MIN = 0.80;
  private static final double IND_COMPENSATION_MAX = 1.20;

  /**
   * Weight on the profile costfactor in waypoint-snap scoring (line ~1218 of this
   * file). Combined with the geometric score as {@code geom × (1 + W × (costfactor - 1))}.
   *
   * <p>Empirical calibration with W=0.5:
   * <ul>
   *   <li>50m grade5 track (fastbike costfactor 30) score = 50 × (1 + 0.5 × 29) = 775,
   *       loses to 200m tertiary (costfactor 1, score 200) — correct: fastbike won't
   *       snap to a forest track when there's a paved road nearby.</li>
   *   <li>50m cycleway (costfactor 1.3) score = 50 × 1.15 = 57.5 still beats a
   *       200m tertiary (score 200) — correct: short snap to a cyclist-preferred
   *       road remains the better pick.</li>
   * </ul>
   * Falls back to 1.0 (no penalty) when the link can't be resolved.
   */
  private static final double SNAP_PROFILE_COST_WEIGHT = 0.5;

  /**
   * Hard ceiling for the active profile's costfactor at a waypoint snap. A
   * candidate's BEST road is REJECTED entirely if its costfactor exceeds this —
   * better to drop the waypoint (route around the area) than commit to a
   * profile-hostile road that the user will hit as a surprise mid-tour. A
   * fastbike snap to a track grade-5 (costfactor 30) is far worse for the rider
   * than skipping that waypoint and routing elsewhere.
   *
   * <p>Per-profile thresholds — fastbike rejects high-costfactor (≥5) tracks/unpaved;
   * gravel allows moderate roughness; mtb is lenient because trails are its target.
   */
  private static final double SNAP_REJECT_COSTFACTOR_FASTBIKE = 5.0;
  private static final double SNAP_REJECT_COSTFACTOR_GRAVEL = 8.0;
  private static final double SNAP_REJECT_COSTFACTOR_DEFAULT = 10.0;

  /**
   * Isochrone Dijkstra cost budget.
   *
   * <p>The fixed budget {@code searchRadius * 4} produces wildly different
   * physical pool depths depending on the profile costfactor: ~3× searchRadius
   * for fastbike (costfactor ≈ 1.3), ~1.33× for mtb (costfactor ≈ 3). A 5-step
   * loop needs pool depth ≈ 2× searchRadius; mtb's pool is too clustered and
   * the planner can collapse to half target length if the pool is too shallow.
   * AUTO now tries ISO_GREEDY first, so the planner must fall back to per-step
   * graph-native candidates when the start-centered iso pool is insufficient
   * instead of returning a weak route from clustered data.
   */

  private int MAX_DYNAMIC_RANGE = 60000;

  protected OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  /**
   * The round-trip track that was rejected by the quality gate, if any.
   * {@link #foundTrack} is nulled on rejection so callers see a clean
   * "no track" outcome; this field preserves the rejected geometry for
   * post-mortem analysis (visual route inspection, failure
   * categorization, regression tests). Only populated for round-trip
   * mode; null in plain routing.
   */
  private OsmTrack lastRejectedTrack;
  private RoundTripResult lastRoundTripResult;
  /**
   * Set by {@link #doGreedyRoundTrip} when the adopted loop is a forced
   * same-way-back corridor (no clean alternative in constrained terrain). The
   * uniform round-trip gate then evaluates it with allowSamewayback=true so the
   * rideable corridor is accepted (disclosed) rather than rejected.
   */
  private boolean roundTripForcedCorridorAccepted;
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
  public SearchBoundary boundary;

  public boolean quite = false;

  // Round-trip desirability heatmap (issue #15) — experimental, default off,
  // gated on RoutingContext.roundTripDesirability and honoured by GREEDY only.
  // When enabled, the GREEDY round-trip piggybacks on the isochrone expansion to
  // accumulate a coarse profile-cost-density grid keyed by ~500m cell: each value
  // is [nodeCount, prefWeightedSum], where prefWeighted rewards nodes reached
  // cheaply per air-meter (i.e. on profile-preferred roads). The grid then feeds
  // a DesirabilityCandidateProvider that biases waypoint placement toward
  // high-desirability cells. Stays empty (and unused) when the flag is off.
  static final int DESIRABILITY_CELL = 5000; // microdeg (~500m)
  /**
   * Reachability-cloud cell size for pocket-avoiding waypoint placement: every
   * node an isochrone expansion pops is bucketed into cells of roughly this
   * many meters. ~150m keeps a 5×5 neighborhood at ~750m — local enough that a
   * dead-end corridor (cells along one line) is distinguishable from a
   * junction-rich neighborhood (filled square).
   */
  static final int REACHABILITY_CELL_M = 150;
  private static final int DESIRABILITY_TOP_K = 10; // candidate cells offered per greedy step
  // Package-private so the round-trip desirability wiring test can assert the grid
  // was actually populated (i.e. the flag-on path was exercised end-to-end).
  final Map<Long, double[]> desirabilityGrid = new HashMap<>();
  // True only while an isochrone expansion is being run specifically to build the
  // GREEDY desirability grid. Keeps the accumulation off the ISO_GREEDY expansion,
  // which would otherwise populate a grid that buildCandidateProvider then discards.
  private boolean accumulatingDesirabilityGrid;
  private boolean suppressRoutingIslandGuard = false;

  private Object[] extract;

  private boolean directWeaving = !Boolean.getBoolean("disableDirectWeaving");
  private String outfile;

  double roundTripSearchRadius = 0;

  /**
   * Greedy route-choice threshold for clear accept. If ISO_GREEDY scores
   * below this, AUTO runs the plain GREEDY candidate as a comparison before
   * considering the legacy WAYPOINT fallback.
   */
  private static final double CLEAR_ACCEPT_THRESHOLD = 0.85;

  // AUTO competition runs its candidates sequentially in the calling thread and
  // cannot interrupt a child mid-run, so it shares one wall-clock budget across
  // all candidates instead of giving each the full timeout. DEFAULT applies when
  // the caller passes no timeout (maxRunningTime <= 0); MIN_CHILD guarantees a
  // spawned candidate still gets a usable slice.
  private static final long DEFAULT_AUTO_BUDGET_MS = 60_000;
  private static final long MIN_CHILD_BUDGET_MS = 5_000;
  /**
   * Set by {@link #doExplicitViaRoundTrip} when the request supplies user
   * via points in round-trip mode. Routing-time micro-detour and back-and-forth
   * removal must be skipped in this mode — those passes were designed for
   * auto-generated loops with many rt* waypoints, and they aggressively
   * delete the entire route when the closing waypoint sits at the same
   * position as the start (crow-fly = 0, which always trips the ratio
   * threshold). User-via routes are also typically short and shape-
   * preserving by intent; the user picked them, the engine should not
   * post-edit them away.
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
   * Generated cycling loops default to no ferries. Point-to-point routing keeps
   * the profile defaults, but loop generation must not discover an attractive
   * ferry shortcut unless the caller explicitly opts in via
   * {@code profile:allow_ferries=true}.
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

  /**
   * Top-level driver for round-trip (loop) generation, called from {@code doRun}
   * for {@link #BROUTER_ENGINEMODE_ROUNDTRIP}. Steps:
   * <ol>
   *   <li>Derive the internal {@code searchRadius} from {@code roundTripLength}
   *       (total loop distance / 2π) or {@code roundTripDistance} (radius).</li>
   *   <li>Resolve the start bearing (user-supplied or data-driven random draw).</li>
   *   <li>Dispatch: explicit-via mode when the caller supplied via points
   *       ({@link #doExplicitViaRoundTrip}); otherwise pick the planner —
   *       AUTO normally runs {@link #runAutoCandidateCompetition} (which writes
   *       {@code foundTrack}/{@code errorMessage} and returns early), else one of
   *       the greedy ({@link #doGreedyRoundTrip}) or waypoint
   *       ({@link #doWaypointBasedRoundTrip}) planners.</li>
   *   <li>Run the uniform acceptance gate ({@link RoundTripQualityGate#evaluate})
   *       on the produced loop, then either hard-reject (nulling {@code foundTrack}
   *       into {@code lastRejectedTrack}) or keep it and surface advisory/disclosure
   *       messages. Lenient by default; {@code roundTripStrictQuality=1} hard-rejects
   *       QUALITY-tier failures.</li>
   *   <li>{@link #ensureInfoMessage} syncs the messages onto the track so they reach
   *       GPX/JSON output.</li>
   * </ol>
   * The result is the loop in {@code foundTrack}, or {@code errorMessage} set and
   * {@code foundTrack} null on failure.
   */
  public void doRoundTrip() {
    try {
      long startTime = System.currentTimeMillis();

      routingContext.useDynamicDistance = true;
      // Classify the profile's surface policy once, from its cost model (not its
      // name), so the quality gate and planner hostility checks use a consistent,
      // name-independent verdict for the rest of this request.
      RoundTripQualityGate.classifyPavedProfile(routingContext.expctxWay, routingContext.getProfileName());
      double searchRadius;
      if (routingContext.roundTripLength != null) {
        // roundTripLength is the desired total loop distance — convert to internal search radius.
        // The waypoint strategies place points at searchRadius from start and route between them,
        // so the loop traces roughly the circle circumference: total ≈ 2*PI * searchRadius.
        // Do NOT raise this factor toward L/2 (the out-and-back relation) thinking it gives a
        // "wider" loop: a closed loop traces the circumference, so a larger radius overshoots.
        // Measured across 4 real regions (urban/alpine/coastal/rural) for a 40km target, the
        // distance ratio climbs monotonically with the factor — L/2π≈0.91, 0.20→1.3, 0.25→1.6,
        // 0.33→2.1, L/2→3.2 — so L/2π is the calibrated optimum (closest to 1.0, best composite).
        searchRadius = routingContext.roundTripLength / (2 * Math.PI);
      } else {
        // Defensive floor: a non-positive roundTripDistance (e.g. set directly on
        // the context, bypassing the param-layer guard) would otherwise become a
        // zero/negative searchRadius. That ships a wrong-scale loop with the
        // distance gate silently disabled — the ratio check is skipped when
        // expectedDistance (2*PI*searchRadius) <= 0 — so floor it to the default.
        searchRadius = (routingContext.roundTripDistance == null
          || routingContext.roundTripDistance <= 0) ? 1500 : routingContext.roundTripDistance;
      }

      // Fail fast on a missing start tile. Start-tile availability is invariant
      // across every attempt below (direction × subRouteCount × AUTO candidate),
      // yet the greedy/iso paths discover it only lazily — and every earlier touch
      // point (the reachability probe, isochrone expansion) swallows the
      // IllegalArgumentException — so without this it is either re-discovered per
      // attempt or, in AUTO, wrapped as a generic "candidate threw" failure.
      // Checking once here surfaces the canonical "datafile … not found" before any
      // provider/isochrone/competition work, and keeps a missing-data start from
      // being mislabeled "start point not on road network" by the greedy planner
      // (whose matchPoint now uniformly maps any match failure to null).
      OsmNodeNamed rtStart = waypoints.get(0);
      if (nodesCache == null) {
        resetCache(false);
      }
      if (!nodesCache.hasSegmentFor(rtStart.ilon, rtStart.ilat)) {
        errorMessage = "datafile " + nodesCache.getSegmentFileName(rtStart.ilon, rtStart.ilat) + " not found";
        logInfo(errorMessage);
        return;
      }

      double direction = (routingContext.startDirection == null ? -1 :routingContext.startDirection);
      double directionAdd = (routingContext.roundTripDirectionAdd == null ? ROUNDTRIP_DEFAULT_DIRECTIONADD :routingContext.roundTripDirectionAdd);
      if (direction == -1) {
        direction = getRandomDirectionFromData(waypoints.get(0), searchRadius);
        direction += directionAdd;
      }
      // Normalize to a [0,360) compass bearing: getRandomDirectionFromData()+directionAdd
      // can exceed 360 (e.g. 332+45=377), and a user-supplied startDirection may be out of
      // range, while downstream bearing comparisons assume a normalized value.
      direction = CheapAngleMeter.normalize(direction);

      // Explicit-via round-trip: when the caller supplied via points (any
      // waypoint beyond the start), treat those vias as a hard route
      // skeleton and bypass all generated-loop placement, regardless of
      // roundTripAlgorithm. User vias express stronger intent than any AUTO
      // heuristic, so they win. Generated rt* points are never added; the via
      // order is preserved exactly; distance settings become advisory.
      boolean explicitViaMode = waypoints.size() > 1;
      if (explicitViaMode) {
        logInfo("round trip: explicit-via mode (" + (waypoints.size() - 1) + " user via points)");
        // Variety-seed disclosure: user vias are a hard skeleton expressing
        // stronger intent than any heuristic, so the alternativeidx seed is ignored.
        if (routingContext.getRoundTripSeed() > 0) {
          logInfo("alternativeidx has no effect in explicit-via round trips");
        }
        doExplicitViaRoundTrip(searchRadius, direction);
      } else {
        // Resolve the roundTripIsochrone shortcut into the canonical
        // roundTripAlgorithm ONCE, so the algorithm is the single source of
        // truth from here down and the boolean never has to propagate to child
        // contexts. Honoured only when no explicit algorithm was chosen — an
        // explicit algorithm always wins.
        if (routingContext.roundTripAlgorithm == RoundTripAlgorithm.AUTO
            && routingContext.roundTripIsochrone) {
          routingContext.roundTripAlgorithm = RoundTripAlgorithm.ISOCHRONE;
        }
        RoundTripAlgorithm algo = routingContext.roundTripAlgorithm;

        // AUTO candidate competition.
        //
        // Generated loops default to greedy Dijkstra construction. AUTO runs
        // ISO_GREEDY first, then GREEDY, and considers the legacy
        // WAYPOINT/probe path only as a separately scored fallback candidate
        // if greedy cannot produce an accepted route (see
        // runAutoCandidateCompetition for the full competition policy).
        if (algo == RoundTripAlgorithm.AUTO
            && greedySupports(routingContext.allowSamewayback, waypoints.size())) {
          runAutoCandidateCompetition(searchRadius, direction);
          // The competition method writes foundTrack / errorMessage directly.
          return;
        }

        if (algo == RoundTripAlgorithm.AUTO) {
          algo = selectRoundTripAlgorithm(searchRadius);
        }
        logInfo("round trip algorithm: " + algo);

        if (algo == RoundTripAlgorithm.GREEDY || algo == RoundTripAlgorithm.ISO_GREEDY) {
          if (!greedySupports(routingContext.allowSamewayback, waypoints.size())) {
            // Greedy generates its own intermediate points and does not honor
            // allowSamewayback. (User vias are handled in explicitViaMode above.)
            logInfo("greedy round trip does not support allowSamewayback, falling back to waypoint algorithm");
            doWaypointBasedRoundTrip(searchRadius, direction, RoundTripAlgorithm.WAYPOINT);
          } else {
            // ISO_GREEDY: isochrone-derived candidate pool. Falls back to plain
            // GREEDY internally if the candidate pool is insufficient.
            doGreedyRoundTrip(searchRadius, direction, algo);
          }
        } else {
          doWaypointBasedRoundTrip(searchRadius, direction, algo);
        }
      }

      if (foundTrack == null && errorMessage != null) {
        return;
      }

      // A loop needs at least a triangle (start + 2 intermediate waypoints). With a single
      // intermediate the route is only an out-and-back, which closure/detour handling cannot
      // turn into a loop. Same-way-back is the deliberate exception (it IS an out-and-back).
      //
      // Explicit-via mode skips this check: a single user-supplied via is a valid
      // route skeleton (start → via1 → start), even though the result shape is
      // out-and-back. The user is expressing route intent, not a loop request.
      int intermediateWaypoints = (matchedWaypoints == null) ? 0 : matchedWaypoints.size() - 2;
      if (!routingContext.allowSamewayback && !explicitViaMode
          && intermediateWaypoints < MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS) {
        errorMessage = "round-trip could not place enough waypoints to form a loop (need "
          + MIN_ROUNDTRIP_INTERMEDIATE_WAYPOINTS + " intermediate, got " + Math.max(0, intermediateWaypoints)
          + ") for direction " + (int) direction + " at radius " + (int) searchRadius + "m";
        logInfo(errorMessage);
        foundTrack = null;
        return;
      }

      // Contract: a round-trip must yield an actual loop. When intermediate waypoints
      // cannot be placed on reachable roads (e.g. the requested direction has no roads
      // within this radius), routing collapses to a 1-3 node stub. Report that as a
      // failure rather than returning a non-loop as success.
      //
      // Explicit-via mode also bypasses the strict node/length floors: a short
      // one-via route may produce fewer than MIN_ROUNDTRIP_LOOP_NODES if the
      // via is right next to the start. We still reject null/no-track outcomes
      // below as a safety net.
      if (foundTrack == null || foundTrack.nodes == null
          || (!explicitViaMode && (foundTrack.nodes.size() < MIN_ROUNDTRIP_LOOP_NODES
                                || foundTrack.distance < MIN_ROUNDTRIP_LOOP_METERS))) {
        int n = (foundTrack == null || foundTrack.nodes == null) ? 0 : foundTrack.nodes.size();
        int d = foundTrack == null ? 0 : foundTrack.distance;
        errorMessage = "round-trip could not form a loop for direction " + (int) direction
          + " at radius " + (int) searchRadius + "m (only " + n + " nodes, " + d
          + "m) — no reachable roads in that direction at this distance";
        logInfo(errorMessage);
        lastRejectedTrack = foundTrack; // preserve stub for post-mortem
        foundTrack = null;
        return;
      }

      // Production-safety acceptance gate: applied uniformly across all
      // round-trip algorithms (WAYPOINT/ISOCHRONE/GREEDY/ISO_GREEDY) right
      // before returning success. The gate rejects unsafe routes (beeline
      // segments, broken closure, distance way off, profile-hostile surfaces,
      // accidental mid-route backtracking). Acceptance is shape-aware:
      // STRICT_LOOP/LOLLIPOP/OUT_AND_BACK each get explicit
      // disclosures so the cyclist knows what they're getting; only
      // INVALID_RETRACE is rejected. See {@link RoundTripQualityGate}.
      double expectedDistance = 2 * Math.PI * searchRadius;
      String profileName = routingContext.getProfileName();
      // Explicit-via mode treats the requested distance as advisory only —
      // the user-supplied skeleton defines the route, not the distance target.
      // The gate still enforces beeline / closure / profile-hostility checks.
      // A forced same-way-back corridor (planner found nothing clean in this
      // constrained terrain) is accepted as a disclosed OUT_AND_BACK rather than
      // rejected — keep-when-forced. Gratuitous corridors never reach here: the
      // planner only sets the flag when no clean alternative exists.
      boolean allowSamewayback = routingContext.allowSamewayback || roundTripForcedCorridorAccepted;
      RoundTripQualityResult quality = RoundTripQualityGate.evaluate(foundTrack, expectedDistance,
        profileName, allowSamewayback, explicitViaMode, roundTripFerriesAllowed());
      if (!quality.isAccepted()) {
        // STRUCTURAL failures (broken / un-routable / not-a-loop) are always
        // hard-rejected — there is nothing usable to offer. QUALITY failures
        // (distance off-target, self-crossing/hairpin chaos, hostile surface,
        // mid-route backtracking) are advisory by default: the route is
        // rideable, so we return it with a Warning and let the user decide.
        // roundTripStrictQuality=1 restores the old hard-reject behaviour.
        boolean hardReject = roundTripQualityHardReject(quality);
        if (hardReject) {
          errorMessage = "round-trip rejected by quality gate (direction " + (int) direction
            + ", radius " + (int) searchRadius + "m, shape=" + quality.getShape() + "): "
            + quality.getRejectionReason();
          logInfo(errorMessage);
          lastRejectedTrack = foundTrack;
          foundTrack = null;
          return;
        }
        // Lenient default: surface the quality issue as a warning and keep the
        // route. The planner already searched strictly and shipped its best
        // effort; we disclose the problem rather than discard a rideable loop.
        String advisory = "Warning: " + quality.getRejectionReason()
          + " (shape=" + quality.getShape() + ") — route returned anyway; ride at your"
          + " discretion, or set roundTripStrictQuality=1 to reject it.";
        logInfo("round-trip quality advisory (lenient): " + advisory);
        appendRouteMessage(foundTrack, advisory);
        // fall through to disclosure surfacing + success
      }
      // Surface the route shape + disclosures (e.g. "contains retraced
      // scenic spur: 4.2km") so the cyclist isn't surprised to find
      // they're returning the same way along a stretch. Stays in the
      // route message stream so it propagates to GPX/JSON exports.
      logInfo("round-trip quality: " + quality);
      for (String d : quality.getDisclosures()) {
        appendRouteMessage(foundTrack, d);
      }

      // Transparency for the silent band: 1..MAX crossings and guard-blocked
      // spurs pass the gate without any message, yet the cyclist sees them on
      // the map. Disclose every nonzero count — informational only, the route
      // ships either way (lenient product policy: odd-but-cycleable > nothing).
      int shippedCrossings = RoundTripQualityGate.countSelfIntersections(foundTrack);
      if (shippedCrossings > 0) {
        appendRouteMessage(foundTrack, String.format(Locale.US,
          "Note: route crosses its own path %d time%s.",
          shippedCrossings, shippedCrossings == 1 ? "" : "s"));
      }
      if (foundTrack.nodes != null) {
        int[] spurInfo = LoopQualityMetrics.computeSpurInfo(foundTrack.nodes);
        if (spurInfo[0] > 0 && spurInfo[1] > 600) {
          appendRouteMessage(foundTrack, String.format(Locale.US,
            "Note: route contains %d out-and-back section%s (longest %.1fkm).",
            spurInfo[0], spurInfo[0] == 1 ? "" : "s", spurInfo[1] / 1000.0));
        }
      }

      // Residual-chord advisory (loop-review backlog item 1): the planner's
      // fidelity enforcement retries chord legs, but a best-effort adoption or
      // a non-greedy path can still ship a long null-tag edge that renders as
      // a straight line cutting across terrain. Ground truth (Lozère study):
      // these follow a real curving road whose detail is missing, so the route
      // is rideable — disclose, don't reject. Same threshold as the planner's
      // fidelity check so the two mechanisms never disagree about what a
      // chord is.
      int chordMeters = LoopQualityMetrics.maxSingleNullEdgeMeters(foundTrack);
      if (chordMeters > GreedyRoundTripPlanner.MAX_UNDETAILED_EDGE_METERS) {
        appendRouteMessage(foundTrack, String.format(Locale.US,
          "Note: route contains an undetailed straight-line section of ~%dm "
            + "(way detail missing in the map data; the actual road may curve).",
          chordMeters));
      }

      // Soft advisory: even within the [0.5, 1.8] ratio band, a >1.5
      // overshoot is worth flagging so the caller can suggest a shorter
      // distance. This stays informational because the hard gate above
      // already rejects ratios outside the safe range.
      if (foundTrack.distance > 0) {
        double ratio = foundTrack.distance / expectedDistance;
        if (ratio > 1.5) {
          String warning = String.format(
            "Warning: route distance (%dkm) exceeds requested loop distance (%dkm) by %.0f%%. "
            + "The road network in this area is too constrained for a compact loop at this distance. "
            + "Consider a shorter distance or an out-and-back route.",
            foundTrack.distance / 1000, (int) (expectedDistance / 1000), (ratio - 1) * 100);
          logInfo(warning);
          appendRouteMessage(foundTrack, warning);
        }
      }

      // The advisory/disclosures above were appended to foundTrack.message, but
      // FormatGpx emits <brouter:info> and its message comments from
      // messageList, not message. Sync messageList[0] so the quality warning
      // actually reaches GPX/JSON consumers. Idempotent; no-op for the AUTO
      // path (which returns earlier and syncs via adoptCandidateWinner).
      ensureInfoMessage(foundTrack);

      long endTime = System.currentTimeMillis();
      logInfo("round trip execution time = " + (endTime - startTime) / 1000. + " seconds");
    } catch (Exception e) {
      logException(e);
      logThrowable(e);
    } finally {
      cleanupRoutingResources();
    }

  }

  /**
   * Append a one-line message to {@code track.message}, space-separated.
   * Used to surface advisories and quality-gate disclosures so that
   * downstream GPX/JSON formatters carry the information to the cyclist.
   * No-op if either argument is null/empty.
   */
  private static void appendRouteMessage(OsmTrack track, String message) {
    if (track == null || message == null || message.isEmpty()) return;
    if (track.message == null || track.message.isEmpty()) {
      track.message = message;
    } else {
      track.message += " " + message;
    }
  }

  /**
   * Single source of truth for the round-trip lenient/strict policy: whether a
   * non-accepted quality verdict must be hard-rejected rather than returned with
   * an advisory. STRUCTURAL failures (broken / un-routable / not-a-loop) are
   * always hard-rejected; QUALITY failures (rideable but suboptimal) are
   * advisory by default and hard-rejected only in strict mode
   * ({@link RoutingContext#roundTripStrictQuality}). Used by the gate path and
   * the AUTO best-effort fallback so the two never drift apart.
   */
  private boolean roundTripQualityHardReject(RoundTripQualityResult quality) {
    return quality.getRejectionTier() != RoundTripQualityResult.RejectionTier.QUALITY
      || routingContext.roundTripStrictQuality;
  }

  /**
   * Keep the round-trip lifecycle equivalent to the normal {@link #doRouting(long)}
   * finally block: release the parsed profile, close any cache/log resources,
   * clear search state, and signal {@link #isFinished()}.
   *
   * <p>This is intentionally idempotent. Some round-trip paths delegate through
   * {@code doRouting()}, which already performs the same cleanup, while direct
   * planner-track adoption paths never enter {@code doRouting()} at all.
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

  static RoundTripAlgorithm selectRoundTripAlgorithm(double searchRadius) {
    // Cheap fallback selector. The full AUTO policy lives in
    // {@link #runAutoCandidateCompetition}; this helper remains as a stable
    // entry point for direct callers and unsupported AUTO modes.
    return RoundTripAlgorithm.GREEDY;
  }

  /**
   * AUTO candidate competition for generated round trips (no user vias).
   *
   * <p>Runs greedy candidates first and uses the old probe/WAYPOINT generator
   * only as a fallback:
   * <ol>
   *   <li>ISO_GREEDY — iso-derived candidates fed to the greedy planner.
   *       Profile-aware by construction.</li>
   *   <li>GREEDY — plain greedy graph-native/top-k planner if ISO_GREEDY fails or is weak.</li>
   *   <li>WAYPOINT/probe — legacy fallback only if greedy produced no accepted route.</li>
   * </ol>
   *
   * <p>Each candidate runs inside an isolated <em>child</em> {@link RoutingEngine}
   * built from a request-fields-only copy of the parent's
   * {@link RoutingContext} — no parsed/runtime state is shared. Child output
   * is suppressed (no GPX/log written). After all candidates have run, the
   * highest-scoring accepted candidate's {@link OsmTrack} is adopted as
   * this engine's {@code foundTrack} and its disclosures are surfaced.
   *
   * <p>If no candidate passes strict validation, the lenient default adopts the
   * least-bad QUALITY-tier best-effort track (see {@link #selectBestEffortCandidate});
   * strict mode instead leaves {@code foundTrack} null and sets {@code errorMessage}.
   */
  private void runAutoCandidateCompetition(double searchRadius, double direction) {
    long t0 = System.currentTimeMillis();
    // One wall-clock budget shared across the sequentially-run candidates, so
    // the competition cannot run ~Nx the requested timeout. Each child gets the
    // remaining slice (see runChildCandidate); once it is exhausted we stop
    // spawning further candidates.
    long deadline = t0 + (maxRunningTime > 0 ? maxRunningTime : DEFAULT_AUTO_BUDGET_MS);
    List<RoundTripCandidateResult> results = new ArrayList<>(3);

    // 1. ISO_GREEDY.
    RoundTripCandidateResult isoGreedyR = runChildCandidate(
      RoundTripAlgorithm.ISO_GREEDY, searchRadius, direction, deadline);
    results.add(isoGreedyR);
    logInfo("AUTO candidate: " + isoGreedyR);

    // 2. If ISO_GREEDY was weak/marginal/failed, run GREEDY for comparison.
    //    The spec calls for GREEDY when iso pool is not viable OR ISO_GREEDY
    //    is weak — we use the same single threshold for both signals.
    boolean isoGreedyWeak = !isoGreedyR.accepted()
      || isoGreedyR.scoreValue() < CLEAR_ACCEPT_THRESHOLD;
    if (isoGreedyWeak && System.currentTimeMillis() < deadline) {
      RoundTripCandidateResult greedyR = runChildCandidate(
        RoundTripAlgorithm.GREEDY, searchRadius, direction, deadline);
      results.add(greedyR);
      logInfo("AUTO candidate: " + greedyR);
    }

    // 3. Compare accepted greedy candidates; pick highest score.
    RoundTripCandidateResult winner = null;
    for (RoundTripCandidateResult r : results) {
      if (!r.accepted()) continue;
      if (winner == null || r.scoreValue() > winner.scoreValue()) {
        winner = r;
      }
    }

    // 4. Legacy fallback only if both greedy variants failed hard validation
    //    and budget remains.
    if (winner == null && System.currentTimeMillis() < deadline) {
      RoundTripCandidateResult waypointR = runChildCandidate(
        RoundTripAlgorithm.WAYPOINT, searchRadius, direction, deadline);
      results.add(waypointR);
      logInfo("AUTO candidate: " + waypointR);
      if (waypointR.accepted()) {
        winner = waypointR;
      }
    }

    // 5. Last-resort ISOCHRONE fallback. The direct isochrone-frontier
    //    placement reaches loops the greedy radial candidates miss in
    //    constrained terrain (e.g. a valley where the radial probe can't
    //    form a loop in the requested direction, or only finds a chaotic
    //    one). Purely additive: only runs when ISO_GREEDY, GREEDY and
    //    WAYPOINT have all already failed, so it cannot displace a winner.
    if (winner == null && System.currentTimeMillis() < deadline) {
      RoundTripCandidateResult isochroneR = runChildCandidate(
        RoundTripAlgorithm.ISOCHRONE, searchRadius, direction, deadline);
      results.add(isochroneR);
      logInfo("AUTO candidate: " + isochroneR);
      if (isochroneR.accepted()) {
        winner = isochroneR;
      }
    }
    long totalMs = System.currentTimeMillis() - t0;

    // Lenient default: if no candidate passed strict validation but one produced
    // a rideable route that failed only a QUALITY check, adopt the best-effort
    // one (the child already attached its "Warning:" advisory) instead of
    // returning nothing — keeping AUTO consistent with direct-dispatch leniency.
    // Candidates are in algorithm-quality order (ISO_GREEDY, GREEDY, WAYPOINT,
    // ISOCHRONE), so the first quality-failed track is the best best-effort.
    // The lenient/strict decision uses the same predicate as the gate path
    // (roundTripQualityHardReject), so strict mode keeps the hard "no acceptable
    // route" and only QUALITY verdicts are adopted leniently.
    if (winner == null) {
      // Among the QUALITY-tier best-effort candidates (STRUCTURAL and, under strict
      // mode, every failure are excluded by roundTripQualityHardReject), pick the
      // LEAST-BAD overall rather than the first by algorithm order. We rank with the
      // same multi-factor RouteChoiceScore used for accepted winners — distance
      // closeness (its largest weight), profile cost/m match, and reuse/shape — so
      // each candidate is penalised on the very axis it failed and the most rideable
      // degraded loop wins. No extra routing: the tracks are already generated.
      List<RoundTripCandidateResult> bestEffort = new ArrayList<>();
      for (RoundTripCandidateResult r : results) {
        if (r.track != null && r.gateVerdict != null
            && !roundTripQualityHardReject(r.gateVerdict)) {
          bestEffort.add(r);
        }
      }
      winner = selectBestEffortCandidate(bestEffort, 2 * Math.PI * searchRadius,
        routingContext.getProfileName(), direction);
      if (winner != null) {
        logInfo("AUTO: no strictly-accepted route; adopting best-effort " + winner.algorithm
          + " (most rideable of " + bestEffort.size()
          + " degraded candidate(s)) with quality warning (lenient mode)");
      }
    }

    if (winner == null) {
      // All candidates failed. Surface the most recent (richest) error.
      String err = null;
      for (int i = results.size() - 1; i >= 0; i--) {
        if (results.get(i).errorMessage != null) { err = results.get(i).errorMessage; break; }
      }
      errorMessage = "AUTO competition produced no acceptable route "
        + "(tried " + results.size() + " candidates in " + totalMs + "ms): "
        + (err == null ? "unknown" : err);
      logInfo(errorMessage);
      // Surface the best-geometry rejected candidate for post-mortem inspection,
      // mirroring the direct-dispatch path which sets lastRejectedTrack before
      // nulling foundTrack. Candidates are in algorithm-quality order, so the
      // first with a track is the best available rejected geometry.
      for (RoundTripCandidateResult r : results) {
        if (r.track != null) {
          lastRejectedTrack = r.track;
          break;
        }
      }
      foundTrack = null;
      return;
    }
    adoptCandidateWinner(winner, results, totalMs);
  }

  /**
   * Rank degraded best-effort round-trip candidates and return the most rideable,
   * or {@code null} if none have a track. Scores each with the multi-factor
   * {@link RouteChoiceScore#scoreBestEffort}, which bypasses the scorer's
   * accepted-only zero-guard (so a rejected track is ranked on its real geometry
   * instead of collapsing to 0) while still consuming the candidate's gate
   * verdict for the shape disclosure penalty — a rejected LOLLIPOP/OUT_AND_BACK
   * must not rank as if it were a strict loop. Because every QUALITY failure
   * also corresponds to a weak component in the score (distance miss → low
   * distance term, hostile surface → low cost/m term, chaos/retrace → low reuse
   * term), the least-bad overall candidate wins. Ties keep {@code candidates}
   * order (the AUTO algorithm-quality order). Does no routing — the tracks are
   * already built.
   */
  static RoundTripCandidateResult selectBestEffortCandidate(
      List<RoundTripCandidateResult> candidates, double expectedDistance,
      String profileName, double direction) {
    RoundTripCandidateResult best = null;
    double bestScore = -1.0;
    RouteChoiceScore.Verdict bestVerdict = null;
    for (RoundTripCandidateResult r : candidates) {
      if (r.track == null) {
        continue;
      }
      RouteChoiceScore.Verdict v = RouteChoiceScore.scoreBestEffort(
        r.track, expectedDistance, profileName, r.gateVerdict, direction);
      double s = v.score();
      if (s > bestScore) {
        bestScore = s;
        best = r;
        bestVerdict = v;
      }
    }
    // Surface the computed best-effort score on the winner so the adoption
    // summary logs the real value (and the score breakdown) instead of 0.000;
    // r.score is otherwise only set for strictly-accepted candidates.
    if (best != null && best.score == null) {
      best.score = bestVerdict;
    }
    return best;
  }

  /**
   * Budget (ms) for the next sequential AUTO candidate: the time remaining to
   * the shared competition deadline, floored at {@link #MIN_CHILD_BUDGET_MS} so
   * a candidate that is still spawned gets a usable slice rather than ~0.
   */
  static long childCandidateBudgetMs(long deadline, long now) {
    return Math.max(MIN_CHILD_BUDGET_MS, deadline - now);
  }

  /**
   * Run one AUTO candidate in an isolated child engine, score the result,
   * and return the wrapper. Never throws — failures land in
   * {@link RoundTripCandidateResult#errorMessage}.
   */
  private RoundTripCandidateResult runChildCandidate(RoundTripAlgorithm algo,
                                                     double searchRadius, double direction,
                                                     long deadline) {
    long t0 = System.currentTimeMillis();
    RoundTripCandidateResult r = new RoundTripCandidateResult(algo);
    try {
      RoutingContext childCtx = routingContext.copyRequestFields();
      childCtx.roundTripAlgorithm = algo;
      childCtx.startDirection = (int) direction;
      // Inherit the user's direction intent from copyRequestFields rather than
      // hard-forcing it. forceUseStartDirection makes the first leg leave on a
      // strict bearing; when the user supplied only a soft `direction` (or
      // none) that over-constrains the loop and can shove the opening leg onto
      // a profile-hostile stretch, failing a candidate that the same algorithm
      // accepts when free to pick a nearby bearing. Only an explicit `heading`
      // (which sets forceUseStartDirection on the parent) hard-forces here.
      // Copy waypoint list — child engine mutates its own list.
      List<OsmNodeNamed> childWps = new ArrayList<>(waypoints.size());
      for (OsmNodeNamed wp : waypoints) {
        OsmNodeNamed copy = new OsmNodeNamed(new OsmNode(wp.ilon, wp.ilat));
        copy.name = wp.name;
        childWps.add(copy);
      }
      // Output suppressed (null outfileBase). Child runs its own pipeline
      // including post-routing checks + quality gate; we just inspect the
      // result.
      RoutingEngine child = new RoutingEngine(null, null, segmentDir, childWps, childCtx,
        BROUTER_ENGINEMODE_ROUNDTRIP);
      child.quite = true;
      // Give the child only the remaining shared budget (floored so a spawned
      // candidate still gets a usable slice), not the full request timeout.
      long budget = childCandidateBudgetMs(deadline, System.currentTimeMillis());
      child.doRun(budget);
      r.track = child.foundTrack;
      r.errorMessage = child.errorMessage;
      r.runtimeMillis = System.currentTimeMillis() - t0;
      if (child.lastRoundTripResult != null) {
        RoundTripResult cr = child.lastRoundTripResult;
        r.routedIsoCandidates = cr.getRoutedIsoCandidates();
        r.routedNonIsoCandidates = cr.getRoutedNonIsoCandidates();
        r.acceptedIsoLegs = cr.getAcceptedIsoLegs();
        r.acceptedNonIsoLegs = cr.getAcceptedNonIsoLegs();
        // Keep-when-forced: the child accepted a same-way-back corridor because
        // nothing clean exists. Without carrying this, the re-gate below would
        // reject the track the child engine accepted by design (the direct
        // routing path honors the same flag at the main gate).
        r.forcedCorridorAccepted = cr.isForcedCorridorAccepted();
      }

      if (r.track != null) {
        // Score against the parent's expected loop distance. This produces
        // a verdict that may differ from the child's internal gate result
        // because the parent's routingContext is the source of truth (e.g.
        // for profile-name lookup), but in practice both agree.
        double expectedDist = 2 * Math.PI * searchRadius;
        String profileName = routingContext.getProfileName();
        r.gateVerdict = RoundTripQualityGate.evaluate(r.track, expectedDist,
          profileName, routingContext.allowSamewayback || r.forcedCorridorAccepted,
          false, roundTripFerriesAllowed());
        if (r.gateVerdict.isAccepted()) {
          r.score = RouteChoiceScore.score(r.track, expectedDist,
            profileName, r.gateVerdict, direction);
        }
      }
    } catch (RuntimeException e) {
      // Preserve the exception type: e.getMessage() is null for NPE/AIOOBE/CCE,
      // which otherwise surfaces an undiagnosable "threw: null" to the operator.
      // Also log the full stack trace on the parent (which, unlike the child, is
      // not `quite`) so a recurring child failure is diagnosable from logs — the
      // child suppressed its own logging via quite=true + null outfileBase.
      logThrowable(e);
      r.errorMessage = "candidate " + algo + " threw: " + e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage());
      r.runtimeMillis = System.currentTimeMillis() - t0;
    }
    return r;
  }

  /**
   * Adopt the winning candidate's track as this engine's result, attach
   * a summary diagnostic listing what was tried and which won.
   */
  private void adoptCandidateWinner(RoundTripCandidateResult winner,
                                    List<RoundTripCandidateResult> all, long totalMs) {
    foundTrack = winner.track;
    errorMessage = null;
    finalizeAdoptedRoundTripTrack(foundTrack, foundTrack == null ? null : foundTrack.matchedWaypoints);
    // Best-effort (quality-failed) winner adopted under lenient mode: make sure
    // the user-facing quality Warning is present. The child engine usually
    // attaches it, but when the parent's gate re-evaluation in runChildCandidate
    // disagrees with the child's own verdict the child may not have — so attach
    // it here if absent, mirroring the direct-dispatch advisory (and skip when a
    // "Warning:" is already present to avoid a duplicate).
    if (foundTrack != null && !winner.accepted() && winner.gateVerdict != null
        && (foundTrack.message == null || !foundTrack.message.contains("Warning:"))) {
      appendRouteMessage(foundTrack, "Warning: " + winner.gateVerdict.getRejectionReason()
        + " (shape=" + winner.gateVerdict.getShape() + ") — route returned anyway; ride at your"
        + " discretion, or set roundTripStrictQuality=1 to reject it.");
    }
    // Append a summary message so debugging consumers can see the
    // competition outcome. Score breakdown is in the route-choice verdict.
    StringBuilder summary = new StringBuilder(256);
    summary.append("AUTO selected ").append(winner.algorithm)
      .append(" (score ").append(String.format(Locale.US, "%.3f", winner.scoreValue()))
      .append(") after ").append(all.size()).append(" candidate(s) in ").append(totalMs).append("ms.");
    for (RoundTripCandidateResult r : all) {
      if (r == winner) continue;
      summary.append(" Also tried ").append(r.algorithm).append(": ")
        .append(r.accepted() ? String.format(Locale.US, "score %.3f", r.scoreValue())
                             : (r.errorMessage == null ? "no track" : "rejected"))
        .append('.');
    }
    if (foundTrack != null) {
      // foundTrack is nullable here (a best-effort winner can carry no track —
      // see the null-guards above at adoption and the warning block); only
      // attach the AUTO summary when there is a track to annotate.
      if (foundTrack.message == null || foundTrack.message.isEmpty()) {
        foundTrack.message = summary.toString();
      } else {
        foundTrack.message += " " + summary.toString();
      }
    }
    // Keep messageList.get(0) in sync with the just-extended message so the
    // GPX <brouter:info> / comment block reflects the AUTO summary too.
    ensureInfoMessage(foundTrack);
    logInfo(summary.toString());
    if (winner.score != null) {
      logInfo("AUTO winner score breakdown:\n" + winner.score.describe());
    }
    // Format + persist the adopted track if the caller asked for an
    // output file. The child engines ran with null outfileBase (output
    // suppressed); the parent does the single final write.
    writeAdoptedTrackOutput(foundTrack);
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

  /**
   * Guarantee the track carries the standard one-line info message and a
   * matching {@code messageList}, mirroring the direct-routing output path in
   * {@link #doRouting} (lines that set {@code track.message} /
   * {@code track.messageList}). Round-trip result tracks are assembled from
   * merged segments via {@code new OsmTrack()} and otherwise reach the
   * formatters with {@code messageList == null}, which made
   * {@link FormatGpx#formatAsGpx} throw a NullPointerException on export.
   * Idempotent: re-running it keeps {@code messageList.get(0)} in sync with
   * any later additions to {@code track.message} (e.g. the AUTO summary).
   */
  private void ensureInfoMessage(OsmTrack track) {
    if (track == null) {
      return;
    }
    if (track.message == null || track.message.isEmpty()) {
      track.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend
        + " plain-ascend = " + track.plainAscend + " cost=" + track.cost;
      if (track.energy != 0) {
        track.message += " energy=" + Formatter.getFormattedEnergy(track.energy)
          + " time=" + Formatter.getFormattedTime2(track.getTotalSeconds());
      }
    }
    if (track.messageList == null) {
      track.messageList = new ArrayList<>();
    }
    if (track.messageList.isEmpty()) {
      track.messageList.add(track.message);
    } else {
      track.messageList.set(0, track.message);
    }
  }

  /**
   * Bring a directly adopted round-trip track up to the same metadata
   * contract that {@link #doRouting(long)} provides before returning:
   * matched waypoints attached, indices filled, origin chain coherent for
   * voice hints, speed/profile fields, POIs, and export flags.
   */
  private void finalizeAdoptedRoundTripTrack(OsmTrack track, List<MatchedWaypoint> mwps) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()) return;
    boolean haveMwps = mwps != null && !mwps.isEmpty();
    if (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP
        && !routingContext.allowSamewayback && !explicitViaRoundTrip) {
      // removeBackAndForthSegments locates each waypoint's spur via its
      // indexInTrack, so populate those indices first. The direct greedy-bypass
      // path supplies matchedWaypoints with indexInTrack still 0 (the planner
      // never sets it); without this the back-and-forth removal silently skips
      // every waypoint and is a no-op. Indices are refreshed below after the
      // removals shift the node list.
      // removeBackAndForthSegments / removeMicroDetours iterate mwps and would
      // NPE on a null list (the spur-removal is a no-op without waypoints), so
      // keep all three consumers under the same haveMwps guard.
      if (haveMwps) {
        assignMatchedWaypointIndexes(track, mwps);
        removeBackAndForthSegments(track, mwps);
        removeMicroDetours(track, 1500, mwps);
        repairViaPinnedBulges(track, mwps);
        removeArtifactSpurSpans(track, mwps);
      }
    }
    rebuildOriginChain(track);
    recalcTrack(track);
    ensureInfoMessage(track);
    if (haveMwps) {
      assignMatchedWaypointIndexes(track, mwps);
      matchedWaypoints = mwps;
      track.matchedWaypoints = mwps;
    }
    track.processVoiceHints(routingContext);
    if (engineMode == BROUTER_ENGINEMODE_ROUNDTRIP) {
      consolidateRoundTripVoiceHints(track);
    }
    track.prepareSpeedProfile(routingContext);
    track.showTime = routingContext.showTime;
    track.params = routingContext.keyValues;
    if (routingContext.poipoints != null) {
      track.pois = routingContext.poipoints;
    }
    track.exportWaypoints = routingContext.exportWaypoints;
    track.exportCorrectedWaypoints = routingContext.exportCorrectedWaypoints;
  }

  private static void assignMatchedWaypointIndexes(OsmTrack track, List<MatchedWaypoint> mwps) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()
        || mwps == null || mwps.isEmpty()) {
      return;
    }
    int lastNodeIndex = track.nodes.size() - 1;
    int searchFrom = 0;
    for (int i = 0; i < mwps.size(); i++) {
      MatchedWaypoint mwp = mwps.get(i);
      if (i == 0) {
        mwp.indexInTrack = 0;
        continue;
      }
      if (i == mwps.size() - 1) {
        mwp.indexInTrack = lastNodeIndex;
        continue;
      }
      OsmNode target = mwp.crosspoint != null ? mwp.crosspoint : mwp.waypoint;
      if (target == null) {
        mwp.indexInTrack = Math.min(searchFrom, lastNodeIndex);
        continue;
      }
      int bestIndex = searchFrom;
      int bestDistance = Integer.MAX_VALUE;
      for (int n = searchFrom; n <= lastNodeIndex; n++) {
        int d = track.nodes.get(n).calcDistance(target);
        if (d < bestDistance) {
          bestDistance = d;
          bestIndex = n;
        }
      }
      mwp.indexInTrack = bestIndex;
      searchFrom = bestIndex;
    }
  }

  /**
   * Whether greedy round-trip planning can be applied with the given inputs.
   * Greedy currently generates its own intermediate waypoints from the start,
   * so user-supplied via points and allowSamewayback are not honored.
   */
  static boolean greedySupports(boolean allowSamewayback, int waypointCount) {
    return !allowSamewayback && waypointCount <= 1;
  }

  /**
   * Explicit-via round-trip: the caller supplied via points; route through
   * them exactly, in input order, with no generated {@code rt*} waypoints.
   *
   * <p>Routing skeleton:
   * <ul>
   *   <li>{@code allowSamewayback=false}: {@code start → via1 → ... → viaN → start}</li>
   *   <li>{@code allowSamewayback=true}:  {@code start → via1 → ... → viaN → viaN-1 → ... → via1 → start}
   *       (mirroring is applied by the existing {@code doRouting} expansion;
   *       this helper only supplies the forward chain).</li>
   * </ul>
   *
   * <p>{@code roundTripPoints} is ignored. {@code roundTripDistance} /
   * {@code roundTripLength} become advisory targets only — the quality gate
   * runs in explicit-via mode and converts distance-ratio mismatch to a
   * disclosure rather than a rejection. {@code startDirection} is logged but
   * does not influence the via order.
   *
   * <p>A user via that cannot be snapped within range fails with an error
   * naming the via (the no-beeline invariant is preserved). The helper never
   * silently drops a user via.
   *
   * @param searchRadius used only to size the snap tolerance and for logging
   * @param direction    logged for diagnostics; not used to reorder vias
   */
  private void doExplicitViaRoundTrip(double searchRadius, double direction) {
    OsmNodeNamed start = waypoints.get(0);
    List<OsmNodeNamed> userVias = new ArrayList<>(waypoints.subList(1, waypoints.size()));
    waypoints.subList(1, waypoints.size()).clear();
    // Default-name only blanks; preserve any user-supplied via names so that
    // diagnostic output references the user's identifiers.
    for (int i = 0; i < userVias.size(); i++) {
      OsmNodeNamed v = userVias.get(i);
      if (v.name == null || v.name.isEmpty()) {
        v.name = "via" + (i + 1);
      }
    }

    // Snap start and every user via. Failure on a user via is fatal and
    // names the via — explicit vias are hard constraints, never dropped.
    // Note: `snapStartToRoad(waypoints, ...)` short-circuits when
    // waypoints.size() < 2, so we snap the start directly via the
    // single-waypoint helper to avoid that early-return.
    double userSnapDist = Math.min(searchRadius * 0.3, 2000);
    snapStartProfileAware(start, userSnapDist);
    List<Boolean> matched = snapWaypointsToRoad(userVias, userSnapDist, "snapUserVia");
    for (int i = 0; i < userVias.size(); i++) {
      if (!matched.get(i)) {
        throw new IllegalArgumentException("user waypoint " + userVias.get(i).name
          + " has no road within " + (int) userSnapDist + "m");
      }
    }
    // Densification gate (ship A gated). OFF by default: inserting generated bulge points
    // would violate the user-via skeleton contract (no generated waypoints, order preserved),
    // so it must be explicitly opted into ({@code roundTripDensify=1} →
    // {@code explicitViaDensifyOverride=TRUE}) — a "length-honoring loop" mode. Even when opted
    // in it is gated to NON-PAVED profiles: for a road bike in sparse terrain a retracing paved
    // lollipop beats a one-way track loop the quality gate would reject, so paved keeps the
    // plain route.
    routingContext.explicitViaDensify =
      Boolean.TRUE.equals(routingContext.explicitViaDensifyOverride)
        && !RoundTripQualityGate.isPavedProfile(routingContext.getProfileName());

    // Anchor cycle [start, via1, ..., viaN]. With densification on, insert generated
    // arc-following "bulge" points between consecutive anchors so legs follow the loop
    // perimeter instead of cutting the chord (corner-cut undershoot fix).
    List<OsmNodeNamed> anchors = new ArrayList<>();
    anchors.add(start);
    anchors.addAll(userVias);

    waypoints.clear();
    if (routingContext.explicitViaDensify && !routingContext.allowSamewayback && anchors.size() >= 2) {
      waypoints.addAll(densifyViaArcs(anchors, searchRadius, userSnapDist));
    } else {
      waypoints.addAll(anchors);
    }

    // For allowSamewayback=false append the closing start copy so the route
    // forms a closed loop. For allowSamewayback=true the existing doRouting
    // expansion at the top of {@link #doRouting} mirrors the chain back —
    // we must NOT add a closing copy here or we'd double-close.
    if (!routingContext.allowSamewayback) {
      OsmNodeNamed closing = new OsmNodeNamed(new OsmNode(start.ilon, start.ilat));
      closing.name = "to";
      waypoints.add(closing);
    }

    routingContext.waypointCatchingRange = 250;
    roundTripSearchRadius = searchRadius;
    explicitViaRoundTrip = true;
    logInfo("explicit-via round-trip: " + userVias.size() + " user via(s), "
      + "allowSamewayback=" + routingContext.allowSamewayback
      + ", direction=" + (int) direction + " (advisory only)");
    doRouting(roundTripRoutingBudgetMs);
  }

  /**
   * Via-arc densification. Insert one generated "bulge" waypoint per
   * perimeter leg of the anchor cycle [start, via1, ..., viaN, (back to start)],
   * offset outward from the anchor centroid so each leg follows the loop perimeter
   * instead of cutting the chord. User anchors stay hard constraints.
   *
   * <p>Safety: a bulge is kept ONLY if it snaps to a profile-compatible way (cost-factor
   * at or below the profile's snap-reject threshold, and not a ferry-like edge). Where
   * "outward" is only profile-hostile or off-network (e.g. a road bike in sparse/alpine
   * terrain), the bulge is dropped and that leg reverts to its baseline shortest-path
   * form — so densification never forces the route onto a hostile road, which was the
   * lost-route failure mode of the unguarded version.
   * Returns the densified, ordered waypoint chain (without the closing start copy).
   */
  private List<OsmNodeNamed> densifyViaArcs(List<OsmNodeNamed> anchors, double searchRadius, double snapDist) {
    long sumLon = 0;
    long sumLat = 0;
    for (OsmNodeNamed a : anchors) {
      sumLon += a.ilon;
      sumLat += a.ilat;
    }
    int cLon = (int) (sumLon / anchors.size());
    int cLat = (int) (sumLat / anchors.size());

    double alpha = routingContext.explicitViaDensifyAlpha;
    int n = anchors.size();

    // 1. Build one candidate bulge per leg (null when degenerate, e.g. a 1-via
    //    out-and-back where the leg midpoint coincides with the centroid).
    //    candIndexForLeg maps each leg to its slot in the compacted candidates
    //    list (or -1), so acceptance can be tracked by index rather than by
    //    node identity/coordinate.
    OsmNodeNamed[] bulges = new OsmNodeNamed[n];
    int[] candIndexForLeg = new int[n];
    List<OsmNodeNamed> candidates = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      OsmNodeNamed bulge = makeArcBulge(anchors.get(i), anchors.get((i + 1) % n), cLon, cLat, alpha, searchRadius);
      bulges[i] = bulge;
      if (bulge != null) {
        candIndexForLeg[i] = candidates.size();
        candidates.add(bulge);
      } else {
        candIndexForLeg[i] = -1;
      }
    }

    // 2. Profile-aware snap (batched): accepted[c] flags candidate c BY INDEX, so
    //    two bulges that snap to the same road node are never conflated (which a
    //    coordinate-keyed set would, since OsmNode equals/hashCode key on lon/lat).
    boolean[] accepted = snapBulgesProfileAware(candidates, snapDist);

    // 3. Assemble the densified chain, keeping only accepted bulges.
    List<OsmNodeNamed> out = new ArrayList<>();
    int added = 0;
    int dropped = 0;
    for (int i = 0; i < n; i++) {
      out.add(anchors.get(i));
      if (candIndexForLeg[i] >= 0 && accepted[candIndexForLeg[i]]) {
        out.add(bulges[i]);
        added++;
      } else {
        dropped++;
      }
    }
    logInfo("explicit-via arc densification: +" + added + " arc point(s), " + dropped
      + " dropped (degenerate/hostile/off-network), alpha=" + alpha);
    return out;
  }

  /**
   * A snap match is usable only if it resolved to a real road edge: both endpoints
   * present and the matched edge short enough to be a road rather than a ferry-like
   * span (see {@link #FERRY_LIKE_EDGE_METERS}).
   */
  private static boolean isRoadSnap(MatchedWaypoint m) {
    return m.crosspoint != null && m.node1 != null && m.node2 != null
      && m.node1.calcDistance(m.node2) <= FERRY_LIKE_EDGE_METERS;
  }

  /**
   * Shared batch road-snap primitive: reset the node cache, wrap each point in a
   * {@link MatchedWaypoint}, and run the matcher once. Returns the matched list
   * (same size and order as {@code points}; each entry's crosspoint/node1/node2
   * populated where a road was found within {@code maxSnapDist}), or {@code null}
   * if the matcher threw (callers treat that as "nothing matched"). Used by the
   * profile-aware start/bulge snaps. {@link #snapWaypointsToRoad} keeps its own
   * copy of this pattern because it additionally mutates the input waypoints and
   * returns per-point booleans rather than the MatchedWaypoint objects.
   */
  private List<MatchedWaypoint> batchMatchToRoads(List<OsmNode> points, double maxSnapDist, String nameTag) {
    resetCache(false);
    List<MatchedWaypoint> mwps = new ArrayList<>(points.size());
    for (OsmNode p : points) {
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(p.ilon, p.ilat);
      mwp.name = nameTag;
      mwps.add(mwp);
    }
    try {
      nodesCache.matchWaypointsToNodes(mwps, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      return null;
    }
    return mwps;
  }

  /**
   * Batch-snap candidate bulge points, accepting a bulge ONLY if it matches a
   * profile-compatible road and is not a ferry-like long edge. Accepted bulges are
   * moved to their crosspoint. Returns a boolean[] parallel to {@code bulges}:
   * {@code accepted[i]} is true iff {@code bulges.get(i)} was accepted — indexed,
   * not coordinate-keyed, so two bulges snapping to the same node stay distinct.
   *
   * <p>The cost-factor ceiling is {@link RoutingContext#explicitViaDensifyMaxCostFactor},
   * intentionally stricter than the lenient user-snap reject threshold in
   * {@link #validateAndAdjustWaypoints}: a bulge is an optional nicety, so a
   * fastbike facing only tracks drops it and reverts to its baseline leg instead
   * of being forced onto a hostile detour.
   */
  private boolean[] snapBulgesProfileAware(List<OsmNodeNamed> bulges, double maxSnapDist) {
    boolean[] accepted = new boolean[bulges.size()];
    if (bulges.isEmpty()) {
      return accepted;
    }
    List<OsmNode> points = new ArrayList<>(bulges.size());
    for (OsmNodeNamed bulge : bulges) {
      points.add(new OsmNode(bulge.ilon, bulge.ilat));
    }
    List<MatchedWaypoint> mwps = batchMatchToRoads(points, maxSnapDist, "dvia_snap");
    if (mwps == null) {
      return accepted; // match failure — all legs revert to baseline
    }
    double rejectThreshold = routingContext.explicitViaDensifyMaxCostFactor;
    for (int i = 0; i < bulges.size(); i++) {
      MatchedWaypoint mwp = mwps.get(i);
      if (!isRoadSnap(mwp)) {
        continue;
      }
      if (snapCandidateCostFactor(mwp) > rejectThreshold) {
        continue; // not a near-ideal road for this profile — drop, leg reverts to baseline
      }
      OsmNodeNamed bulge = bulges.get(i);
      bulge.ilon = mwp.crosspoint.getILon();
      bulge.ilat = mwp.crosspoint.getILat();
      accepted[i] = true;
    }
    return accepted;
  }

  /**
   * Probe-ring radii for {@link #profileAwareMatchPoint}. The inner ring covers
   * "good road just past the nearest track" cases; the outer ring covers
   * pocket escapes (the Basel reference via sat 750m from the parallel asphalt
   * cycle route, with nothing but grade2 track in between).
   */
  private static final double[] VIA_SNAP_PROBE_RINGS = {300, 700};
  /**
   * Relocation triggers only when the plain nearest snap is meaningfully
   * profile-hostile. Below this cost factor the original snap is fine and the
   * cached graph-native leg (if any) stays valid — relocating on marginal wins
   * (cf 1.2 → 1.1) was measured to fire on most candidates, paying an extra
   * leg Dijkstra each (~6× shard runtime) and churning routes for no artifact
   * being prevented.
   */
  static final double VIA_SNAP_HOSTILE_COSTFACTOR = 2.0;
  /** ... and only when the alternative is substantially better than the
   *  original (multiplicative improvement), not a lateral move. */
  static final double VIA_SNAP_MIN_IMPROVEMENT = 0.6;
  /**
   * Loop-scale relocation bound: a via may relocate at most this fraction of
   * the round-trip search radius away from the planner's intended position.
   * The probe rings ({@link #VIA_SNAP_PROBE_RINGS}) and {@code maxSnapDist}
   * are absolute, so without this bound a small loop can have a via pulled
   * sideways by a distance comparable to the whole loop radius — measured on
   * the r=1000m fixture (after the 8802e75b metadata-consistency fix made
   * snap cost factors real): the GREEDY dir90 loop flipped clean →
   * 19%-retraced OUT_AND_BACK. At production radii (≥8000m) the cap reaches
   * {@code maxSnapDist} and behaviour is unchanged — the junk-pocket bulges
   * this relocation prevents are a long-loop phenomenon.
   */
  static final double VIA_RELOCATION_LOOP_FRACTION = 0.25;

  /**
   * Profile-aware point match for planner-generated round-trip vias. The plain
   * nearest-road match commits the via to whatever way is closest — for
   * fastbike that can be a grade2 track in a pocket whose only escape is more
   * track, which pins a junk bulge into the loop (see
   * {@link #repairViaPinnedBulges}). When (and only when) that nearest snap is
   * profile-hostile ({@link #VIA_SNAP_HOSTILE_COSTFACTOR}), probe rings
   * ({@link #VIA_SNAP_PROBE_RINGS}) are evaluated and the via moves to the
   * best-scoring road ({@code costFactor*1000 + distance}) if it improves the
   * cost factor by {@link #VIA_SNAP_MIN_IMPROVEMENT}; otherwise the plain
   * nearest match is returned unchanged. Returns null when nothing matches.
   */
  MatchedWaypoint profileAwareMatchPoint(int ilon, int ilat, String name, double maxSnapDist) {
    // Loop-scale relocation bound (see VIA_RELOCATION_LOOP_FRACTION). When no
    // round-trip scale is known (0), fall back to the absolute maxSnapDist.
    double relocationCap = roundTripSearchRadius > 0
      ? Math.min(maxSnapDist, VIA_RELOCATION_LOOP_FRACTION * roundTripSearchRadius)
      : maxSnapDist;
    OsmNode orig = new OsmNode(ilon, ilat);
    List<OsmNode> points = new ArrayList<>();
    points.add(new OsmNode(ilon, ilat));
    for (double ring : VIA_SNAP_PROBE_RINGS) {
      if (ring > relocationCap) continue; // ring would only yield over-cap candidates
      for (double bearing = 0; bearing < 360; bearing += 45) {
        int[] p = CheapRuler.destination(ilon, ilat, ring, bearing);
        points.add(new OsmNode(p[0], p[1]));
      }
    }
    List<MatchedWaypoint> mwps = batchMatchToRoads(points, maxSnapDist, name);
    if (mwps == null) return null;

    MatchedWaypoint origMatch = isRoadSnap(mwps.get(0)) ? mwps.get(0) : null;
    double origCf = origMatch != null ? snapCandidateCostFactor(origMatch) : Double.MAX_VALUE;

    MatchedWaypoint best = origMatch;
    double bestCf = origCf;
    if (origMatch == null || origCf >= VIA_SNAP_HOSTILE_COSTFACTOR) {
      // KNOWN INCONSISTENCY, kept for now: the incumbent is scored WITHOUT its
      // snap-distance term while the probe alternatives pay costFactor*1000 +
      // distance (the sibling start-snapper scores the incumbent with the full
      // formula). The omission gives the hostile original a stickiness bonus
      // equal to its own snap distance, which can block a relocation the
      // improvement guard below would keep. Risk of the consistent formula:
      // it loosens incumbent stickiness while relocation distance has no bound
      // relative to loop scale (rings {300,700} + maxSnapDist are absolute, so
      // a small loop can have a via pulled >1km sideways). Change this only
      // together with a loop-scale relocation bound, validated on a GREEN
      // matrix baseline (a 2026-06-11 attempt was reverted during a red
      // baseline; its regression evidence was misattributed and is unproven).
      double bestScore = origMatch != null ? origCf * 1000.0 : Double.MAX_VALUE;
      for (MatchedWaypoint m : mwps) {
        if (!isRoadSnap(m)) continue;
        // Hard loop-scale bound on the actual displacement (ring filtering
        // above is necessary but not sufficient: a ring point can snap to a
        // road far beyond the cap). The incumbent (m == origMatch) is exempt —
        // staying put is never a relocation.
        if (m != origMatch && orig.calcDistance(m.crosspoint) > relocationCap) continue;
        double costFactor = snapCandidateCostFactor(m);
        double score = costFactor * 1000.0 + orig.calcDistance(m.crosspoint);
        if (score < bestScore) {
          bestScore = score;
          best = m;
          bestCf = costFactor;
        }
      }
      // Accept the relocation only when it is a substantial improvement, not a
      // lateral move among comparably hostile roads.
      if (origMatch != null && best != origMatch && bestCf > origCf * VIA_SNAP_MIN_IMPROVEMENT) {
        best = origMatch;
      }
    }
    if (best != null) {
      best.name = name;
      // Re-anchor the waypoint on the original position so downstream radius /
      // catching-range checks measure from the planner's intended target.
      best.waypoint = orig;
      best.radius = orig.calcDistance(best.crosspoint);
    }
    return best;
  }

  /**
   * Profile-aware start snap for explicit-via round trips. The plain nearest-road snap
   * ({@link #snapWaypointToRoad}) matches the nearest <em>accessible</em> way, which for a
   * road bike can be a track right next to a paved road — starting the loop on unpaved.
   * This evaluates the original click plus a small ring of nearby positions and moves the
   * start to the most profile-compatible road (lowest cost-factor), tie-broken by proximity,
   * within {@code maxSnapDist}. The original position is always a candidate, so the start is
   * never moved unless a clearly more compatible road is genuinely nearby — and never moved
   * further than {@code maxSnapDist}.
   */
  private void snapStartProfileAware(OsmNodeNamed start, double maxSnapDist) {
    int origLon = start.ilon;
    int origLat = start.ilat;
    OsmNode orig = new OsmNode(origLon, origLat);

    List<OsmNode> points = new ArrayList<>();
    points.add(new OsmNode(origLon, origLat)); // index 0 = original = plain nearest-road snap
    double ring = Math.min(maxSnapDist, 300);
    for (double bearing = 0; bearing < 360; bearing += 45) {
      int[] p = CheapRuler.destination(origLon, origLat, ring, bearing);
      points.add(new OsmNode(p[0], p[1]));
    }

    List<MatchedWaypoint> mwps = batchMatchToRoads(points, maxSnapDist, "start_snap");
    if (mwps == null) {
      return; // leave start as-is; downstream matchWaypointsToNodes handles it
    }

    MatchedWaypoint best = null;
    double bestScore = Double.MAX_VALUE;
    double bestCostFactor = 1.0;
    for (MatchedWaypoint m : mwps) {
      if (!isRoadSnap(m)) {
        continue;
      }
      int distFromOrig = orig.calcDistance(m.crosspoint);
      if (distFromOrig > maxSnapDist) {
        continue; // keep the relocation bounded
      }
      double costFactor = snapCandidateCostFactor(m);
      // Strongly prefer a low-cost (profile-liked) road; tie-break toward the original click.
      double score = costFactor * 1000.0 + distFromOrig;
      if (score < bestScore) {
        bestScore = score;
        best = m;
        bestCostFactor = costFactor;
      }
    }

    if (best != null) {
      int moved = orig.calcDistance(best.crosspoint);
      if (moved > 0) {
        logInfo("snapStart: profile-aware start snap (" + moved + "m, costfactor "
          + String.format("%.1f", bestCostFactor) + ")");
      }
      start.ilon = best.crosspoint.getILon();
      start.ilat = best.crosspoint.getILat();
    }
  }

  /**
   * Build a single arc-following waypoint for the leg {@code a -> b}: the chord
   * midpoint pushed outward (away from the loop centroid) by {@code alpha} of the
   * chord length. Returns null for legs too short to bulge or a degenerate centroid.
   */
  private OsmNodeNamed makeArcBulge(OsmNodeNamed a, OsmNodeNamed b, int cLon, int cLat,
                                    double alpha, double searchRadius) {
    int midLon = (int) (((long) a.ilon + b.ilon) / 2);
    int midLat = (int) (((long) a.ilat + b.ilat) / 2);
    double chord = CheapRuler.distance(a.ilon, a.ilat, b.ilon, b.ilat);
    if (chord < 50) {
      return null;
    }
    if (midLon == cLon && midLat == cLat) {
      return null; // midpoint coincides with the loop centroid — no outward direction
    }
    // Outward bearing = centroid -> midpoint (latitude-scaled compass degrees, [0,360)).
    double bearing = CheapRuler.getScaledBearing(cLon, cLat, midLon, midLat);
    double offset = Math.min(alpha * chord, searchRadius);
    int[] p = CheapRuler.destination(midLon, midLat, offset, bearing);
    OsmNodeNamed bulge = new OsmNodeNamed(new OsmNode(p[0], p[1]));
    bulge.name = DENSIFIED_VIA_WAYPOINT_NAME; // display/log label only
    bulge.generated = true; // the load-bearing "this is a generated bulge" signal
    return bulge;
  }

  // --- Placement-path instrumentation (diagnostic only) -------------------
  // Monotonic process-wide counters recording which waypoint-placement path
  // each round-trip leg used. Purely additive: NO routing logic reads these.
  // They exist to measure how often the terrain-unaware ENVELOPE path is taken
  // (esp. ENVELOPE_ISO_FALLBACK, the only envelope case where an indirectness
  // compensation could be derived) so the P5 envelope-compensation work can be
  // prioritised and validated against the loop-quality corpus. AUTO runs its
  // candidates in `quite` child engines whose logInfo is suppressed, so a
  // static counter — not per-call logging — is what survives a corpus run.
  // Aggregate with placementPathCounts(); reset between corpus cases with
  // resetPlacementPathCounts().
  enum PlacementPath { ISOCHRONE, ENVELOPE_ISO_FALLBACK, ENVELOPE_FAST, CIRCLE }

  private static final java.util.concurrent.atomic.AtomicLongArray PLACEMENT_PATH_COUNTS =
    new java.util.concurrent.atomic.AtomicLongArray(PlacementPath.values().length);

  private void recordPlacementPath(PlacementPath path) {
    PLACEMENT_PATH_COUNTS.incrementAndGet(path.ordinal());
    logInfo("roundtrip placement path: " + path); // no-op for quite child engines
  }

  /** Snapshot of placement-path counts, indexed by {@link PlacementPath#ordinal()}. */
  public static long[] placementPathCounts() {
    long[] out = new long[PLACEMENT_PATH_COUNTS.length()];
    for (int i = 0; i < out.length; i++) out[i] = PLACEMENT_PATH_COUNTS.get(i);
    return out;
  }

  /** Reset the placement-path counters (for test/corpus isolation). */
  public static void resetPlacementPathCounts() {
    for (int i = 0; i < PLACEMENT_PATH_COUNTS.length(); i++) PLACEMENT_PATH_COUNTS.set(i, 0L);
  }

  private void doWaypointBasedRoundTrip(double searchRadius, double direction, RoundTripAlgorithm algo) {
    // Variety seed: bounded multi-knob perturbation for the geometric
    // placement paths (see RoutingContext.getRoundTripSeed). The phase shift stays within ±15° so the
    // direction focus is preserved; the radius stays within ±3% so the loop
    // stays inside the quality gate's distance tolerance — the gate's
    // expectedDistance is computed from the caller's UNJITTERED radius, so
    // it keeps measuring against the user's requested length. targetPoints
    // ±1 is applied below (derived values only). Seed 0/absent leaves every
    // knob at exactly 0 — bit-identical to the unseeded baseline.
    int varietySeed = routingContext.getRoundTripSeed();
    if (varietySeed > 0) {
      double phaseShiftDeg = 15.0 * GreedyRoundTripPlanner.seededUnit(varietySeed, 1, 0);
      double radiusScale = 1.0 + 0.03 * GreedyRoundTripPlanner.seededUnit(varietySeed, 2, 0);
      direction = CheapAngleMeter.normalize(direction + phaseShiftDeg);
      searchRadius *= radiusScale;
      logInfo("round trip variety seed " + varietySeed + ": phase shift " + (int) phaseShiftDeg
        + " deg, radius scale " + radiusScale);
    }
    if (routingContext.allowSamewayback) {
      int[] pos = CheapRuler.destination(waypoints.get(0).ilon, waypoints.get(0).ilat, searchRadius, direction);
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt1";
      waypoints.add(onn);
      // No-beeline invariant: snap the tip before final matchWaypointsToNodes.
      // On snap failure the tip stays at the raw geometric point and the return
      // leg can degrade to a straight-line beeline — surface it rather than
      // silently discarding the result (cf. snapWaypointsToRoad for user vias).
      if (!snapWaypointToRoad(onn, Math.min(searchRadius * 0.3, 2000), "snapSamewaybackTip")) {
        logInfo("snapSamewaybackTip: no road within snap range; samewayback return leg may include a beeline");
      }
    } else {
      // INVARIANT: this branch runs only in non-explicit-via mode, which is
      // reached only when waypoints.size() == 1 (user vias are handled earlier by
      // doExplicitViaRoundTrip). Fail fast if a future refactor ever routes user
      // vias here, rather than silently re-running the old bearing-sorted via
      // injection that doExplicitViaRoundTrip was built to replace.
      if (waypoints.size() > 1) {
        throw new IllegalStateException(
          "doWaypointBasedRoundTrip expects a single start waypoint; user vias must be "
            + "handled by doExplicitViaRoundTrip (got " + waypoints.size() + ")");
      }

      int targetPoints = routingContext.roundTripPoints == null ?
        Math.max(5, Math.min(15, (int) (searchRadius / 1500) + 3)) :
        routingContext.roundTripPoints;
      // Variety seed knob: ±1 via-point count, only when the count is derived —
      // an explicit roundTripPoints is a user decision the seed must not override.
      if (varietySeed > 0 && routingContext.roundTripPoints == null) {
        targetPoints = Math.max(4,
          targetPoints + (int) Math.round(GreedyRoundTripPlanner.seededUnit(varietySeed, 3, 0)));
      }

      if (algo == RoundTripAlgorithm.ISOCHRONE) {
        ProbeResult probe = probeReachableDirections(waypoints.get(0), searchRadius);
        double[] probeDirections = (probe != null) ? probe.viableDirections : null;
        IsochroneExpansionResult iso = runIsochroneExpansion(waypoints.get(0), searchRadius);
        double[][] frontier = (iso != null) ? iso.frontier : null;
        double[][] merged = mergeIsochroneWithProbe(frontier, probeDirections, searchRadius);
        if (merged != null && merged.length >= 3) {
          List<IsoCandidate> isoCandidates = (iso != null) ? iso.candidates : null;
          recordPlacementPath(PlacementPath.ISOCHRONE);
          placeWaypointsFromIsochrone(waypoints, merged, isoCandidates, searchRadius, direction, targetPoints);
        } else if (probeDirections != null && probeDirections.length >= 3) {
          logInfo("isochrone merge insufficient, falling back to probe directions");
          recordPlacementPath(PlacementPath.ENVELOPE_ISO_FALLBACK);
          placeWaypointsFromEnvelope(waypoints, probeDirections, searchRadius, direction, targetPoints);
        } else {
          logInfo("both isochrone and probe insufficient, falling back to circle");
          recordPlacementPath(PlacementPath.CIRCLE);
          buildPointsFromCircle(waypoints, direction, searchRadius, targetPoints);
        }
      } else {
        ProbeResult probe = probeReachableDirections(waypoints.get(0), searchRadius);
        // FAST tier: drop single-probe-success directions when enough strong
        // alternatives exist. Avoids fragile sea-edge/dead-end picks.
        double[] viableDirections = filterByProbeConfidence(probe, targetPoints);
        if (viableDirections != null && viableDirections.length >= 3) {
          recordPlacementPath(PlacementPath.ENVELOPE_FAST);
          placeWaypointsFromEnvelope(waypoints, viableDirections, searchRadius, direction, targetPoints);
        } else {
          logInfo("reachability probe returned < 3 directions, falling back to circle");
          recordPlacementPath(PlacementPath.CIRCLE);
          buildPointsFromCircle(waypoints, direction, searchRadius, targetPoints);
        }
      }

      validateAndAdjustWaypoints(waypoints, searchRadius);

      // Snap start/end waypoints to nearest road to prevent beeline segments.
      // Without this, if the user's click position is >250m from a road (park,
      // water, etc.), the routing engine inserts straight-line beelines.
      snapStartToRoad(waypoints, searchRadius);
    }

    routingContext.waypointCatchingRange = 250;
    roundTripSearchRadius = searchRadius;
    doRouting(roundTripRoutingBudgetMs);
  }

  void doGreedyRoundTrip(double searchRadius, double direction, RoundTripAlgorithm algo) {
    // Initialize nodesCache — needed before the planner can match waypoints to the graph.
    resetCache(false);
    roundTripForcedCorridorAccepted = false;
    // Loop scale for the via-relocation bound (profileAwareMatchPoint): must be
    // set BEFORE planner via matching — the doRouting fallthrough below used to
    // set it only late, leaving the bound inert during greedy placement.
    roundTripSearchRadius = searchRadius;

    OsmNodeNamed start = waypoints.get(0);
    double desiredDistance = 2 * Math.PI * searchRadius;
    logInfo("greedy round trip: desired distance=" + (int) desiredDistance
      + "m, searchRadius=" + (int) searchRadius + "m, direction=" + (int) direction
      + ", mode=" + algo);

    // Phase 2.0: when ISO_GREEDY runs without an explicit user direction,
    // use the isochrone's reachability asymmetry to bias the initial bearing
    // toward the most-reaching sector. The legacy default of "direction=-1"
    // (ANY) means candidate scoring's direction term is inert at step 1,
    // and the candidate placement uses an unrelated heuristic. On terrain-
    // asymmetric networks (coast, valley, island) this can place initial
    // candidates in geographically unreachable regions. The asymmetry bias
    // grounds the initial direction in actual graph reachability.
    //
    // Bias applies ONLY when:
    //   - algo == ISO_GREEDY (we need the frontier table)
    //   - direction < 0 (user did not specify a direction)
    //   - at least one bucket meets quality thresholds (airDist >= 0.6 *
    //     searchRadius AND hits >= 3)
    // Otherwise direction is preserved verbatim.
    // The desirability heatmap (issue #15) piggybacks on the isochrone expansion,
    // so GREEDY also runs the expansion when an explicit experimental flag needs
    // the grid. Accumulation is scoped to this case so default GREEDY and
    // ISO_GREEDY do not pay to build a grid they never consume.
    boolean buildDesirabilityGrid = (routingContext.roundTripDesirability || routingContext.roundTripCapsule
        || routingContext.roundTripSteerVias)
        && algo == RoundTripAlgorithm.GREEDY;
    accumulatingDesirabilityGrid = buildDesirabilityGrid;
    IsochroneExpansionResult iso = (algo == RoundTripAlgorithm.ISO_GREEDY || buildDesirabilityGrid)
      ? runIsochroneExpansion(start, searchRadius)
      : null;
    accumulatingDesirabilityGrid = false;
    // Faithful capsule (leg-masking): turn the density grid into soft no-go polygons over
    // dense interiors so routed LEGS avoid the spaghetti, crossing only at least-cost gaps
    // (implicit portals/corridors). Off unless roundTripCapsule AND loop.capsule.nogoweight>0.
    double capsuleNogoWeight = 0;
    if (routingContext.roundTripCapsule && capsuleNogoWeight > 0 && !desirabilityGrid.isEmpty()) {
      List<OsmNodeNamed> capsuleNogos = CapsuleNogoBuilder.build(
        desirabilityGrid, DESIRABILITY_CELL, capsuleNogoWeight,
        0.80,
        10,
        4);
      if (!capsuleNogos.isEmpty()) {
        if (routingContext.nogopoints == null) routingContext.nogopoints = new ArrayList<>();
        routingContext.nogopoints.addAll(capsuleNogos);
        logInfo("GREEDY: capsule leg-masking — " + capsuleNogos.size()
          + " soft no-go polygons (weight " + capsuleNogoWeight + ")");
      }
    }
    // Via-steering: derive a dense-area map (town/city cores) from the same grid; the round-trip
    // planner penalises candidate vias placed inside it, so the loop keeps its turnarounds out of
    // built-up cores. Opt-in (roundTripSteerVias); never consulted by the general per-segment engine.
    if (routingContext.roundTripSteerVias && !desirabilityGrid.isEmpty()) {
      routingContext.denseAreaMap = DenseAreaMap.fromDesirabilityGrid(desirabilityGrid, DESIRABILITY_CELL,
        0.88,
        12,
        2,
        20,
        5);
      if (routingContext.denseAreaMap != null) {
        logInfo("GREEDY: via-steering — " + routingContext.denseAreaMap.size() + " dense-area boxes");
      }
    }
    double effectiveDirection = direction;
    IsoAsymmetryBias bias = IsoAsymmetryBias.NONE;
    if (algo == RoundTripAlgorithm.ISO_GREEDY && direction < 0 && iso != null) {
      bias = computeIsoAsymmetryBearing(iso.frontier, searchRadius);
      if (bias.applied) {
        effectiveDirection = bias.bearingDegrees;
        logInfo("ISO_GREEDY: iso-asymmetry bias selected bearing="
          + (int) bias.bearingDegrees + "° (indirectness=" + String.format("%.2f", bias.indirectness)
          + ", hits=" + bias.hits + ", airDist=" + bias.airDistMeters + "m)");
      }
    }
    RoundTripCandidateProvider provider = buildCandidateProvider(algo, start, searchRadius, effectiveDirection, iso);
    int baseSubRouteCount = selectGreedySubRouteCount(desiredDistance, routingContext.getProfileName());

    // First attempt — user direction (or Phase 2.0 biased bearing).
    RoundTripResult result = runGreedyAttempt(start, searchRadius, desiredDistance,
      effectiveDirection, baseSubRouteCount, provider, bias);

    // Phase 2.1: if the first attempt degraded AND the user supplied an
    // explicit direction AND the frontier has a strong terrain axis AND
    // the user's direction is perpendicular to that axis, retry once
    // along the axis. This addresses the Inn-Valley pattern: 100km loop
    // requested heading N where the road network only supports E-W.
    // Scoped to ISO_GREEDY: the desirability flag (issue #15) also makes GREEDY
    // populate `iso`, but Phase 2.1 axis-retry is an ISO_GREEDY behaviour and must
    // not change GREEDY's algorithm identity. (No-op for prior behaviour — `iso`
    // was only ever non-null for ISO_GREEDY before the flag existed.)
    FrontierAxis frontierAxis = (algo == RoundTripAlgorithm.ISO_GREEDY && iso != null)
      ? computeFrontierAxis(iso.frontier, searchRadius) : FrontierAxis.NONE;
    boolean phase21Triggered = false;
    boolean phase21Succeeded = false;
    double phase21RetryDir = Double.NaN;
    if (isDegradedGreedyResult(result)
        && direction >= 0
        && frontierAxis.hasStrongAxis
        && isPerpendicularToAxis(direction, frontierAxis.axisBearingDegrees)) {
      phase21Triggered = true;
      phase21RetryDir = chooseAxisBearing(frontierAxis.axisBearingDegrees, direction);
      logInfo("ISO_GREEDY: Phase 2.1 axis retry — user direction " + (int) direction
        + "° is perpendicular to terrain axis " + String.format("%.0f", frontierAxis.axisBearingDegrees)
        + "° (strength=" + String.format("%.1fx", frontierAxis.strength)
        + "); retrying with axis-aligned direction " + (int) phase21RetryDir + "°");
      RoundTripResult retry = runGreedyAttempt(start, searchRadius, desiredDistance,
        phase21RetryDir, baseSubRouteCount, provider, bias);
      if (!isDegradedGreedyResult(retry)
          && retry != null && retry.getLoopWaypoints() != null
          && retry.getLoopWaypoints().size() >= 4) {
        phase21Succeeded = true;
        result = retry;
      } else {
        // Retry also degraded → geographic infeasibility. Keep first-attempt
        // result for diagnostic display but mark the infeasibility for the
        // caller's error path below.
        logInfo("ISO_GREEDY: Phase 2.1 axis retry ALSO degraded — geographic infeasibility detected");
      }
    }

    if (result != null) {
      result.setPhase21AxisRetryTriggered(phase21Triggered);
      result.setPhase21AxisRetrySucceeded(phase21Succeeded);
      result.setPhase21AxisBearingDegrees(frontierAxis.hasStrongAxis
        ? frontierAxis.axisBearingDegrees : Double.NaN);
      result.setPhase21AxisStrength(frontierAxis.hasStrongAxis ? frontierAxis.strength : 0.0);
      result.setPhase21RetryDirectionDegrees(phase21RetryDir);
    }

    // Phase 2.1 used to also set errorMessage when both attempts degraded
    // (the spec's "refuse with infeasibility error" option). That cut off
    // doRoundTrip's later fallback path (waypoint algorithm), losing 2
    // iso_greedy/gravel scenarios on the broader corpus that the legacy
    // waypoint fallback had been salvaging. Drop the errorMessage write;
    // let the result return as degraded so the caller can fall back as
    // before. The axis info is still surfaced via the Phase 2.1 telemetry
    // fields on RoundTripResult for diagnostic purposes.
    if (phase21Triggered && !phase21Succeeded) {
      logInfo("ISO_GREEDY: Phase 2.1 axis retry also degraded — geographic"
        + " infeasibility (axis " + axisName(frontierAxis.axisBearingDegrees)
        + ", strength " + String.format("%.1fx", frontierAxis.strength)
        + "); falling through to legacy fallback chain");
    }

    // A real loop needs at least a triangle: start + 2 intermediate waypoints + closing
    // start (>= 4 entries). A single intermediate is just an out-and-back, so reject it
    // rather than attributing a legacy waypoint/probe fallback route to GREEDY.
    // Reject loops the planner explicitly flagged as failing its quality gates
    // (DEGRADED_FALLBACK_PREFIX) — shipping a 180% overshoot or 60%-reused
    // forced-closure loop as success would silently fool downstream consumers.
    roundTripForcedCorridorAccepted = result != null && result.isForcedCorridorAccepted();
    boolean degradedFallback = isDegradedGreedyResult(result);
    if (degradedFallback) {
      logInfo("greedy: rejecting degraded fallback (" + result.getFallbackReason()
        + ")");
    }
    if (!degradedFallback
        && result != null && result.getLoopWaypoints() != null
        && result.getLoopWaypoints().size() >= 4) {
      for (String diag : result.getDiagnostics()) {
        logInfo("greedy: " + diag);
      }
      // Spec §10 telemetry — compute-budget audit.
      logInfo("greedy telemetry: candidatesGenerated=" + result.getCandidatesGenerated()
        + ", candidatesRouted=" + result.getCandidatesRouted()
        + ", returnChecks=" + result.getReturnChecksPerformed()
        + ", runtimeMs=" + result.getRuntimeMillis()
        + ", fallbackReason=" + (result.getFallbackReason() == null ? "none" : result.getFallbackReason()));
      if (!result.isWithinTolerance()) {
        logInfo("greedy: fallback — " + result.getFallbackReason());
      }
      logInfo("greedy: planned " + result.getLoopWaypoints().size() + " waypoints"
        + ", estimated distance=" + result.getTotalDistanceMeters() + "m");

      // Route through the greedy waypoints with the standard routing engine.
      // The greedy planner's lookahead ensures waypoints are in well-connected
      // areas (not dead-end valleys), so doRouting() produces gap-free tracks
      // following roads appropriate for the profile.
      waypoints.clear();
      waypoints.addAll(result.getLoopWaypoints());

      if (result.getMatchedWaypoints() != null) {
        matchedWaypoints = result.getMatchedWaypoints();
      }

      if (result.getLegTracks() != null) {
        List<OsmTrack> legs = result.getLegTracks();
        greedyLegTracks = legs.toArray(new OsmTrack[0]);
      }

      // Phase 2 v3: the planner now retracks each committed leg, so its
      // merged track has full per-edge MessageData. Use that directly
      // instead of running doRouting() which re-routes via a fragile
      // corridor mechanism that frequently fails or diverges. The
      // re-routing was wiping out the planner's hostility-aware
      // candidate choices, so the quality gate was seeing routes the
      // planner itself would have rejected. Diagnostic data: roughly
      // 80% of greedy legs in failing fastbike scenarios had the
      // corridor fail or diverge.
      boolean useDetailedPlannerTrack = result != null && result.getTrack() != null
        && result.getTrack().nodes != null && result.getTrack().nodes.size() >= MIN_ROUNDTRIP_LOOP_NODES;
      if (useDetailedPlannerTrack) {
        try {
          foundTrack = result.getTrack();
          if (result.getMatchedWaypoints() != null) {
            matchedWaypoints = result.getMatchedWaypoints();
          }
          finalizeAdoptedRoundTripTrack(foundTrack, matchedWaypoints);
        } catch (Exception e) {
          logInfo("greedy: bypass path failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), falling back to doRouting");
          useDetailedPlannerTrack = false;
        }
      }
      if (!useDetailedPlannerTrack) {
        routingContext.waypointCatchingRange = 250;
        roundTripSearchRadius = searchRadius;
        try {
          doRouting(roundTripRoutingBudgetMs);
        } catch (Exception e) {
          logInfo("greedy: doRouting failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
          throw e;
        } finally {
          greedyLegTracks = null;
        }
      }
    } else {
      // ISO_GREEDY only fails over to plain GREEDY if it also failed; otherwise
      // ISO_GREEDY's planner already added graph-native per-step candidates
      // when the start-centered iso pool was insufficient (see buildCandidateProvider).
      if (algo == RoundTripAlgorithm.ISO_GREEDY) {
        logInfo("ISO_GREEDY produced no loop, falling back to GREEDY with graph-native candidates");
        doGreedyRoundTrip(searchRadius, direction, RoundTripAlgorithm.GREEDY);
      } else {
        // Adopt the planner's best-effort loop (if any) and hand it up to the
        // uniform quality gate in doRoundTrip, which is the single place that
        // decides hard-reject (STRUCTURAL, or any failure under strict mode) vs.
        // a lenient advisory. This keeps greedy consistent with the other
        // algorithms and removes a duplicate, tier-blind leniency decision: the
        // gate (plus the node/distance floor just above it) inspects the verdict
        // rather than re-deriving "usable" from node counts here.
        OsmTrack bestEffort = result == null ? null : result.getTrack();
        if (bestEffort != null && bestEffort.nodes != null && !bestEffort.nodes.isEmpty()) {
          logInfo("greedy: adopting best-effort loop for the quality gate to grade ("
            + (result.getFallbackReason() == null ? "?" : result.getFallbackReason()) + ")");
          foundTrack = bestEffort;
          if (result.getMatchedWaypoints() != null) {
            matchedWaypoints = result.getMatchedWaypoints();
          }
          // finalize can throw (voice hints / speed profile / spur removal). Guard
          // it like the bypass path above: an exception here would otherwise
          // unwind past doRoundTrip's floor + quality gate (its catch does not
          // null foundTrack), shipping this un-gated best-effort track as a
          // success. On failure, reject instead so nothing skips the gate.
          try {
            finalizeAdoptedRoundTripTrack(foundTrack, matchedWaypoints);
            // errorMessage stays null: the floor check + quality gate in
            // doRoundTrip reject (and set errorMessage) if the loop is too small,
            // structurally broken, or strict mode is on; else it ships with a warning.
          } catch (Exception e) {
            errorMessage = "greedy best-effort finalize failed ("
              + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            logInfo(errorMessage);
            lastRejectedTrack = bestEffort;
            foundTrack = null;
          }
        } else {
          errorMessage = "greedy round trip planner produced no acceptable graph-native loop"
            + (result == null || result.getFallbackReason() == null ? "" : ": " + result.getFallbackReason());
          logInfo(errorMessage);
          lastRejectedTrack = result == null ? null : result.getTrack();
          foundTrack = null;
        }
      }
    }
  }

  static int selectGreedySubRouteCount(double desiredDistance, String profileName) {
    int n;
    if (desiredDistance < 8000) {
      n = 3;
    } else if (desiredDistance < 30000) {
      n = 4;
    } else if (desiredDistance < 80000) {
      n = 5;
    } else {
      n = 6;
    }
    if (profileName != null && profileName.toLowerCase(Locale.US).contains("mtb")) {
      n++;
    }
    return Math.max(3, Math.min(6, n));
  }

  static int[] greedySubRouteCountPlan(int base) {
    int clamped = Math.max(3, Math.min(6, base));
    List<Integer> counts = new ArrayList<>(6);
    addUniqueCount(counts, clamped);
    addUniqueCount(counts, clamped + 1);
    addUniqueCount(counts, clamped - 1);
    addUniqueCount(counts, clamped - 2);
    addUniqueCount(counts, clamped + 2);
    addUniqueCount(counts, clamped - 3);
    int[] result = new int[counts.size()];
    for (int i = 0; i < counts.size(); i++) result[i] = counts.get(i);
    return result;
  }

  private static void addUniqueCount(List<Integer> counts, int n) {
    if (n < 3 || n > 6 || counts.contains(n)) return;
    counts.add(n);
  }

  private static boolean isDegradedGreedyResult(RoundTripResult result) {
    return result != null
      && result.getFallbackReason() != null
      && result.getFallbackReason().startsWith(GreedyRoundTripPlanner.DEGRADED_FALLBACK_PREFIX);
  }

  /**
   * Build the appropriate candidate provider for the chosen mode. GREEDY uses
   * per-step graph-native candidates. ISO_GREEDY blends a bounded start-centered
   * isochrone pool with that same per-step graph-native provider. Geometric
   * radial placement is intentionally not used by production greedy paths.
   */
  private RoundTripCandidateProvider buildCandidateProvider(RoundTripAlgorithm algo,
                                                            OsmNodeNamed start,
                                                            double searchRadius,
                                                            double startDirection,
                                                            IsochroneExpansionResult iso) {
    GraphNativeCandidateProvider graphNative = new GraphNativeCandidateProvider(this);
    if (algo != RoundTripAlgorithm.ISO_GREEDY) {
      // Capsule wins if both experimental flags are set: it steers waypoints out
      // of dense interiors AND rewards higher ground, a superset of the use case.
      if (routingContext.roundTripCapsule && !desirabilityGrid.isEmpty()) {
        CapsuleCandidateProvider capsule =
          new CapsuleCandidateProvider(desirabilityGrid, graphNative, DESIRABILITY_CELL);
        logInfo("GREEDY: capsule-guided candidate provider (" + desirabilityGrid.size()
          + " cells, " + capsule.denseCellCount() + " dense, " + capsule.boundaryCellCount()
          + " portal, elevation=" + (capsule.elevationActive() ? "on" : "off") + ")");
        return capsule;
      }
      if (routingContext.roundTripDesirability && !desirabilityGrid.isEmpty()) {
        logInfo("GREEDY: desirability-guided candidate provider (" + desirabilityGrid.size() + " cells)");
        return new DesirabilityCandidateProvider(desirabilityGrid, graphNative,
          DESIRABILITY_CELL, DESIRABILITY_TOP_K);
      }
      return graphNative;
    }
    if (iso == null || iso.frontier.length < 6 || iso.candidates.size() < 12) {
      logInfo("ISO_GREEDY: insufficient isochrone data ("
        + (iso == null ? 0 : iso.frontier.length) + " buckets, "
        + (iso == null ? 0 : iso.candidates.size()) + " raw candidates), using graph-native candidates");
      return graphNative;
    }
    IsochroneCandidateProvider isoProvider =
      IsochroneCandidateProvider.fromPool(searchRadius, startDirection, iso.candidates);
    if (isoProvider.poolSize() < 6) {
      logInfo("ISO_GREEDY: candidate pool too small after filtering ("
        + isoProvider.poolSize() + "), using graph-native candidates");
      return graphNative;
    }
    if (!isoProvider.isDiverse()) {
      logInfo("ISO_GREEDY: candidate pool concentrated in a narrow corridor ("
        + isoProvider.poolSize() + " candidates), using graph-native candidates");
      return graphNative;
    }
    // ISO_GREEDY: blend start-centered iso depth with per-step graph-native
    // candidates. Both sources are road-native; neither invents coordinates.
    logInfo("ISO_GREEDY: blended isochrone+graph-native provider (iso pool="
      + isoProvider.poolSize() + ")");
    return new BlendedCandidateProvider(isoProvider, graphNative);
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
      // crosspoint is nullable (MatchedWaypoint.crosspoint); a not-yet-matched
      // rt waypoint would NPE here. Skip the too-close test rather than crash.
      if (curr.crosspoint == null || prev.crosspoint == null) continue;

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
   * Snap the start (and closing) waypoint to the nearest road.
   * Round-trip waypoints use the user's raw click position which may be
   * far from any road. If the distance exceeds waypointCatchingRange (250m),
   * the routing engine inserts a straight-line beeline instead of a road segment.
   * This method matches the start to the road network and updates its position.
   */
  void snapStartToRoad(List<OsmNodeNamed> waypoints, double searchRadius) {
    if (waypoints.size() < 2) return;
    OsmNodeNamed start = waypoints.get(0);
    if (snapWaypointToRoad(start, Math.min(searchRadius * 0.3, 2000), "snapStartToRoad")) {
      // Also snap the closing waypoint (last in list) which mirrors the start
      OsmNodeNamed closing = waypoints.get(waypoints.size() - 1);
      if ("to".equals(closing.name)) {
        closing.ilon = start.ilon;
        closing.ilat = start.ilat;
      }
    }
  }

  /**
   * Snap a single waypoint to the nearest road within {@code maxSnapDist}.
   * Returns true and rewrites the waypoint coordinates to the matched
   * crosspoint when a match is found; returns false otherwise (waypoint left
   * untouched). Used by round-trip code paths to ensure generated points are
   * close enough to a road that final matchWaypointsToNodes (250m catching
   * range) does not fall back to dynamic beeline insertion.
   */
  boolean snapWaypointToRoad(OsmNodeNamed wp, double maxSnapDist, String logTag) {
    return snapWaypointsToRoad(Collections.singletonList(wp), maxSnapDist, logTag).get(0);
  }

  /**
   * Batch variant of {@link #snapWaypointToRoad}. One nodesCache reset and one
   * matchWaypointsToNodes call for the whole list — avoids reallocating the
   * cache per waypoint when many points need snapping (e.g. user via points).
   * Returns a parallel list of booleans indicating which waypoints matched.
   */
  List<Boolean> snapWaypointsToRoad(List<OsmNodeNamed> wps, double maxSnapDist, String logTag) {
    resetCache(false);
    List<MatchedWaypoint> mwpList = new ArrayList<>(wps.size());
    for (OsmNodeNamed wp : wps) {
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(wp.ilon, wp.ilat);
      mwp.name = (wp.name == null ? "wp" : wp.name) + "_snap";
      mwpList.add(mwp);
    }
    try {
      nodesCache.matchWaypointsToNodes(mwpList, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      logInfo(logTag + ": match failed, leaving " + wps.size() + " waypoint(s) unsnapped: " + e.getMessage());
      List<Boolean> all = new ArrayList<>(wps.size());
      for (int i = 0; i < wps.size(); i++) all.add(false);
      return all;
    }
    List<Boolean> matched = new ArrayList<>(wps.size());
    for (int i = 0; i < wps.size(); i++) {
      OsmNodeNamed wp = wps.get(i);
      MatchedWaypoint mwp = mwpList.get(i);
      if (mwp.crosspoint == null) {
        matched.add(false);
        continue;
      }
      int snapDist = wp.calcDistance(mwp.crosspoint);
      if (snapDist > 0) {
        logInfo(logTag + ": moved " + (wp.name == null ? "wp" : wp.name) + " "
          + snapDist + "m to nearest road");
        wp.ilon = mwp.crosspoint.getILon();
        wp.ilat = mwp.crosspoint.getILat();
      }
      matched.add(true);
    }
    return matched;
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
      double bearing = CheapRuler.getScaledBearing(start.ilon, start.ilat, wp.ilon, wp.ilat);
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

    // Match all candidates at once — profile-aware via segment decoding.
    // Guard like the sibling callers (batchMatchToRoads, snapWaypointsToRoad): a
    // cache/segment-decode failure must not abort the whole round trip. On error,
    // leave the waypoints at their generated positions and let the downstream
    // final matchWaypointsToNodes handle them, rather than throwing out of here
    // into doRoundTrip's catch and failing the request outright.
    try {
      nodesCache.matchWaypointsToNodes(allCandidates, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      logInfo("validateAndAdjustWaypoints: candidate match failed ("
        + e.getClass().getSimpleName() + "), keeping generated waypoint positions");
      return;
    }

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
      double bestCostFactor = 1.0;
      double snapRejectThreshold = snapRejectCostFactorForProfile();
      for (MatchedWaypoint mwp : group) {
        if (mwp.crosspoint == null) continue;

        // node1/node2 are dereferenced just below for the road bearing; skip any
        // match missing either endpoint rather than NPE.
        if (mwp.node1 == null || mwp.node2 == null) continue;

        // Skip matches to ferry-like segments (very long edges between node1/node2).
        // Ferry routes have sparse nodes spanning several km over water; road edges are < 1km.
        if (mwp.node1.calcDistance(mwp.node2) > FERRY_LIKE_EDGE_METERS) {
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

        // Profile-aware penalty: prefer roads the active profile actually likes.
        // Without this, fastbike happily snaps to a 50m forest track when there's
        // a 200m paved road just past it — and then the routing engine is forced
        // through the track because the waypoint is committed.
        double costFactor = snapCandidateCostFactor(mwp);
        score *= (1.0 + SNAP_PROFILE_COST_WEIGHT * (costFactor - 1.0));

        if (score < bestScore) {
          best = mwp;
          bestScore = score;
          bestCostFactor = costFactor;
        }
      }
      // Reject the snap entirely if the BEST road we could find is too profile-
      // hostile (e.g. fastbike forced onto a grade-5 track). Better to drop this
      // waypoint and route around than ship the user onto a road their profile
      // would have actively avoided — that's the surprise-mid-tour pain.
      if (best != null && bestCostFactor > snapRejectThreshold) {
        logInfo("validateWaypoints: rejecting profile-hostile snap for "
          + waypoints.get(i).name + " (costfactor=" + String.format("%.1f", bestCostFactor)
          + " > " + snapRejectThreshold + " for " + profileNameForLog() + ")");
        best = null;
      }

      OsmNodeNamed wp = waypoints.get(i);
      if (best != null && best.radius <= maxSnapDist) {
        // Use crosspoint, not waypoint: keeps the point within the 250m
        // catching range used by final matchWaypointsToNodes (avoids beeline).
        if (wp.ilon != best.crosspoint.getILon() || wp.ilat != best.crosspoint.getILat()) {
          logInfo("validateWaypoints: relocated " + wp.name + " snap=" + (int) best.radius + "m");
          wp.ilon = best.crosspoint.getILon();
          wp.ilat = best.crosspoint.getILat();
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
   * Per-profile snap-rejection threshold from the {@code SNAP_REJECT_COSTFACTOR_*}
   * constants. Resolves the active profile by filename so a fastbike user
   * doesn't end up snapped to a grade-5 track even when the engine has run a
   * different profile previously on the same JVM.
   */
  private double snapRejectCostFactorForProfile() {
    String name = profileNameForLog();
    if (name == null) return SNAP_REJECT_COSTFACTOR_DEFAULT;
    String lower = name.toLowerCase();
    if (lower.contains("fastbike")) return SNAP_REJECT_COSTFACTOR_FASTBIKE;
    if (lower.contains("gravel")) return SNAP_REJECT_COSTFACTOR_GRAVEL;
    return SNAP_REJECT_COSTFACTOR_DEFAULT;
  }

  /** Filename of the active profile (lower-cased, no extension) — for logging + threshold lookup. */
  private String profileNameForLog() {
    String path = routingContext == null ? null : routingContext.localFunction;
    if (path == null) return null;
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    int dot = path.lastIndexOf('.');
    int start = slash + 1;
    int end = dot > start ? dot : path.length();
    return path.substring(start, end);
  }

  /**
   * Evaluate the active profile's costfactor for the road segment a candidate
   * waypoint matched against (used by round-trip via-snapping to prefer
   * profile-liked roads). Returns {@code 1.0f} on any failure to resolve the
   * tags — that's the neutral value (preserves the geometric-only score).
   *
   * <p>The score comes straight from {@code mwp.wayDescription}: the waypoint
   * matcher records the matched way's tag bytes at match time (the trailing
   * way-description arg of {@code WaypointMatcher.start}). This method, reached
   * only on the round-trip via-snap path, evaluates those tags through the
   * profile's way context. {@code wayDescription} is null only when the match
   * carried no way tags, in which case the neutral {@code 1.0f} is returned.
   */
  private float snapCandidateCostFactor(MatchedWaypoint mwp) {
    // Evaluate the tag description the waypoint matcher captured from the
    // matched way. The previous implementation walked node1's links to find
    // the way — but the matcher's node1/node2 are coordinate-only copies that
    // are never hollow and carry no links, so that walk silently returned the
    // 1.0 fallback for every match and all "profile-aware" snap scoring was
    // inert. The matcher now records the way description at match time.
    if (mwp == null || mwp.wayDescription == null) return 1.0f;
    try {
      routingContext.expctxWay.evaluate(false, mwp.wayDescription);
      return routingContext.expctxWay.getCostfactor();
    } catch (RuntimeException ignored) {
      return 1.0f;
    }
  }

  /**
   * Remove back-and-forth segments from a round-trip track.
   * At each waypoint boundary, the route may retrace the same road
   * it arrived on before diverging. This method detects such overlaps
   * by walking outward from each waypoint in both directions and
   * comparing node positions. The full mirrored spur is removed and the
   * generated waypoint metadata is moved back to the surviving branch node.
   */
  void removeBackAndForthSegments(OsmTrack track, List<MatchedWaypoint> waypoints) {
    removeBackAndForthSegments(track, waypoints, false);
  }

  /**
   * As {@link #removeBackAndForthSegments(OsmTrack, List)}, but when
   * {@code onlyGenerated} is true only engine-generated waypoints
   * ({@link MatchedWaypoint#generated}) are cleaned. Used by densified
   * explicit-via routes to strip the out-and-back spurs at generated "dvia"
   * bulge points while leaving user-supplied vias (and their exact
   * pass-through) untouched.
   */
  void removeBackAndForthSegments(OsmTrack track, List<MatchedWaypoint> waypoints, boolean onlyGenerated) {
    List<OsmPathElement> nodes = track.nodes;

    for (int wi = 1; wi < waypoints.size() - 1; wi++) {
      if (onlyGenerated && !waypoints.get(wi).generated) {
        continue;
      }
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
        int removeFrom = wptIdx - overlapCount + 1;
        int removeTo = wptIdx + overlapCount + 1;
        int removeCount = removeTo - removeFrom;
        int branchIdx = removeFrom - 1;

        logInfo("removeBackAndForth: at waypoint " + waypoints.get(wi).name
          + " removing " + removeCount + " spur nodes");

        nodes.subList(removeFrom, removeTo).clear();

        // The generated waypoint was on the removed spur. Keep the waypoint
        // metadata attached to the branch so later waypoint-relative cleanup
        // and export indices stay inside the surviving track.
        waypoints.get(wi).indexInTrack = branchIdx;
        for (int wj = wi + 1; wj < waypoints.size(); wj++) {
          waypoints.get(wj).indexInTrack -= removeCount;
        }
      }
    }
  }

  /**
   * Via-pinned teardrop band: a detour pinned at a generated via (the planner
   * placed a waypoint in a one-way-out pocket; the anti-reuse penalty then
   * shapes the escape into a thin offset loop instead of an exact retrace the
   * symmetric spur remover could strip) may be removed up to this arc length —
   * well beyond the plain micro-detour cap.
   */
  static final int VIA_TEARDROP_MAX_ARC_M = 4000;
  /** ... bounded relative to the whole track, so short loops never lose a large share. */
  static final double VIA_TEARDROP_MAX_ARC_FRAC = 0.15;
  /**
   * Petal-compactness ceiling for the via-pinned band: spans fatter than this
   * (shoelace area vs same-perimeter circle) enclose real area — a scenic petal
   * the rider may want — and are kept. Thin excursions (deep, narrow, returning
   * to the pinch point) are routing artifacts and removed. A pure out-and-back
   * scores ~0; a 10:1 deep-narrow teardrop ~0.3; a round petal 0.7+.
   */
  static final double VIA_TEARDROP_MAX_COMPACTNESS = 0.30;

  /**
   * Remove micro-detours: small loops where the route returns to the same
   * area within a short distance. Uses proximity matching (not just exact
   * node identity) to catch detours through parallel roads or dual
   * carriageways where the route returns to a nearby but distinct node.
   *
   * <p>Spans pinned at a generated round-trip via get an extended cap
   * ({@link #VIA_TEARDROP_MAX_ARC_M}, fraction-bounded) but must additionally
   * be thin ({@link #petalCompactness} ≤ {@link #VIA_TEARDROP_MAX_COMPACTNESS})
   * so genuine scenic petals survive; below {@code maxLoopDistance} behaviour
   * is unchanged.
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
      long totalDist = 0;
      for (int k = 1; k < nodes.size(); k++) {
        totalDist += nodes.get(k - 1).calcDistance(nodes.get(k));
      }
      int viaTeardropCap = (int) Math.min(VIA_TEARDROP_MAX_ARC_M,
        VIA_TEARDROP_MAX_ARC_FRAC * totalDist);
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
          boolean nearVia = isNearGeneratedWaypoint(nodes, matchIdx, i, waypoints, waypointProximity);
          double ratioThreshold = nearVia ? 2.0 : 3.0;

          // Via-pinned teardrop band: spans pinned at a generated via may exceed
          // the plain cap, but only THIN spans are removed there — a fat span
          // encloses real area (scenic petal) and stays.
          int effectiveCap = (nearVia && viaTeardropCap > maxLoopDistance)
            ? viaTeardropCap : maxLoopDistance;
          boolean removable = loopDist > 0 && loopDist <= effectiveCap;
          if (removable && loopDist > maxLoopDistance
              && petalCompactness(nodes, matchIdx, i, loopDist) > VIA_TEARDROP_MAX_COMPACTNESS) {
            removable = false;
          }

          // A genuine detour has route distance much larger than crow-fly distance
          // (the route went elsewhere and came back). Normal forward progression
          // has route distance ≈ crow-fly distance.
          if (removable && loopDist > crowFly * ratioThreshold) {
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
   * Geometric trigger for a via-pinned bulge: the route arc across the span
   * must be at least this multiple of the mouth's crow-fly distance. The Basel
   * reference artifact (1.85km of grade2 track for 410m of progress) measures
   * 4.5; 3.0 keeps margin against firing on ordinary loop curvature near a via.
   */
  static final double BULGE_MIN_PROGRESS_RATIO = 3.0;
  /** Spans shorter than this are left to removeMicroDetours' teardrop band. */
  static final int BULGE_MIN_ARC_M = 600;
  /**
   * Junk filter: the span's average cost/m must be at least this multiple of
   * the whole track's average. A deliberate scenic petal rides roads the
   * profile likes (span cost/m ≈ track average) and is kept; only overpriced
   * detours — the profile got dragged onto roads it penalizes — qualify.
   * Deliberately loose (the Basel reference span measures 1.38× because
   * {@link #spanCostPerMeter} skips the leg-reset edge); the connector
   * cost-advantage check below is the decisive guard.
   */
  static final double BULGE_MIN_COST_FACTOR = 1.2;
  /**
   * Repair acceptance: the connector must be at most this fraction of the
   * bulge arc. This is an efficiency criterion, not a straightness one — a
   * winding connector on profile-friendly roads still beats a junk-road bulge
   * (Basel via3: 839m connector at 1.44 cost/m vs 3231m span at 3.92). When
   * the shortest connector is comparable to the bulge itself, the bulge was
   * network-forced, not via-pinned — keep it.
   */
  static final double BULGE_CONNECTOR_MAX_ARC_FRACTION = 0.6;
  /** Repair acceptance: minimum arc length the splice must save. */
  static final int BULGE_MIN_SAVED_M = 400;
  /** Repair acceptance: connector cost/m × this must still undercut the span's
   *  cost/m, so the repair is a genuine quality win, not a lateral move. */
  static final double BULGE_CONNECTOR_COST_ADVANTAGE = 1.3;

  /**
   * Find a low-progress span pinned at a generated via: the pair (i, j) with
   * i &lt; viaIdx &lt; j maximizing {@code arc - BULGE_MIN_PROGRESS_RATIO * crowFly}
   * (the "excess length" of the detour), subject to {@code BULGE_MIN_ARC_M ≤ arc
   * ≤ maxArcM}. Unlike removeMicroDetours this needs no ≤50m pinch point — it
   * catches wide-mouth bulges whose outbound and return legs never come close
   * (e.g. parallel field lanes 400m apart). Returns {@code {i, j}} or null.
   */
  static int[] findViaPinnedBulgeSpan(List<OsmPathElement> nodes, int viaIdx,
                                      int loIdx, int hiIdx, int maxArcM) {
    if (nodes == null || loIdx < 0 || hiIdx >= nodes.size()
        || viaIdx <= loIdx || viaIdx >= hiIdx) {
      return null;
    }
    double[] cum = new double[hiIdx - loIdx + 1];
    for (int k = loIdx + 1; k <= hiIdx; k++) {
      cum[k - loIdx] = cum[k - loIdx - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    double bestBenefit = 0;
    int bi = -1;
    int bj = -1;
    for (int i = viaIdx - 1; i >= loIdx; i--) {
      if (cum[viaIdx - loIdx] - cum[i - loIdx] > maxArcM) break;
      for (int j = viaIdx + 1; j <= hiIdx; j++) {
        double arc = cum[j - loIdx] - cum[i - loIdx];
        if (arc > maxArcM) break;
        if (arc < BULGE_MIN_ARC_M) continue;
        double crowFly = CheapRuler.distance(
          nodes.get(i).getILon(), nodes.get(i).getILat(),
          nodes.get(j).getILon(), nodes.get(j).getILat());
        double benefit = arc - BULGE_MIN_PROGRESS_RATIO * crowFly;
        if (benefit > bestBenefit) {
          bestBenefit = benefit;
          bi = i;
          bj = j;
        }
      }
    }
    return bi < 0 ? null : new int[]{bi, bj};
  }

  /**
   * Average cost per meter over a node span, summing per-edge cost deltas.
   * Node costs are cumulative per routed leg and reset to 0 at leg joins in
   * planner-merged tracks; a negative delta marks such a reset and that edge's
   * cost is skipped (slightly underestimates the span — conservative for the
   * junk filter, which requires the span to be expensive).
   */
  static double spanCostPerMeter(List<OsmPathElement> nodes, int fromIdx, int toIdx) {
    double cost = 0;
    double dist = 0;
    for (int k = fromIdx + 1; k <= toIdx; k++) {
      int dc = nodes.get(k).cost - nodes.get(k - 1).cost;
      if (dc > 0) cost += dc;
      dist += nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    return dist > 0 ? cost / dist : Double.MAX_VALUE;
  }

  /**
   * Detect and repair wide-mouth bulges pinned at generated round-trip vias.
   *
   * <p>The planner can place a via in a pocket whose only escape is over roads
   * the profile penalizes (Basel reference: via on a grade2 field track 750m
   * west of a parallel asphalt cycle route — the loop pays ~11× the through
   * cost to touch it). The resulting detour has no ≤50m pinch point, so
   * {@link #removeMicroDetours}' teardrop band never sees it; and it encloses
   * real area (petal compactness ~0.9), so a thin-only filter can't separate
   * it from scenic petals. What does separate it is price: the span rides
   * roads at ≥{@link #BULGE_MIN_COST_FACTOR}× the track's average cost/m.
   *
   * <p>Because the mouth is wide, the span cannot simply be deleted (that
   * would leave an off-road beeline). Instead the mouth is re-routed: a local
   * {@link #findTrack} between the two mouth nodes, accepted only when the
   * connector meaningfully shortens the span
   * ({@link #BULGE_CONNECTOR_MAX_ARC_FRACTION}, {@link #BULGE_MIN_SAVED_M})
   * and undercuts its cost/m ({@link #BULGE_CONNECTOR_COST_ADVANTAGE}) — when
   * the network offers no better connector, the bulge was forced, and it stays.
   */
  void repairViaPinnedBulges(OsmTrack track, List<MatchedWaypoint> waypoints) {
    List<OsmPathElement> nodes = track.nodes;
    if (nodes == null || nodes.size() < 10 || waypoints == null || waypoints.size() < 3) {
      return;
    }
    long totalDist = 0;
    for (int k = 1; k < nodes.size(); k++) {
      totalDist += nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    int arcCap = (int) Math.min(VIA_TEARDROP_MAX_ARC_M, VIA_TEARDROP_MAX_ARC_FRAC * totalDist);
    double trackCostPerM = spanCostPerMeter(nodes, 0, nodes.size() - 1);

    for (int wi = 1; wi < waypoints.size() - 1; wi++) {
      MatchedWaypoint via = waypoints.get(wi);
      if (!isGeneratedRoundTripWaypoint(via)) continue;
      int v = via.indexInTrack;
      if (v <= 0 || v >= nodes.size() - 1) continue;
      int lo = Math.max(0, waypoints.get(wi - 1).indexInTrack + 1);
      int hi = Math.min(nodes.size() - 1, waypoints.get(wi + 1).indexInTrack - 1);
      int[] span = findViaPinnedBulgeSpan(nodes, v, lo, hi, arcCap);
      if (span == null) continue;
      double spanCpm = spanCostPerMeter(nodes, span[0], span[1]);
      if (trackCostPerM > 0 && spanCpm < BULGE_MIN_COST_FACTOR * trackCostPerM) {
        logInfo("repairViaPinnedBulges: " + via.name + " span " + span[0] + "-" + span[1]
          + " kept as petal (span " + spanCpm + " vs track " + trackCostPerM + " cost/m)");
        continue; // priced like the rest of the loop — a petal, not an artifact
      }
      OsmPathElement ni = nodes.get(span[0]);
      OsmPathElement nj = nodes.get(span[1]);
      double arc = 0;
      for (int k = span[0] + 1; k <= span[1]; k++) {
        arc += nodes.get(k - 1).calcDistance(nodes.get(k));
      }
      double crowFly = CheapRuler.distance(ni.getILon(), ni.getILat(), nj.getILon(), nj.getILat());

      List<OsmNode> mouthPts = new ArrayList<>();
      mouthPts.add(new OsmNode(ni.getILon(), ni.getILat()));
      mouthPts.add(new OsmNode(nj.getILon(), nj.getILat()));
      List<MatchedWaypoint> mouth = batchMatchToRoads(mouthPts, 100.0, "bulge_repair");
      if (mouth == null || !isRoadSnap(mouth.get(0)) || !isRoadSnap(mouth.get(1))) {
        logInfo("repairViaPinnedBulges: " + via.name + " mouth snap failed");
        continue;
      }

      OsmTrack connector = null;
      OsmTrack savedGuide = guideTrack;
      guideTrack = null; // a live guide track would corrupt the local search
      try {
        connector = findTrack("bulge-repair", mouth.get(0), mouth.get(1), null, null, false);
      } catch (RuntimeException e) {
        logInfo("repairViaPinnedBulges: connector routing failed (" + e.getMessage() + ")");
      } finally {
        guideTrack = savedGuide;
      }
      if (connector == null || connector.nodes == null || connector.nodes.size() < 2) {
        logInfo("repairViaPinnedBulges: " + via.name + " no connector route");
        continue;
      }
      double connCpm = connector.distance > 0
        ? (double) connector.cost / connector.distance : Double.MAX_VALUE;
      if (connector.distance > arc * BULGE_CONNECTOR_MAX_ARC_FRACTION
          || arc - connector.distance < BULGE_MIN_SAVED_M
          || connCpm * BULGE_CONNECTOR_COST_ADVANTAGE > spanCpm) {
        logInfo(String.format(Locale.US,
          "repairViaPinnedBulges: %s connector rejected (dist=%d crowFly=%.0f arc=%.0f connCpm=%.2f spanCpm=%.2f)",
          via.name, connector.distance, crowFly, arc, connCpm, spanCpm));
        continue;
      }

      // Splice: replace the span interior with the connector's nodes, trimming
      // connector endpoints that duplicate the mouth nodes.
      List<OsmPathElement> conn = connector.nodes;
      int cs = 0;
      int ce = conn.size();
      while (cs < ce && conn.get(cs).calcDistance(ni) <= 2) cs++;
      while (ce > cs && conn.get(ce - 1).calcDistance(nj) <= 2) ce--;
      List<OsmPathElement> interior = new ArrayList<>(conn.subList(cs, ce));
      int removedNodes = span[1] - span[0] - 1;
      // Crossing guard: the connector is routed without sight of the rest of
      // the loop, so it can cut transversely across the outbound or return —
      // trading a fat bulge for a user-visible self-crossing (measured: AUTO
      // fastbike crossings +49% before this guard). Splice the node list
      // first, compare self-intersections, and revert if the count rose;
      // waypoint indices are only adjusted after acceptance.
      int crossingsBefore = RoundTripQualityGate.countSelfIntersections(track);
      List<OsmPathElement> oldInterior = new ArrayList<>(nodes.subList(span[0] + 1, span[1]));
      nodes.subList(span[0] + 1, span[1]).clear();
      nodes.addAll(span[0] + 1, interior);
      int crossingsAfter = RoundTripQualityGate.countSelfIntersections(track);
      if (crossingsAfter > crossingsBefore) {
        nodes.subList(span[0] + 1, span[0] + 1 + interior.size()).clear();
        nodes.addAll(span[0] + 1, oldInterior);
        logInfo("repairViaPinnedBulges: " + via.name + " connector rejected (would add "
          + (crossingsAfter - crossingsBefore) + " self-crossing(s))");
        continue;
      }
      adjustWaypointIndices(waypoints, span[0], span[1] - 1, removedNodes - interior.size());

      logInfo(String.format(Locale.US,
        "repairViaPinnedBulges: at %s replaced %.0fm bulge (mouth %.0fm, span %.2f cost/m vs track %.2f) with %dm connector (%.2f cost/m)",
        via.name, arc, crowFly, spanCpm, trackCostPerM, connector.distance, connCpm));
    }
  }

  /**
   * Artifact-spur repair: compactness at or below this is an artifact by shape.
   * Raised 0.30 → 0.65 (2026-06-10, user calibration): the freiburg 80km N
   * petals (compactness 0.38 and 0.62) are detour loops to the cyclist's eye —
   * riding a circle back to within 60m of an earlier point is an artifact at
   * any ordinary fatness. Only a near-circular sub-loop (above 0.65) survives
   * on shape alone; user vias and the distance floor still protect deliberate
   * excursions.
   */
  static final double SPUR_ARTIFACT_MAX_COMPACTNESS = 0.65;
  /** Artifact by price: span cost/m at or above this multiple of the track average. */
  static final double SPUR_ARTIFACT_MIN_COST_FACTOR = 1.3;
  /** Near-revisit parameters (mirror {@link LoopQualityMetrics#computeSpurInfo}). */
  static final double SPUR_REPAIR_EPS_M = 60.0;
  static final double SPUR_REPAIR_MIN_ARC_M = 600.0;
  static final int SPUR_REPAIR_MAX_ARC_M = 6000;
  /** Never repair below this fraction of the requested loop distance. */
  static final double SPUR_REPAIR_MIN_DISTR = 0.85;

  /** Requested total loop distance from the request context, 0 when unknown. */
  private double roundTripExpectedDistance() {
    if (routingContext.roundTripLength != null) {
      return routingContext.roundTripLength;
    }
    if (routingContext.roundTripDistance != null && routingContext.roundTripDistance > 0) {
      return 2 * Math.PI * routingContext.roundTripDistance; // roundTripDistance is a radius
    }
    return 0;
  }

  /**
   * Whether this waypoint is engine-generated (greedy planner vias carry the
   * {@code generated} flag; the WAYPOINT algorithm's points use the "rt" name
   * prefix). User-supplied waypoints match neither — they express route
   * intent and must never be repaired away.
   */
  private static boolean isGeneratedRoundTripWaypoint(MatchedWaypoint wp) {
    return wp.generated || (wp.name != null && wp.name.startsWith("rt"));
  }

  /** Whether the span (i, j) strictly contains a USER waypoint (not planner-generated). */
  private static boolean spanContainsUserWaypoint(List<MatchedWaypoint> waypoints, int i, int j) {
    if (waypoints == null) return false;
    for (int wi = 1; wi < waypoints.size() - 1; wi++) {
      MatchedWaypoint wp = waypoints.get(wi);
      if (!isGeneratedRoundTripWaypoint(wp) && wp.indexInTrack > i && wp.indexInTrack < j) return true;
    }
    return false;
  }

  /**
   * Remove artifact near-revisit spur spans from a round-trip loop — the
   * residual class every narrower pass misses: start-stem antennas pinned at
   * no via, pinches between removeMicroDetours' 50m and the detector's 60m,
   * arcs above the 4km via-band, and overpriced-but-fat excursions. The span
   * source is the locked near-revisit primitive
   * ({@link LoopQualityMetrics#nearRevisitSpans}); a span is an ARTIFACT (and
   * removed) when it is thin ({@link RoutingEngine#petalCompactness} ≤
   * {@link #SPUR_ARTIFACT_MAX_COMPACTNESS}) or rides roads priced ≥
   * {@link #SPUR_ARTIFACT_MIN_COST_FACTOR}× the track average — a scenic
   * petal is neither. Guards: spans containing a USER via are never touched;
   * removal never takes the loop below {@link #SPUR_REPAIR_MIN_DISTR} of the
   * requested distance (or 90% of the current length when the request is
   * unknown); a removal that would create a self-crossing (the ≤60m splice
   * jump can transversely cut another part of the loop) is reverted.
   */
  void removeArtifactSpurSpans(OsmTrack track, List<MatchedWaypoint> waypoints) {
    List<OsmPathElement> nodes = track.nodes;
    if (nodes == null || nodes.size() < 10) return;
    long totalDist = 0;
    for (int k = 1; k < nodes.size(); k++) {
      totalDist += nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    double expected = roundTripExpectedDistance();
    double minTotal = expected > 0 ? SPUR_REPAIR_MIN_DISTR * expected : 0.9 * totalDist;
    double trackCpm = spanCostPerMeter(nodes, 0, nodes.size() - 1);

    boolean changed = true;
    while (changed) {
      changed = false;
      int maxArc = (int) Math.min(SPUR_REPAIR_MAX_ARC_M, VIA_TEARDROP_MAX_ARC_FRAC * totalDist);
      List<int[]> spans = new ArrayList<>(LoopQualityMetrics.nearRevisitSpans(
        nodes, SPUR_REPAIR_EPS_M, SPUR_REPAIR_MIN_ARC_M, maxArc));
      double[] cum = new double[nodes.size()];
      for (int k = 1; k < nodes.size(); k++) {
        cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
      }
      // Largest-arc first: the distance floor caps how much can be removed in
      // total, so spend that budget on the worst offender, not scan order.
      spans.sort((a, b) -> Double.compare(cum[b[1]] - cum[b[0]], cum[a[1]] - cum[a[0]]));
      for (int[] s : spans) {
        int i = s[0];
        int j = s[1];
        double arc = cum[j] - cum[i];
        double jump = nodes.get(i).calcDistance(nodes.get(j));
        if (totalDist - (arc - jump) < minTotal) continue;
        if (spanContainsUserWaypoint(waypoints, i, j)) continue;
        boolean thin = petalCompactness(nodes, i, j, arc) <= SPUR_ARTIFACT_MAX_COMPACTNESS;
        boolean overpriced = trackCpm > 0
          && spanCostPerMeter(nodes, i, j) >= SPUR_ARTIFACT_MIN_COST_FACTOR * trackCpm;
        if (!thin && !overpriced) continue; // scenic petal — keep
        int crossingsBefore = RoundTripQualityGate.countSelfIntersections(track);
        List<OsmPathElement> oldInterior = new ArrayList<>(nodes.subList(i + 1, j));
        nodes.subList(i + 1, j).clear();
        if (RoundTripQualityGate.countSelfIntersections(track) > crossingsBefore) {
          nodes.addAll(i + 1, oldInterior);
          continue;
        }
        adjustWaypointIndices(waypoints, i, j - 1, oldInterior.size());
        totalDist -= (long) (arc - jump);
        logInfo(String.format(Locale.US,
          "removeArtifactSpurSpans: removed %.0fm spur span [%d..%d] (%s)",
          arc, i, j, thin ? "thin" : "overpriced"));
        changed = true;
        break; // indices shifted — rescan
      }
    }
  }

  /**
   * Relink each node's origin back-pointer to its predecessor in the (possibly
   * edited) nodes list, so the origin chain length matches nodes.size(). Required
   * after round-trip node removal because processVoiceHints() walks the origin
   * chain rather than the list.
   */
  private static void rebuildOriginChain(OsmTrack track) {
    List<OsmPathElement> nodes = track.nodes;
    for (int i = 0; i < nodes.size(); i++) {
      nodes.get(i).origin = (i == 0) ? null : nodes.get(i - 1);
    }
  }

  // Hints closer together than this are treated as one maneuver for round-trip cleanup.
  private static final double ROUNDTRIP_VOICEHINT_MERGE_DIST = 25.0; // meters

  /**
   * Collapse clusters of voice hints produced by synthetic round-trip geometry
   * (waypoint-snapping wiggles and curves reported as several turns). Within a run
   * of hints spaced closer than {@link #ROUNDTRIP_VOICEHINT_MERGE_DIST}, if the net
   * turn is near-straight the whole cluster is dropped; otherwise only the single
   * dominant turn is kept. Roundabouts, beelines and the end marker are never merged,
   * and the conservative distance threshold leaves genuine close turns intact.
   * Round-trip only — does not affect normal point-to-point routes.
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
   * Check if a loop (between matchIdx and currentIdx in the track) is near
   * a generated roundtrip waypoint — either flagged {@link MatchedWaypoint#generated}
   * (greedy planner vias, densification bulges) or named with the WAYPOINT
   * algorithm's "rt" prefix. User-supplied waypoints match neither.
   */
  private boolean isNearGeneratedWaypoint(List<OsmPathElement> nodes, int matchIdx, int currentIdx,
                                          List<MatchedWaypoint> waypoints, int proximityMeters) {
    // Check both endpoints and midpoint of the loop for proximity to waypoints
    int midIdx = (matchIdx + currentIdx) / 2;
    for (int checkIdx : new int[]{matchIdx, midIdx, currentIdx}) {
      if (checkIdx < 0 || checkIdx >= nodes.size()) continue;
      OsmPathElement refNode = nodes.get(checkIdx);
      for (MatchedWaypoint mwp : waypoints) {
        if (!mwp.generated && (mwp.name == null || !mwp.name.startsWith("rt"))) continue;
        if (mwp.crosspoint == null) continue;
        if (refNode.calcDistance(mwp.crosspoint) <= proximityMeters) return true;
      }
    }
    return false;
  }

  /**
   * Shape thinness of the sub-track {@code nodes[fromIdx..toIdx]} treated as a
   * closed petal (auto-closed by the {@code toIdx → fromIdx} chord): shoelace
   * polygon area divided by the area of a circle with the same perimeter.
   * Self-intersecting (zigzag) spans cancel in the shoelace sum and score near
   * 0 — correctly classified as thin artifacts. Result clamped to [0, 1].
   */
  static double petalCompactness(List<OsmPathElement> nodes, int fromIdx, int toIdx,
                                 double loopDistMeters) {
    if (loopDistMeters <= 0 || toIdx - fromIdx < 2) return 1.0; // degenerate: keep
    double[] kxky = CheapRuler.getLonLatToMeterScales(nodes.get(fromIdx).getILat());
    double kx = kxky[0];
    double ky = kxky[1];
    int lon0 = nodes.get(fromIdx).getILon();
    int lat0 = nodes.get(fromIdx).getILat();
    double area2 = 0;
    double px = 0;
    double py = 0; // origin = first span node, so the closing chord contributes 0
    for (int k = fromIdx + 1; k <= toIdx; k++) {
      double x = (nodes.get(k).getILon() - lon0) * kx;
      double y = (nodes.get(k).getILat() - lat0) * ky;
      area2 += px * y - x * py;
      px = x;
      py = y;
    }
    double area = Math.abs(area2) / 2.0;
    double circleArea = loopDistMeters * loopDistMeters / (4.0 * Math.PI);
    return Math.min(1.0, area / circleArea);
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
   * (0.7R, 1.0R, 1.3R) and snaps each to the road network. Returns the
   * viable bearings plus a per-direction successful-probe count (the FAST tier
   * consumes that count via {@link #filterByProbeConfidence} to drop one-shot
   * weak picks).
   *
   * @param start        the start waypoint
   * @param searchRadius the round-trip search radius in meters
   * @return viable bearings + per-direction scoring; {@code null} on probe failure
   */
  ProbeResult probeReachableDirections(OsmNodeNamed start, double searchRadius) {
    resetCache(false);
    double maxSnapDist = Math.min(searchRadius * 0.3, 2000);
    double[] distFactors = {0.7, 1.0, 1.3};
    int probeCount = 24; // every 15 degrees
    double angleStep = 360.0 / probeCount;
    int probesPerDirection = distFactors.length;

    List<MatchedWaypoint> allProbes = new ArrayList<>();
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

    try {
      nodesCache.matchWaypointsToNodes(allProbes, maxSnapDist, islandNodePairs);
    } catch (Exception e) {
      logInfo("reachability probe failed: " + e.getMessage());
      return null;
    }

    int probeOffset = 1; // start probe is at index 0
    double[] viable = new double[probeCount];
    int viableCount = 0;
    List<ProbeDirection> scored = new ArrayList<>();
    for (int d = 0; d < probeCount; d++) {
      double dir = d * angleStep;
      int successCount = 0;
      for (int f = 0; f < probesPerDirection; f++) {
        MatchedWaypoint mwp = allProbes.get(probeOffset + d * probesPerDirection + f);
        if (mwp.crosspoint == null || mwp.radius > maxSnapDist) continue;
        successCount++;
      }
      if (successCount == 0) continue;
      viable[viableCount++] = dir;
      scored.add(new ProbeDirection(dir, successCount));
    }

    logInfo("reachability probe: " + viableCount + "/" + probeCount + " directions viable");
    if (viableCount == 0) return null;
    return new ProbeResult(java.util.Arrays.copyOf(viable, viableCount), scored);
  }

  /**
   * FAST-tier helper: drop directions where only a single probe distance snapped, IF at
   * least {@code max(targetPoints, 8)} multi-probe-strong directions remain. A 1/3
   * success rate usually marks a thin road sliver or sea-edge that snaps weakly; selecting
   * it produces a fragile waypoint. When few strong directions exist (constrained
   * terrain), we keep the weak ones to avoid collapsing to a circle fallback.
   *
   * @return the viable-direction subset to feed into placement
   */
  static double[] filterByProbeConfidence(ProbeResult probe, int targetPoints) {
    if (probe == null) return null;
    if (probe.scored.isEmpty()) return probe.viableDirections;
    int strongCount = 0;
    for (ProbeDirection pd : probe.scored) if (pd.successfulProbeCount >= 2) strongCount++;
    int threshold = Math.max(targetPoints, 8);
    if (strongCount < threshold) return probe.viableDirections;
    double[] kept = new double[probe.scored.size()];
    int n = 0;
    for (ProbeDirection pd : probe.scored) {
      if (pd.successfulProbeCount >= 2) kept[n++] = pd.direction;
    }
    return java.util.Arrays.copyOf(kept, n);
  }

  /**
   * Weight of the air-distance "reach bonus" in the cost-contour scoring rule.
   * The bonus is a soft tiebreaker; this weight is the trade-off threshold,
   * i.e. a 10% normalized cost error completely cancels the max air-reach
   * bonus (chosen so cost dominates whenever it's meaningfully different).
   */
  static final double AIR_REACH_BONUS_WEIGHT = 0.10;

  /** Frontier-entry layout indices for the 6-element isochrone form. */
  private static final int FRONTIER_IDX_ILON = 4;
  private static final int FRONTIER_IDX_ILAT = 5;
  private static final int FRONTIER_LENGTH_ROAD_NATIVE = 6;

  /**
   * Score a Dijkstra-popped node against a target cost level. Lower wins.
   * {@code costError} is the normalized distance from {@code targetCost};
   * {@code airReachBonus} rewards farther-reached nodes as a soft tiebreaker.
   * Used by {@link #runIsochroneExpansion} to pick the per-bucket frontier
   * node and the 25/50/75% contour candidates.
   *
   * @param pathCost     Dijkstra path cost from start, in cost-units
   * @param targetCost   target cost level (costBudget or a contour fraction of it)
   * @param dist         air-distance from start to the popped node, in meters
   * @param searchRadius round-trip search radius, used to normalize air-reach
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
   * Extract the road-native coordinate ({@code [ilon, ilat]}) from a frontier
   * entry, or {@code null} if the entry doesn't carry one. The frontier coord
   * is the cost-budget-envelope node — a fallback for callers that don't pass
   * the full candidate pool to {@link #placeWaypointsFromIsochrone}; production
   * placement prefers {@link #nearestCandidateByAirDist} (airDist-aware).
   *
   * <p>Isochrone-produced entries are 6-element; probe-only entries from
   * {@link #mergeIsochroneWithProbe} are 4-element and have no road-native data.
   */
  static int[] frontierRoadNativeCoord(double[] entry) {
    if (entry == null || entry.length < FRONTIER_LENGTH_ROAD_NATIVE) return null;
    return new int[]{(int) entry[FRONTIER_IDX_ILON], (int) entry[FRONTIER_IDX_ILAT]};
  }

  /**
   * Pick the candidate in {@code bucketCandidates} whose air-distance from start
   * is closest to {@code targetAirDist}, or {@code null} if the bucket has no
   * candidates. Used by {@link #placeWaypointsFromIsochrone} to preserve the
   * indirectness-compensated placement radius while still using a road-native
   * point (each bucket carries one frontier-max + up to three contour candidates
   * at distinct cost depths, so a close airDist match is usually available).
   */
  static IsoCandidate nearestCandidateByAirDist(List<IsoCandidate> bucketCandidates, double targetAirDist) {
    if (bucketCandidates == null || bucketCandidates.isEmpty()) return null;
    IsoCandidate best = null;
    double bestDiff = Double.MAX_VALUE;
    for (IsoCandidate c : bucketCandidates) {
      double diff = Math.abs(c.airDistanceFromStart - targetAirDist);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = c;
      }
    }
    return best;
  }

  /**
   * Run a cost-limited Dijkstra expansion from the start point to discover
   * the reachable road network frontier in all directions.
   *
   * <p>Uses the match → resetCache → getGraphNode pattern from _findTrack to
   * correctly initialize graph nodes from production segment files.
   *
   * @param start        the start waypoint
   * @param searchRadius the round-trip search radius in meters
   * @return frontier table + road-native candidate pool; {@code null} on failure
   */
  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius) {
    return runIsochroneExpansion(start, searchRadius, null, false);
  }

  IsochroneExpansionResult runIsochroneExpansion(OsmNodeNamed start, double searchRadius,
                                                 OsmTrack refTrack,
                                                 boolean includeCandidateTracks) {
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
    java.util.Arrays.fill(bucketBestScore, Double.POSITIVE_INFINITY);
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
    for (double[] row : bucketContourBestScore) java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
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
    java.util.Set<Long> visitedCells = new java.util.HashSet<>(4096);

    for (;;) {
      OsmPath path = isoOpenSet.popLowestKeyValue();
      if (path == null) break;
      if (path.airdistance == -1) continue; // invalidated

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
      visitedCells.add((((long) (curIlon / cellDivLon)) << 32)
        | ((curIlat / cellDivLat) & 0xFFFFFFFFL));
      double dist = CheapRuler.distance(start.ilon, start.ilat, curIlon, curIlat);
      if (accumulatingDesirabilityGrid && dist > 50) {
        // Accumulate profile-cost-density per ~500m cell (issue #15 heatmap).
        // pref saturates to 1.0 at costEff <= ROAD_INDIRECTNESS (1.3), i.e. a
        // fastbike on flat tarmac; gravel/MTB profiles have higher costEff floors,
        // so their pref ceiling is correspondingly below 1.0.
        double costEff = path.cost / dist;                 // cost-units per air-meter
        double pref = costEff > 0 ? Math.min(1.0, 1.3 / costEff) : 1.0;
        long key = (long) (curIlon / DESIRABILITY_CELL) * 1_000_000L + (curIlat / DESIRABILITY_CELL);
        double[] cell = desirabilityGrid.get(key);
        // Slots: [nodeCount, prefSum, eleSum, eleCount]. nodeCount drives the
        // capsule density classification; eleSum/eleCount the elevation reward.
        if (cell == null) { cell = new double[4]; desirabilityGrid.put(key, cell); }
        cell[0] += 1;
        cell[1] += pref;
        // Capsule prototype: accumulate elevation per cell (sentinels = no SRTM).
        short selev = currentNode.selev;
        if (selev != Short.MIN_VALUE && selev != -12345) {
          cell[2] += selev / 4.0; // selev is in 1/4-meter units
          cell[3] += 1;
        }
      }
      if (dist > 50) { // skip very close nodes (noisy bearings)
        int pcost = path.cost;
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
      visitedCells, cellDivLon, cellDivLat);
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
  /** ISOCHRONE direction-bulge strength: the per-direction placement radius is
   *  scaled by 1 + alpha*cos(theta - heading), a mean-preserving cardioid toward
   *  the requested heading (0 = legacy even ring). Package-private and non-final
   *  only so tests can drive both ends — it is not a runtime knob. */
  static double isochroneDirBulgeAlpha = 0.35;

  void placeWaypointsFromIsochrone(List<OsmNodeNamed> waypoints, double[][] frontierData,
                                   List<IsoCandidate> isoCandidates,
                                   double searchRadius, double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    int needed = targetPoints - 1;
    if (needed < 2) needed = 2;

    // Group the road-native candidate pool by bucket so per-direction placement
    // can pick the candidate (frontier-max or 25/50/75 contour) whose
    // air-distance is closest to the indirectness-compensated target — preserves
    // the loop-size scaling while keeping the waypoint on a real road.
    Map<Integer, List<IsoCandidate>> candidatesByBucket;
    if (isoCandidates != null && !isoCandidates.isEmpty()) {
      candidatesByBucket = new HashMap<>();
      for (IsoCandidate c : isoCandidates) {
        candidatesByBucket.computeIfAbsent(c.bucket, k -> new ArrayList<>(4)).add(c);
      }
    } else {
      candidatesByBucket = Collections.emptyMap();
    }

    // Pre-filter the frontier data:
    //   1. Drop sea-blocked / dead-end directions whose airDist is far below the
    //      target placement radius. Selecting these would put a waypoint in the
    //      ocean (coastal_nice 50km failure mode) or at a one-shot dead-end
    //      (rural_lozere garbage signal).
    //   2. Drop low-population buckets (hits < 3) — likely one-shot dead-ends.
    //   3. Keep at least 4 directions even if filtering would leave fewer, so
    //      the loop can still be constructed.
    double minFrontierReach = searchRadius * 0.4;
    int minHits = 3;
    List<double[]> usable = new ArrayList<>();
    for (double[] entry : frontierData) {
      double airDist = entry[1];
      int hits = entry.length > 3 ? (int) entry[3] : 1;
      if (airDist >= minFrontierReach && hits >= minHits) {
        usable.add(entry);
      }
    }
    if (usable.size() < 4) {
      // Signal too thin — relax filters and take whatever we have.
      usable.clear();
      for (double[] entry : frontierData) {
        if (entry[1] >= searchRadius * 0.2) usable.add(entry);
      }
    }

    int n = usable.size();
    if (needed > n) needed = n;
    if (needed < 2) needed = 2;

    double[] directions = new double[n];
    Map<Double, double[]> dirToData = new HashMap<>(); // dir -> entry array
    for (int i = 0; i < n; i++) {
      double[] entry = usable.get(i);
      directions[i] = entry[0];
      dirToData.put(entry[0], entry);
    }

    double[] selected;
    if (needed >= n) {
      selected = directions;
    } else {
      selected = selectSpreadDirections(directions, needed, startDirection);
    }
    selected = sortDirectionsForLoop(selected, startDirection);

    // Base radius from polygon geometry (legacy v1.7.8-compatible), then
    // compensated by observed road-geometry indirectness. The cost/airDist
    // ratio from the isochrone equals (roadDist/airDist) × profileCostFactor.
    // To isolate the road geometry part we estimate profileCostFactor from the
    // minimum observed indirectness across all usable directions (the easiest
    // road's indirectness ≈ pure profile costfactor on flat direct road).
    double geomBase = searchRadius * computeRadiusScale(selected, targetPoints);

    // Per-direction observed cost/airDist ratio. Median (not mean) so a single
    // outlier direction doesn't dominate redistribution.
    double[] selectedInd = new double[selected.length];
    for (int i = 0; i < selected.length; i++) {
      double[] data = dirToData.get(selected[i]);
      double airDist = data[1];
      double cost = data[2];
      selectedInd[i] = (airDist > 50) ? Math.max(1.0, cost / airDist) : 1.5;
    }
    double[] sortedInd = selectedInd.clone();
    java.util.Arrays.sort(sortedInd);
    double medianInd = Math.max(1.0, sortedInd[selectedInd.length / 2]);

    // Estimate profile-only cost factor from the easiest direction across ALL
    // observed directions (not just selected), so directional pre-filter doesn't
    // bias it. Min reasonable value: 1.0 (already clamped during data read).
    double minObservedInd = Double.MAX_VALUE;
    for (double[] entry : usable) {
      double aD = entry[1], c = entry[2];
      if (aD > 50) {
        double ind = Math.max(1.0, c / aD);
        if (ind < minObservedInd) minObservedInd = ind;
      }
    }
    if (minObservedInd == Double.MAX_VALUE) minObservedInd = DEFAULT_PROBE_INDIRECTNESS;
    double profileCostFactor = Math.max(1.0, minObservedInd);
    // Pure road-geometry indirectness (road meters per air meter), profile-free.
    double geomInd = medianInd / profileCostFactor;
    // Compensate the base radius: in indirect terrain (high geomInd) shrink so
    // the actual routed loop matches target distance. Conservative ±20%.
    double indCompensation = REFERENCE_GEOM_INDIRECTNESS / Math.max(1.0, geomInd);
    indCompensation = Math.max(IND_COMPENSATION_MIN, Math.min(IND_COMPENSATION_MAX, indCompensation));
    double baseRadius = geomBase * indCompensation;

    // Per-direction redistribution factors. Indirect dirs (mountains) → factor <
    // 1 → closer; direct dirs (valley floor) → factor > 1 → farther. Normalize
    // so the average factor = 1.0 (mean-preserving).
    double[] rawFactors = new double[selected.length];
    double factorSum = 0;
    for (int i = 0; i < selected.length; i++) {
      rawFactors[i] = medianInd / selectedInd[i];
      factorSum += rawFactors[i];
    }
    double normalization = selected.length / factorSum;

    // Directional bulge: bias the placement radius toward startDirection so the
    // loop heads that way (a cardioid) instead of encircling evenly — this is
    // what lets ISOCHRONE honour the requested direction, which the bare
    // even-spread frontier sampling cannot. Mean-preserving: the per-direction
    // factors are renormalised to average 1.0, so the loop's overall size — and
    // therefore the distance gate — is unchanged; only its shape shifts toward
    // the heading. isochroneDirBulgeAlpha=0 reproduces the legacy even ring.
    double dirBulgeAlpha = isochroneDirBulgeAlpha;
    double[] dirBulge = new double[selected.length];
    double dirBulgeSum = 0;
    for (int i = 0; i < selected.length; i++) {
      dirBulge[i] = 1.0 + dirBulgeAlpha * Math.cos(Math.toRadians(selected[i] - startDirection));
      dirBulgeSum += dirBulge[i];
    }
    double dirBulgeNorm = dirBulgeSum > 0 ? selected.length / dirBulgeSum : 1.0;

    double maxDist = searchRadius * 1.5;
    double minDist = searchRadius * 0.15;
    int roadNativeCount = 0;
    int syntheticCount = 0;
    double bucketSize = 360.0 / 36; // matches runIsochroneExpansion
    for (int i = 0; i < selected.length; i++) {
      double factor = Math.max(0.5, Math.min(2.0,
        rawFactors[i] * normalization * dirBulge[i] * dirBulgeNorm));
      double airDist = baseRadius * factor;
      airDist = Math.max(minDist, Math.min(maxDist, airDist));

      // Pick the road-native candidate in this bucket whose air-distance is
      // closest to airDist — but only if it's within ±2× of the target,
      // otherwise the candidate (typically the cost-budget-envelope frontier-max)
      // would defeat the per-direction indirectness compensation. When out of
      // tolerance, synthesize at the exact target and let matchWaypointsToNodes
      // snap to the nearest road. Frontier-entry coord (entry[4..5]) is a
      // legacy fallback for callers without a candidate pool.
      int bucketIdx = ((int) (selected[i] / bucketSize)) % 36;
      if (bucketIdx < 0) bucketIdx += 36;
      IsoCandidate bestCand = nearestCandidateByAirDist(candidatesByBucket.get(bucketIdx), airDist);
      boolean candAcceptable = bestCand != null
        && bestCand.airDistanceFromStart >= airDist * 0.5
        && bestCand.airDistanceFromStart <= airDist * 2.0;
      int[] pos;
      if (candAcceptable) {
        pos = new int[]{bestCand.ilon, bestCand.ilat};
        roadNativeCount++;
      } else if (bestCand == null) {
        int[] frontierCoord = frontierRoadNativeCoord(dirToData.get(selected[i]));
        if (frontierCoord != null) {
          pos = frontierCoord;
          roadNativeCount++;
        } else {
          pos = CheapRuler.destination(start.ilon, start.ilat, airDist, selected[i]);
          syntheticCount++;
        }
      } else {
        pos = CheapRuler.destination(start.ilon, start.ilat, airDist, selected[i]);
        syntheticCount++;
      }
      OsmNodeNamed onn = new OsmNodeNamed(new OsmNode(pos[0], pos[1]));
      onn.name = "rt" + (i + 1);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    logInfo("placeWaypointsFromIsochrone: " + selected.length + " waypoints"
      + " (" + roadNativeCount + " road-native, " + syntheticCount + " synthetic)"
      + ", baseRadius=" + (int) baseRadius + "m"
      + ", medianInd=" + String.format("%.2f", medianInd)
      + ", searchRadius=" + (int) searchRadius + "m");
  }

  /**
   * Merge isochrone frontier data with probe directions for gap-filling.
   * Isochrone entries are 6-element {@code [direction, airDist, cost, hits,
   * ilon, ilat]} (the last two carry the road-native frontier coord);
   * probe-only entries are 4-element {@code [direction, searchRadius,
   * searchRadius*1.3, 0]} (estimated cost, no road-native data, hits=0).
   * Existing isochrone entries dominate overlapping probe directions.
   *
   * @param frontier        isochrone entries (may be null)
   * @param probeDirections probe viable directions in degrees (may be null)
   * @param searchRadius    fallback distance for probe-only directions
   * @return merged frontier entries; {@code null} if both inputs empty.
   *         Entry length varies: 6 for isochrone-sourced, 4 for probe-only.
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
          // Probe-only: use searchRadius as airDist, estimate cost with default indirectness.
          // hits=0 marks this as "probed but not observed by isochrone" — lower confidence.
          merged.put(bucket, new double[]{dir, searchRadius, searchRadius * DEFAULT_PROBE_INDIRECTNESS, 0});
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
   *
   * <p><b>Known parity gap (P5):</b> unlike {@link #placeWaypointsFromIsochrone},
   * this fallback applies only the geometric {@code computeRadiusScale} correction
   * and does NOT apply terrain-indirectness compensation (it has no per-direction
   * isochrone cost data to derive it from). It is reached precisely when the
   * merged isochrone+probe frontier has &lt;3 usable directions — i.e. indirect
   * terrain (mountains/coast), where unadjusted radii tend to overshoot the target
   * loop distance. Adding a conservative {@link #DEFAULT_PROBE_INDIRECTNESS}-based
   * shrink here is a candidate improvement but is a tuning change that needs
   * loop-quality-corpus validation before landing (cf. the analogous out-of-scope
   * note in docs/features/roundtrip-benchmark-2026-05.md).
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
   * Runs one greedy planning attempt — the inner sub-route-count loop with
   * a single {@code tryDirection}. Used by Phase 2.1 to attempt the same
   * planner twice (user direction first, axis-aligned direction on retry)
   * without code duplication.
   *
   * <p>Stamps Phase 2.0 telemetry on the result and updates
   * {@link #lastRoundTripResult} on every iteration so cross-attempt
   * comparison sees consistent metadata. Returns the final
   * {@link RoundTripResult} produced (which may be degraded — the caller
   * decides whether to accept or retry).
   */
  private RoundTripResult runGreedyAttempt(OsmNodeNamed start, double searchRadius,
                                           double desiredDistance, double tryDirection,
                                           int baseSubRouteCount,
                                           RoundTripCandidateProvider provider,
                                           IsoAsymmetryBias bias) {
    RoundTripResult result = null;
    for (int subRouteCount : greedySubRouteCountPlan(baseSubRouteCount)) {
      logInfo("greedy round trip: subRouteCount=" + subRouteCount + ", direction=" + (int) tryDirection);
      GreedyRoundTripPlanner planner = new GreedyRoundTripPlanner(this, provider,
        new CandidateScorer(), subRouteCount, 0.05, 8);
      planner.setHostilityActive(RoundTripQualityGate.isPavedProfile(routingContext.getProfileName()));
      planner.setProfileName(routingContext.getProfileName());
      planner.setVarietySeed(routingContext.getRoundTripSeed());
      result = planner.plan(start, desiredDistance, tryDirection);
      if (result != null) {
        result.setIsoAsymmetryBearingApplied(bias.applied);
        result.setIsoAsymmetryBearingDegrees(bias.bearingDegrees);
        result.setIsoAsymmetryBestBucketIndirectness(bias.indirectness);
        result.setIsoAsymmetryBestBucketHits(bias.hits);
        result.setIsoAsymmetryBestBucketAirDistMeters(bias.airDistMeters);
      }
      lastRoundTripResult = result;
      if (!isDegradedGreedyResult(result)
          && result != null && result.getLoopWaypoints() != null
          && result.getLoopWaypoints().size() >= 4) {
        return result;
      }
      logInfo("greedy: attempt with " + subRouteCount + " sub-routes did not produce an acceptable loop"
        + (result == null || result.getFallbackReason() == null ? "" : " (" + result.getFallbackReason() + ")"));
    }
    return result;
  }

  /** Phase 2.1: human-readable axis label for the infeasibility error. */
  private static String axisName(double axisBearingDegrees) {
    // axisBearingDegrees is canonical [0, 180). Snap to the nearest cardinal
    // pair for a readable label.
    double a = ((axisBearingDegrees % 180) + 180) % 180;
    if (a < 22.5 || a >= 157.5) return "N-S";
    if (a < 67.5) return "NE-SW";
    if (a < 112.5) return "E-W";
    return "NW-SE";
  }

  /**
   * Phase 2.0 of the closure-aware planning spec — isochrone-asymmetry
   * initial bearing. Examines the 36-bucket frontier table produced by
   * {@link #runIsochroneExpansion} and selects the most-reaching sector
   * (lowest {@code cost / airDist}) subject to quality thresholds.
   *
   * <p>Returns {@link IsoAsymmetryBias#NONE} when no bucket clears the
   * thresholds. The caller falls back to the legacy direction-selection
   * behavior in that case.
   *
   * <p>Package-private + static for unit testing with synthetic frontier
   * tables.
   */
  static IsoAsymmetryBias computeIsoAsymmetryBearing(double[][] frontier, double searchRadius) {
    if (frontier == null || frontier.length == 0) return IsoAsymmetryBias.NONE;
    final double minAirDist = 0.6 * searchRadius;
    final int minHits = 3;
    int bestIdx = -1;
    double bestIndirectness = Double.POSITIVE_INFINITY;
    for (int i = 0; i < frontier.length; i++) {
      double[] b = frontier[i];
      if (b == null || b.length < 4) continue;
      double airDist = b[1];
      double cost = b[2];
      int hits = (int) b[3];
      if (airDist < minAirDist || hits < minHits || airDist <= 0) continue;
      double indirectness = cost / airDist;
      if (indirectness < bestIndirectness) {
        bestIndirectness = indirectness;
        bestIdx = i;
      }
      // Tie-break: lowest bucket index wins (already enforced by strict <).
    }
    if (bestIdx < 0) return IsoAsymmetryBias.NONE;
    double[] best = frontier[bestIdx];
    return new IsoAsymmetryBias(true, best[0], bestIndirectness,
        (int) best[3], (int) best[1]);
  }

  /**
   * Phase 2.1 of the closure-aware planning spec — frontier-axis detection
   * for axis-aware retry when the user's explicit direction conflicts with
   * the terrain's natural orientation.
   *
   * <p>Computes the principal axis of the reachable-frontier displacement
   * vectors (good-quality buckets only, same thresholds as Phase 2.0).
   * Returns an axis bearing in [0, 180) and the eigenvalue ratio
   * (primary / secondary). An axis is considered "strong" when the ratio
   * exceeds {@link #PHASE_2_1_STRONG_AXIS_RATIO}, indicating the reachable
   * region is markedly elongated (Inn Valley, coast roads, ridge tops).
   */
  static FrontierAxis computeFrontierAxis(double[][] frontier, double searchRadius) {
    if (frontier == null || frontier.length < 6) return FrontierAxis.NONE;
    final double minAirDist = 0.6 * searchRadius;
    final int minHits = 3;
    double sumX2 = 0, sumY2 = 0, sumXY = 0;
    int n = 0;
    for (double[] b : frontier) {
      if (b == null || b.length < 4) continue;
      double airDist = b[1];
      int hits = (int) b[3];
      if (airDist < minAirDist || hits < minHits) continue;
      // Compass bearing → (east, north) Cartesian.
      double rad = Math.toRadians(b[0]);
      double x = airDist * Math.sin(rad); // east
      double y = airDist * Math.cos(rad); // north
      sumX2 += x * x;
      sumY2 += y * y;
      sumXY += x * y;
      n++;
    }
    if (n < 4) return FrontierAxis.NONE;
    double a = sumX2 / n;
    double bb = sumY2 / n;
    double c = sumXY / n;
    double trace = a + bb;
    double det = a * bb - c * c;
    double disc = Math.sqrt(Math.max(0, trace * trace - 4 * det));
    double lambda1 = (trace + disc) / 2;
    double lambda2 = (trace - disc) / 2;
    if (lambda2 <= 0 || lambda1 <= 0) return FrontierAxis.NONE;
    double strength = lambda1 / lambda2;
    // Closed-form principal-axis angle for a 2x2 symmetric covariance:
    // principalAngle = 0.5 * atan2(2c, a-b), in math convention (CCW from
    // east). Robust to c ≈ 0 (avoids the fragile eigenvector-from-eigenvalue
    // path which divides by tiny numbers). Convert to compass bearing
    // (CW from north): bearing = 90° − math_angle.
    double principalAngleDeg = 0.5 * Math.toDegrees(Math.atan2(2 * c, a - bb));
    double bearing = (90 - principalAngleDeg + 360) % 360;
    if (bearing >= 180) bearing -= 180; // canonical [0, 180), axis is bidirectional
    return new FrontierAxis(strength >= PHASE_2_1_STRONG_AXIS_RATIO, bearing, strength);
  }

  /** Phase 2.1: eigenvalue ratio above which we treat the reachable region
   *  as having a strong terrain axis. 3.0 corresponds to the reachable
   *  region being ~1.7x as elongated along the principal axis as
   *  perpendicular (sqrt(3) ≈ 1.73). Tunable; lower values fire more often. */
  static final double PHASE_2_1_STRONG_AXIS_RATIO = 3.0;

  /** Phase 2.1: half-angle (degrees) of the "near-perpendicular" cone.
   *  User direction within 30° of perpendicular to the axis triggers retry. */
  static final double PHASE_2_1_PERPENDICULAR_TOL = 30.0;

  /** Phase 2.1: whether a user-supplied bearing is within
   *  {@link #PHASE_2_1_PERPENDICULAR_TOL} of perpendicular to the given
   *  axis. Both arguments are in compass degrees; axis canonical [0, 180). */
  static boolean isPerpendicularToAxis(double userBearing, double axisBearing) {
    double userMod = ((userBearing % 180) + 180) % 180;
    double axisMod = ((axisBearing % 180) + 180) % 180;
    double diff = Math.abs(userMod - axisMod);
    if (diff > 90) diff = 180 - diff;
    return diff >= (90 - PHASE_2_1_PERPENDICULAR_TOL);
  }

  /** Phase 2.1: pick the axis-aligned bearing (axis or axis+180) whose
   *  half-plane is closer to the user's original direction. Used to retry
   *  with a direction that respects both terrain (axis) and rough user
   *  intent (the original half-plane). Tie-break: prefer the lower bearing. */
  static double chooseAxisBearing(double axisBearing, double userBearing) {
    double opt1 = ((axisBearing % 180) + 180) % 180;       // canonical axis
    double opt2 = (opt1 + 180) % 360;                       // opposing direction
    double user = ((userBearing % 360) + 360) % 360;
    double d1 = angularDiff(opt1, user);
    double d2 = angularDiff(opt2, user);
    if (d1 < d2) return opt1;
    if (d2 < d1) return opt2;
    return Math.min(opt1, opt2);
  }

  private static double angularDiff(double a, double b) {
    double d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
  }

  /** Phase 2.1 result: principal axis of the reachable-frontier
   *  displacements, with eigenvalue-ratio strength. */
  static final class FrontierAxis {
    static final FrontierAxis NONE = new FrontierAxis(false, Double.NaN, 0.0);
    final boolean hasStrongAxis;
    /** Axis bearing in [0, 180) — axis is direction-agnostic. */
    final double axisBearingDegrees;
    /** Eigenvalue ratio λ1 / λ2 of the displacement covariance. Strong axis
     *  iff this is at least {@link #PHASE_2_1_STRONG_AXIS_RATIO}. */
    final double strength;

    FrontierAxis(boolean hasStrongAxis, double axisBearingDegrees, double strength) {
      this.hasStrongAxis = hasStrongAxis;
      this.axisBearingDegrees = axisBearingDegrees;
      this.strength = strength;
    }
  }

  /** Phase 2.0 result: which bucket of the isochrone frontier the bias
   *  selected, plus the metadata for telemetry. */
  static final class IsoAsymmetryBias {
    static final IsoAsymmetryBias NONE =
      new IsoAsymmetryBias(false, Double.NaN, Double.NaN, -1, -1);
    final boolean applied;
    final double bearingDegrees;
    final double indirectness;
    final int hits;
    final int airDistMeters;

    IsoAsymmetryBias(boolean applied, double bearingDegrees, double indirectness,
                     int hits, int airDistMeters) {
      this.applied = applied;
      this.bearingDegrees = bearingDegrees;
      this.indirectness = indirectness;
      this.hits = hits;
      this.airDistMeters = airDistMeters;
    }
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
        filterRoundTripWaypoints(matchedWaypoints);
        if (matchedWaypoints.size() != beforeFilter) {
          logInfo("filterRoundTrip: reduced waypoints from " + beforeFilter + " to " + matchedWaypoints.size());
          refTracks = new OsmTrack[matchedWaypoints.size() - 1];
          lastTracks = new OsmTrack[matchedWaypoints.size() - 1];
        }
        // Snap intermediate waypoints to nearest intersection to avoid mid-edge detour tails
        snapToIntersection(matchedWaypoints);
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
        removeBackAndForthSegments(totaltrack, matchedWaypoints);
        removeMicroDetours(totaltrack, 1500, matchedWaypoints);
        // Same artifact-repair chain as the greedy adoption path
        // (finalizeAdoptedRoundTripTrack): probe/isochrone are fast fallback
        // algorithms worth keeping, and their generated "rt*" waypoints suffer
        // the same via-pinned bulges and near-revisit petals. Both passes
        // recognize rt-named waypoints as generated and carry the full guard
        // set (user-via protection, distance floor, crossing guard).
        repairViaPinnedBulges(totaltrack, matchedWaypoints);
        removeArtifactSpurSpans(totaltrack, matchedWaypoints);
      } else if (!routingContext.allowSamewayback && explicitViaRoundTrip && routingContext.explicitViaDensify) {
        // Densified explicit-via: strip the out-and-back spurs at GENERATED bulge points
        // only — never user vias. removeMicroDetours is still skipped (it would
        // delete the whole route, since the closing waypoint coincides with the start).
        // (Cleaning ALL waypoint spurs was tested and rejected: it did not fix the
        // leg-hostile cases and shortened load-bearing retraces on 1-via loops.)
        removeBackAndForthSegments(totaltrack, matchedWaypoints, true);
      }
      // removeBackAndForthSegments/removeMicroDetours edit the nodes list in place but
      // leave each node's origin back-pointer dangling through the removed nodes.
      // processVoiceHints() walks the origin chain (not the list), so a chain longer
      // than the list drives its node counter negative — producing voice hints with
      // negative indexInTrack and stale, out-of-range turn angles at the loop seam.
      // Relink origins to the surviving list order to restore the chain == list invariant.
      rebuildOriginChain(totaltrack);
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
   * Re-tracking pass that takes a raw track produced by single-pass
   * {@link #findTrack} and produces a detailed copy with per-edge
   * {@code MessageData} populated. The same pass that
   * {@link #searchRoutedTrack} runs internally as its final step,
   * exposed for the round-trip planner which needs detailed tracks
   * for its committed legs without paying the 2-pass routing cost
   * for every candidate it evaluates.
   *
   * <p>The single-pass tracks have correct geometry and cost but lack
   * the {@code wayKeyValues} fields required by the quality gate's
   * paved-profile hostility check. Re-tracking walks the existing path
   * via {@code guideTrack} so the resulting track follows exactly the
   * same nodes, just with full per-edge metadata.
   *
   * <p>{@code refTrack} is accepted for call-site compatibility but is not
   * applied during this pass. Reuse penalties belong to route choice; this
   * method only annotates an already-chosen route.
   */
  /**
   * Fallback time budget for the guided detail-retrack when the caller imposed
   * none ({@code maxRunningTime <= 0} — e.g. the quality tests' {@code doRun(0)}
   * or an untimed CLI run). The guided pass normally visits few nodes, but if it
   * exceeds the guide-track cost cap it can fall back to a free search; this caps
   * that so a pathological retrack cannot run unbounded (it then times out and
   * gracefully returns the raw track via the catch below). Production
   * ({@code maxRunningTime > 0}) is already bounded by the request budget and is
   * left unchanged.
   */
  private static final long RETRACK_DETAIL_FALLBACK_BUDGET_MS = 60_000;

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

  /** The last round-trip planning result (carries the planned loop waypoints), or null. */
  public RoundTripResult getLastRoundTripResult() {
    return lastRoundTripResult;
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
