package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Round-trip waypoint snapping, validation, and reachability probing:
 * road/profile-aware via snaps, ferry/hostile usability rules, densified-via
 * arc bulges and their pinned-bulge repair, and the bearing-grid reachability
 * probes that feed ISOCHRONE and FAST placement. Reaches the engine only
 * through the {@link LegRouter}, {@link EngineIO}, and {@link EngineContext}
 * roles.
 */
public final class WaypointSnapper {

  private final LegRouter router;
  private final EngineIO io;
  private final EngineContext ctx;

  public WaypointSnapper(LegRouter router, EngineIO io, EngineContext ctx) {
    this.router = router;
    this.io = io;
    this.ctx = ctx;
  }

  /**
   * Ferry/profile usability of a retained probe match — the same rule every
   * snap-validation site applies (selection owns it; placement re-checks).
   */
  public FastPlacementOps.SnapUsability snapUsability(MatchedWaypoint m) {
    if (!isRoadSnap(m)) {
      return FastPlacementOps.SnapUsability.FERRY_LIKE;
    }
    if (snapCandidateCostFactor(m) > snapRejectCostFactorForProfile()) {
      return FastPlacementOps.SnapUsability.PROFILE_HOSTILE;
    }
    return FastPlacementOps.SnapUsability.OK;
  }

  // A snap whose matched edge is longer than this is ferry-like (sparse nodes
  // spanning km over water), not a road edge — skip it when snapping waypoints.
  private static final int FERRY_LIKE_EDGE_METERS = 1500;

  /**
   * Hard ceiling for the active profile's costfactor at a waypoint snap: a
   * candidate whose BEST road exceeds this is REJECTED entirely — better to drop
   * the waypoint and route around than commit the rider to a profile-hostile
   * road mid-tour (e.g. fastbike snapped to a grade-5 track, costfactor 30).
   *
   * <p>Per-profile: fastbike rejects high-costfactor (≥5) tracks/unpaved; gravel
   * allows moderate roughness; mtb is lenient (trails are its target).
   */
  private static final double SNAP_REJECT_COSTFACTOR_FASTBIKE = 5.0;

  private static final double SNAP_REJECT_COSTFACTOR_GRAVEL = 8.0;

  private static final double SNAP_REJECT_COSTFACTOR_DEFAULT = 10.0;

  /**
   * A snap is usable only if it resolved to a real road edge: both endpoints
   * present and the matched edge short enough to be a road, not a ferry-like
   * span (see {@link #FERRY_LIKE_EDGE_METERS}).
   */
  private static boolean isRoadSnap(MatchedWaypoint m) {
    return m.crosspoint != null && m.node1 != null && m.node2 != null
      && m.node1.calcDistance(m.node2) <= FERRY_LIKE_EDGE_METERS;
  }

  /**
   * Shared batch road-snap primitive: reset the node cache, wrap each point in a
   * {@link MatchedWaypoint}, and run the matcher once. Returns the matched list
   * (same size/order as {@code points}), or {@code null} if the matcher threw
   * (callers treat that as "nothing matched"). {@link #snapWaypointsToRoad} keeps
   * its own copy because it also mutates the input waypoints and returns
   * per-point booleans.
   */
  private List<MatchedWaypoint> batchMatchToRoads(List<OsmNode> points, double maxSnapDist, String nameTag) {
    router.resetCache(false);
    List<MatchedWaypoint> mwps = new ArrayList<>(points.size());
    for (OsmNode p : points) {
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(p.ilon, p.ilat);
      mwp.name = nameTag;
      mwps.add(mwp);
    }
    try {
      router.matchWaypointsToNodes(mwps, maxSnapDist);
    } catch (Exception e) {
      return null;
    }
    return mwps;
  }

  /**
   * Profile-aware point match for planner-generated round-trip vias. Matches the
   * plain nearest road first; only when that snap is profile-hostile
   * ({@link #VIA_SNAP_HOSTILE_COSTFACTOR}) does it evaluate probe rings
   * ({@link #VIA_SNAP_PROBE_RINGS}) and move the via to the best-scoring road
   * ({@code costFactor*1000 + distance}), and only if that beats the original by
   * {@link #VIA_SNAP_MIN_IMPROVEMENT}. Returns null when nothing matches.
   */
  public MatchedWaypoint profileAwareMatchPoint(int ilon, int ilat, String name, double maxSnapDist) {
    // Loop-scale relocation bound (see VIA_RELOCATION_LOOP_FRACTION). When no
    // round-trip scale is known (0), fall back to the absolute maxSnapDist.
    double relocationCap = ctx.roundTripSearchRadius() > 0
      ? Math.min(maxSnapDist, VIA_RELOCATION_LOOP_FRACTION * ctx.roundTripSearchRadius())
      : maxSnapDist;
    OsmNode orig = new OsmNode(ilon, ilat);
    // Match the plain point FIRST and probe the rings only when it is
    // actually hostile. The rings exist purely for the hostile-snap escape;
    // matching all 17 points up front paid ~16 extra road matches per routed
    // candidate (~240 candidate snaps per plan) on the common non-hostile
    // path. Skipping the orig entry in the ring loop below is
    // behavior-identical: its score (origCf*1000 + snapDist) can never beat
    // the incumbent-initialized bestScore of origCf*1000.
    List<OsmNode> plainPoint = new ArrayList<>(1);
    plainPoint.add(new OsmNode(ilon, ilat));
    List<MatchedWaypoint> plainMatch = batchMatchToRoads(plainPoint, maxSnapDist, name);
    if (plainMatch == null) return null;

    MatchedWaypoint origMatch = isRoadSnap(plainMatch.get(0)) ? plainMatch.get(0) : null;
    double origCf = origMatch != null ? snapCandidateCostFactor(origMatch) : Double.MAX_VALUE;

    MatchedWaypoint best = origMatch;
    double bestCf = origCf;
    if (origMatch == null || origCf >= VIA_SNAP_HOSTILE_COSTFACTOR) {
      List<OsmNode> points = new ArrayList<>();
      for (double ring : VIA_SNAP_PROBE_RINGS) {
        if (ring > relocationCap) continue; // ring would only yield over-cap candidates
        for (double bearing = 0; bearing < 360; bearing += 45) {
          int[] p = CheapRuler.destination(ilon, ilat, ring, bearing);
          points.add(new OsmNode(p[0], p[1]));
        }
      }
      List<MatchedWaypoint> mwps = points.isEmpty()
        ? new ArrayList<>() : batchMatchToRoads(points, maxSnapDist, name);
      if (mwps == null) mwps = new ArrayList<>(); // probe failure: keep the plain match
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
   * Drop intermediate round-trip waypoints ("rt*") that snapped badly: snap
   * distance beyond maxSnapDistance, or matched positions too close together.
   * Never removes the first/last waypoint; always keeps at least one intermediate.
   */
  public void filterRoundTripWaypoints(List<MatchedWaypoint> waypoints) {
    double maxSnapDistance = ctx.roundTripSearchRadius() * 0.5;
    double minWaypointDistance = ctx.roundTripSearchRadius() / 10.0;
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
          io.logInfo("filterRoundTrip: removing " + mwp.name + " matched to long segment (" + segLen + "m, likely ferry)");
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
        io.logInfo("filterRoundTrip: removing " + mwp.name + " snap=" + (int) mwp.radius + "m > max=" + (int) maxSnapDistance + "m");
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
        io.logInfo("filterRoundTrip: removing " + curr.name + " too close to " + prev.name + " dist=" + (int) dist + "m");
        waypoints.remove(i);
        rtCount--;
      }
    }
  }

  /**
   * Snap intermediate roundtrip waypoints ("rt*") from a mid-edge crosspoint to
   * the closer of node1/node2 (a real intersection). Routing to a mid-edge point
   * can create small out-and-back tails; snapping to the junction avoids them.
   * User waypoints and the start/end points are left alone.
   */
  public void snapToIntersection(List<MatchedWaypoint> waypoints) {
    for (int i = 1; i < waypoints.size() - 1; i++) {
      MatchedWaypoint mwp = waypoints.get(i);
      if (mwp.name == null || !mwp.name.startsWith("rt")) continue;
      if (mwp.node1 == null || mwp.node2 == null || mwp.crosspoint == null) continue;

      int distToNode1 = mwp.crosspoint.calcDistance(mwp.node1);
      int distToNode2 = mwp.crosspoint.calcDistance(mwp.node2);
      OsmNode closerNode = distToNode1 <= distToNode2 ? mwp.node1 : mwp.node2;

      io.logInfo("snapToIntersection: " + mwp.name + " moved crosspoint "
        + (distToNode1 <= distToNode2 ? distToNode1 : distToNode2) + "m to nearest intersection");
      mwp.crosspoint = new OsmNode(closerNode.ilon, closerNode.ilat);
    }
  }

  /**
   * Snap a single waypoint to the nearest road within {@code maxSnapDist}.
   * Returns true and rewrites the waypoint to the matched crosspoint on success;
   * false leaves it untouched. Keeps generated points within the final
   * matchWaypointsToNodes 250m catching range so it doesn't fall back to a beeline.
   */
  public boolean snapWaypointToRoad(OsmNodeNamed wp, double maxSnapDist, String logTag) {
    return snapWaypointsToRoad(Collections.singletonList(wp), maxSnapDist, logTag).get(0);
  }

  /**
   * Per-profile snap-rejection threshold from the {@code SNAP_REJECT_COSTFACTOR_*}
   * constants, resolved by profile filename (so a fastbike user isn't snapped to
   * a grade-5 track after a different profile ran earlier on the same JVM).
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
    String path = ctx.routingContext() == null ? null : ctx.routingContext().localFunction;
    if (path == null) return null;
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    int dot = path.lastIndexOf('.');
    int start = slash + 1;
    int end = dot > start ? dot : path.length();
    return path.substring(start, end);
  }

  /**
   * Active profile's costfactor for the road a candidate matched against (used by
   * via-snapping to prefer profile-liked roads). Returns neutral {@code 1.0f} on
   * any failure to resolve the tags. The tags come from {@code mwp.wayDescription},
   * captured by the matcher at match time; it is null only when the match carried
   * no way tags.
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
      ctx.routingContext().expctxWay.evaluate(false, mwp.wayDescription);
      return ctx.routingContext().expctxWay.getCostfactor();
    } catch (RuntimeException ignored) {
      return 1.0f;
    }
  }

  /**
   * One probe skeleton for both grids — the legacy 24-bearing × 3-radii sweep
   * and the optimized FAST 2-radii grid — so fixes to the snap rule, matcher
   * error handling, or start-probe convention cannot drift between the two paths.
   */
  private ProbeResult probeDirections(OsmNodeNamed start, double searchRadius,
                                      double[] bearings, double[] distFactors,
                                      boolean retainMatches, String logLabel) {
    router.resetCache(false);
    double maxSnapDist = Math.min(searchRadius * 0.3, 2000);
    int probesPerDirection = distFactors.length;

    List<MatchedWaypoint> allProbes = new ArrayList<>();
    // Include the start point itself to ensure its segment is loaded.
    MatchedWaypoint startProbe = new MatchedWaypoint();
    startProbe.waypoint = new OsmNode(start.ilon, start.ilat);
    startProbe.name = "probe_start";
    allProbes.add(startProbe);

    for (int d = 0; d < bearings.length; d++) {
      for (double df : distFactors) {
        int[] pos = CheapRuler.destination(start.ilon, start.ilat, searchRadius * df, bearings[d]);
        MatchedWaypoint mwp = new MatchedWaypoint();
        mwp.waypoint = new OsmNode(pos[0], pos[1]);
        mwp.name = "probe_" + d + "_" + (int) (df * 100);
        allProbes.add(mwp);
      }
    }

    try {
      router.matchWaypointsToNodes(allProbes, maxSnapDist);
    } catch (Exception e) {
      io.logInfo("reachability probe failed: " + e.getMessage());
      return null;
    }

    int probeOffset = 1; // start probe is at index 0
    double[] viable = new double[bearings.length];
    int viableCount = 0;
    List<ProbeDirection> scored = new ArrayList<>();
    double snapRejectThreshold = retainMatches
      ? snapRejectCostFactorForProfile() : Double.POSITIVE_INFINITY;
    for (int d = 0; d < bearings.length; d++) {
      ProbeDirection pd = selectProbeDirection(bearings[d], allProbes,
        probeOffset + d * probesPerDirection, probesPerDirection,
        retainMatches, maxSnapDist, snapRejectThreshold);
      if (pd == null) continue;
      viable[viableCount++] = bearings[d];
      scored.add(pd);
    }

    io.logInfo(logLabel + ": " + viableCount + "/" + bearings.length + " bearings snapped");
    if (viableCount == 0) return null;
    // allProbes.get(0) is the matched start (has node1/node2/crosspoint when
    // the start is on a road) — used by the islanded-via guard.
    return new ProbeResult(Arrays.copyOf(viable, viableCount), scored,
      retainMatches ? allProbes.get(0) : null);
  }

  /**
   * Summarize one bearing's probe matches. The FAST path counts and retains only
   * road/profile-compatible matches, so a rejected nearer edge can't hide a
   * usable match at the other radius. The legacy probe keeps its raw-snap count
   * and retains no match.
   */
  ProbeDirection selectProbeDirection(double direction, List<MatchedWaypoint> probes,
                                      int firstIndex, int count, boolean retainMatches,
                                      double maxSnapDist, double snapRejectThreshold) {
    int successCount = 0;
    MatchedWaypoint best = null;
    for (int i = firstIndex; i < firstIndex + count; i++) {
      MatchedWaypoint mwp = probes.get(i);
      if (mwp.crosspoint == null || mwp.radius > maxSnapDist) continue;
      if (retainMatches && (!isRoadSnap(mwp)
          || snapCandidateCostFactor(mwp) > snapRejectThreshold)) {
        continue;
      }
      successCount++;
      if (retainMatches && (best == null || mwp.radius < best.radius)) {
        best = mwp;
      }
    }
    return successCount == 0 ? null
      : new ProbeDirection(direction, successCount, best);
  }

  /**
   * FAST-tier reachability probe: 12 bearings × 2 radii (vs the legacy 24 × 3),
   * retaining the best road match per direction so
   * {@code FastWaypointPlanner#placeWaypointsFromProbeMatches} can reuse those
   * snapped nodes as vias instead of re-matching in {@link #validateAndAdjustWaypoints}.
   */
  public ProbeResult probeReachableDirectionsFast(OsmNodeNamed start, double searchRadius, double[] bearings) {
    // 2 radii per bearing (vs the legacy 3) + best-match retention so
    // placeWaypointsFromProbeMatches can reuse the snapped nodes as vias.
    return probeDirections(start, searchRadius, bearings,
      new double[]{0.85, 1.15}, true, "reachability probe (fast)");
  }

  public ProbeResult probeReachableDirections(OsmNodeNamed start, double searchRadius) {
    // Legacy sweep: 24 bearings every 15°, radii {0.7, 1.0, 1.3}·R — the
    // ISOCHRONE path and the -Droundtrip.fast.optimized=false A/B baseline.
    return probeDirections(start, searchRadius, PlacementGeometry.fullCircleBearings(24),
      new double[]{0.7, 1.0, 1.3}, false, "reachability probe");
  }

  /**
   * Snap the start (and its mirrored closing waypoint) to the nearest road. A
   * raw click far from any road (beyond the 250m catching range) would make the
   * engine insert a straight-line beeline instead of a road segment.
   */
  public void snapStartToRoad(List<OsmNodeNamed> waypoints, double searchRadius) {
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
   * Batch variant of {@link #snapWaypointToRoad}: one cache reset and one match
   * call for the whole list (avoids per-waypoint cache reallocation). Returns a
   * parallel boolean list of which waypoints matched.
   */
  public List<Boolean> snapWaypointsToRoad(List<OsmNodeNamed> wps, double maxSnapDist, String logTag) {
    router.resetCache(false);
    List<MatchedWaypoint> mwpList = new ArrayList<>(wps.size());
    for (OsmNodeNamed wp : wps) {
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(wp.ilon, wp.ilat);
      mwp.name = (wp.name == null ? "wp" : wp.name) + "_snap";
      mwpList.add(mwp);
    }
    try {
      router.matchWaypointsToNodes(mwpList, maxSnapDist);
    } catch (Exception e) {
      io.logInfo(logTag + ": match failed, leaving " + wps.size() + " waypoint(s) unsnapped: " + e.getMessage());
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
        io.logInfo(logTag + ": moved " + (wp.name == null ? "wp" : wp.name) + " "
          + snapDist + "m to nearest road");
        wp.ilon = mwp.crosspoint.getILon();
        wp.ilat = mwp.crosspoint.getILat();
      }
      matched.add(true);
    }
    return matched;
  }

  /**
   * Detect and repair wide-mouth bulges pinned at generated round-trip vias.
   *
   * <p>The planner can place a via in a pocket whose only escape is over
   * profile-penalized roads (Basel: via on a grade2 track paying ~11× the
   * through cost). Such a detour has no ≤50m pinch point (so removeMicroDetours
   * misses it) and encloses real area (so a thin-only filter can't catch it);
   * what marks it is price — the span rides roads at ≥{@link #BULGE_MIN_COST_FACTOR}×
   * the track's average cost/m.
   *
   * <p>The wide mouth can't just be deleted (that leaves an off-road beeline),
   * so it is re-routed via a local {@code findTrack} between the two mouth nodes,
   * accepted only when the connector meaningfully shortens the span
   * ({@link #BULGE_CONNECTOR_MAX_ARC_FRACTION}, {@link #BULGE_MIN_SAVED_M}) and
   * undercuts its cost/m ({@link #BULGE_CONNECTOR_COST_ADVANTAGE}); otherwise the
   * bulge was network-forced and stays.
   */
  public void repairViaPinnedBulges(OsmTrack track, List<MatchedWaypoint> waypoints) {
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
        io.logInfo("repairViaPinnedBulges: " + via.name + " span " + span[0] + "-" + span[1]
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
        io.logInfo("repairViaPinnedBulges: " + via.name + " mouth snap failed");
        continue;
      }

      OsmTrack connector = null;
      try {
        connector = router.findTrackUnguided("bulge-repair", mouth.get(0), mouth.get(1));
      } catch (RuntimeException e) {
        io.logInfo("repairViaPinnedBulges: connector routing failed (" + e.getMessage() + ")");
      }
      if (connector == null || connector.nodes == null || connector.nodes.size() < 2) {
        io.logInfo("repairViaPinnedBulges: " + via.name + " no connector route");
        continue;
      }
      double connCpm = connector.distance > 0
        ? (double) connector.cost / connector.distance : Double.MAX_VALUE;
      if (connector.distance > arc * BULGE_CONNECTOR_MAX_ARC_FRACTION
          || arc - connector.distance < BULGE_MIN_SAVED_M
          || connCpm * BULGE_CONNECTOR_COST_ADVANTAGE > spanCpm) {
        io.logInfo(String.format(Locale.US,
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
        io.logInfo("repairViaPinnedBulges: " + via.name + " connector rejected (would add "
          + (crossingsAfter - crossingsBefore) + " self-crossing(s))");
        continue;
      }
      adjustWaypointIndices(waypoints, span[0], span[1] - 1, removedNodes - interior.size());

      io.logInfo(String.format(Locale.US,
        "repairViaPinnedBulges: at %s replaced %.0fm bulge (mouth %.0fm, span %.2f cost/m vs track %.2f) with %dm connector (%.2f cost/m)",
        via.name, arc, crowFly, spanCpm, trackCostPerM, connector.distance, connCpm));
    }
  }

  /**
   * Validate generated round-trip waypoints against segment data before routing.
   * Waypoints in unreachable areas (water, no profile-compatible ways) are
   * relocated by trying alternative angles/distances from the start; unmatched
   * ones are removed while enough remain. Matching is profile-aware (only ways
   * with accessType >= 2 are considered).
   */
  public void validateAndAdjustWaypoints(List<OsmNodeNamed> waypoints, double searchRadius) {
    router.resetCache(false);
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
      router.matchWaypointsToNodes(allCandidates, maxSnapDist);
    } catch (Exception e) {
      io.logInfo("validateAndAdjustWaypoints: candidate match failed ("
        + e.getClass().getSimpleName() + "), keeping generated waypoint positions");
      return;
    }

    // Pick the best candidate for each waypoint or remove it.
    // Use direction-aware scoring: prefer roads perpendicular to the travel
    // direction (the route naturally crosses them) over parallel roads
    // (which often require a detour to reach).
    int minWaypoints = PlacementGeometry.MIN_ROUNDTRIP_VIAS;
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
        io.logInfo("validateWaypoints: rejecting profile-hostile snap for "
          + waypoints.get(i).name + " (costfactor=" + String.format("%.1f", bestCostFactor)
          + " > " + snapRejectThreshold + " for " + profileNameForLog() + ")");
        best = null;
      }

      OsmNodeNamed wp = waypoints.get(i);
      if (best != null && best.radius <= maxSnapDist) {
        // Use crosspoint, not waypoint: keeps the point within the 250m
        // catching range used by final matchWaypointsToNodes (avoids beeline).
        if (wp.ilon != best.crosspoint.getILon() || wp.ilat != best.crosspoint.getILat()) {
          io.logInfo("validateWaypoints: relocated " + wp.name + " snap=" + (int) best.radius + "m");
          wp.ilon = best.crosspoint.getILon();
          wp.ilat = best.crosspoint.getILat();
        }
      } else if (remaining > minWaypoints) {
        io.logInfo("validateWaypoints: removing unreachable " + wp.name
          + " (best=" + (best == null ? "none" : (int) best.radius + "m") + ")");
        waypoints.remove(i);
        remaining--;
      } else {
        io.logInfo("validateWaypoints: keeping marginal " + wp.name + " (min waypoint count reached)");
      }
    }
    io.logInfo("validateWaypoints: " + remaining + "/" + intermediateCount + " waypoints validated");
  }

  /**
   * Relocation triggers only when the plain nearest snap is at least this
   * profile-hostile. Below it the original snap is fine and its cached
   * graph-native leg stays valid — relocating on marginal wins (cf 1.2 → 1.1)
   * fired on most candidates, paying an extra leg Dijkstra each (~6× shard
   * runtime) for no artifact prevented.
   */
  private static final double VIA_SNAP_HOSTILE_COSTFACTOR = 2.0;

  /**
   * Loop-scale relocation bound: a via may move at most this fraction of the
   * round-trip search radius from the planner's intended position. The probe
   * rings ({@link #VIA_SNAP_PROBE_RINGS}) and {@code maxSnapDist} are absolute,
   * so without this a small loop can have a via pulled sideways by ~its whole
   * radius (measured on r=1000m: the GREEDY dir90 loop flipped clean →
   * 19%-retraced OUT_AND_BACK). At production radii (≥8000m) the cap reaches
   * {@code maxSnapDist} and behaviour is unchanged.
   */
  private static final double VIA_RELOCATION_LOOP_FRACTION = 0.25;

  /**
   * Weight on the profile costfactor in waypoint-snap scoring, combined with the
   * geometric score as {@code geom × (1 + W × (costfactor - 1))}.
   *
   * <p>Calibration with W=0.5:
   * <ul>
   *   <li>50m grade5 track (fastbike costfactor 30): 50 × (1 + 0.5 × 29) = 775,
   *       loses to a 200m tertiary (score 200) — correct.</li>
   *   <li>50m cycleway (costfactor 1.3): 50 × 1.15 = 57.5, still beats a 200m
   *       tertiary — correct: a short snap to a cyclist-preferred road wins.</li>
   * </ul>
   * Falls back to 1.0 (no penalty) when the link can't be resolved.
   */
  private static final double SNAP_PROFILE_COST_WEIGHT = 0.5;

  /**
   * Probe-ring radii for {@link #profileAwareMatchPoint}: the inner ring covers
   * "good road just past the nearest track", the outer covers pocket escapes
   * (Basel: via 750m from the parallel asphalt cycle route, only grade2 track between).
   */
  private static final double[] VIA_SNAP_PROBE_RINGS = {300, 700};

  /** ... and only when the alternative is substantially better than the
   *  original (multiplicative improvement), not a lateral move. */
  private static final double VIA_SNAP_MIN_IMPROVEMENT = 0.6;

  /**
   * Junk filter: the span's average cost/m must be at least this multiple of the
   * track's average. A scenic petal rides roads the profile likes (span cost/m ≈
   * track average) and is kept; only overpriced detours qualify. Deliberately
   * loose (the Basel reference span measures 1.38× because {@link #spanCostPerMeter}
   * skips the leg-reset edge) — the connector cost-advantage check is decisive.
   */
  private static final double BULGE_MIN_COST_FACTOR = 1.2;

  /**
   * Repair acceptance: the connector must be at most this fraction of the bulge
   * arc. An efficiency criterion, not a straightness one — a winding connector
   * on profile-friendly roads still beats a junk-road bulge (Basel via3: 839m at
   * 1.44 cost/m vs 3231m span at 3.92). A connector comparable to the bulge
   * itself means it was network-forced, not via-pinned — keep it.
   */
  private static final double BULGE_CONNECTOR_MAX_ARC_FRACTION = 0.6;

  /** Repair acceptance: minimum arc length the splice must save. */
  private static final int BULGE_MIN_SAVED_M = 400;

  /** Repair acceptance: connector cost/m × this must still undercut the span's
   *  cost/m, so the repair is a genuine quality win, not a lateral move. */
  private static final double BULGE_CONNECTOR_COST_ADVANTAGE = 1.3;

  /**
   * Via-pinned teardrop band: a detour pinned at a generated via (the anti-reuse
   * penalty shapes the pocket escape into a thin offset loop the symmetric spur
   * remover can't strip) may be removed up to this arc length — well beyond the
   * plain micro-detour cap.
   */
  public static final int VIA_TEARDROP_MAX_ARC_M = 4000;

  /** ... bounded relative to the whole track, so short loops never lose a large share. */
  public static final double VIA_TEARDROP_MAX_ARC_FRAC = 0.15;

  /**
   * Profile-aware start snap for explicit-via round trips. The plain nearest-road
   * snap ({@link #snapWaypointToRoad}) can match a track right next to a paved
   * road (starting a road bike on unpaved). This evaluates the original click
   * plus a ring of nearby positions and moves the start to the most
   * profile-compatible road (lowest cost-factor, tie-broken by proximity), within
   * {@code maxSnapDist}; the original is always a candidate, so it never moves
   * without a clearly better road nearby.
   */
  public void snapStartProfileAware(OsmNodeNamed start, double maxSnapDist) {
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
        io.logInfo("snapStart: profile-aware start snap (" + moved + "m, costfactor "
          + String.format("%.1f", bestCostFactor) + ")");
      }
      start.ilon = best.crosspoint.getILon();
      start.ilat = best.crosspoint.getILat();
    }
  }

  /**
   * Whether this waypoint is engine-generated: greedy planner vias carry the
   * {@code generated} flag, the WAYPOINT algorithm's points use the "rt" name
   * prefix. User waypoints match neither and must never be repaired away.
   */
  public static boolean isGeneratedRoundTripWaypoint(MatchedWaypoint wp) {
    return wp.generated || (wp.name != null && wp.name.startsWith("rt"));
  }

  /**
   * Adjust waypoint track indices after removing a node range: waypoints past the
   * range shift back by {@code removeCount}; waypoints inside it clamp to rangeStart.
   */
  public static void adjustWaypointIndices(List<MatchedWaypoint> waypoints, int rangeStart, int rangeEnd, int removeCount) {
    for (MatchedWaypoint mwp : waypoints) {
      if (mwp.indexInTrack > rangeEnd) {
        mwp.indexInTrack -= removeCount;
      } else if (mwp.indexInTrack > rangeStart) {
        mwp.indexInTrack = rangeStart;
      }
    }
  }

  /**
   * Find a low-progress span pinned at a generated via: the pair (i, j) with
   * i &lt; viaIdx &lt; j maximizing {@code arc - BULGE_MIN_PROGRESS_RATIO * crowFly},
   * subject to {@code BULGE_MIN_ARC_M ≤ arc ≤ maxArcM}. Needs no ≤50m pinch point
   * (unlike removeMicroDetours), so it catches wide-mouth bulges whose legs never
   * come close (parallel field lanes 400m apart). Returns {@code {i, j}} or null.
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
   * Average cost per meter over a node span. Node costs are cumulative per routed
   * leg and reset to 0 at leg joins; a negative delta marks such a reset and that
   * edge's cost is skipped (slightly underestimates — conservative for the junk
   * filter, which needs the span to be expensive).
   */
  public static double spanCostPerMeter(List<OsmPathElement> nodes, int fromIdx, int toIdx) {
    double cost = 0;
    double dist = 0;
    for (int k = fromIdx + 1; k <= toIdx; k++) {
      int dc = nodes.get(k).cost - nodes.get(k - 1).cost;
      if (dc > 0) cost += dc;
      dist += nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    return dist > 0 ? cost / dist : Double.MAX_VALUE;
  }

  /** Spans shorter than this are left to removeMicroDetours' teardrop band. */
  private static final int BULGE_MIN_ARC_M = 600;

  /**
   * Geometric trigger for a via-pinned bulge: the span's route arc must be at
   * least this multiple of the mouth's crow-fly distance. The Basel artifact
   * (1.85km of grade2 track for 410m of progress) measures 4.5; 3.0 keeps margin
   * against firing on ordinary loop curvature near a via.
   */
  private static final double BULGE_MIN_PROGRESS_RATIO = 3.0;
}
