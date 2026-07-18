package btools.router.roundtrip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.Formatter;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.util.CheapRuler;

/**
 * Round-trip track post-processing: spur and back-and-forth removal,
 * micro-detour and teardrop trims, artifact spur spans, origin-chain rebuild,
 * waypoint index bookkeeping, and the adopted-track finalization pipeline.
 * Voice-hint consolidation stays engine-side behind an ops delegate. Reaches
 * the engine only through the {@link RoundTripRequestState}, {@link EngineIO},
 * and {@link EngineContext} roles.
 */
public final class RoundTripTrackCleanup {

  private final WaypointSnapper snapper;
  private final RoundTripRequestState state;
  private final EngineIO io;
  private final EngineContext ctx;

  public RoundTripTrackCleanup(WaypointSnapper snapper, RoundTripRequestState state,
                               EngineIO io, EngineContext ctx) {
    this.snapper = snapper;
    this.state = state;
    this.io = io;
    this.ctx = ctx;
  }

  /**
   * Guarantee the track carries the standard one-line info message and a matching
   * {@code messageList}. Round-trip result tracks are assembled from merged
   * segments via {@code new OsmTrack()} and otherwise reach the formatters with
   * {@code messageList == null}, which made GPX export throw a NullPointerException.
   * Idempotent: re-running keeps {@code messageList.get(0)} in sync with later
   * additions to {@code track.message} (e.g. the AUTO summary).
   */
  public void ensureInfoMessage(OsmTrack track) {
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
   * Bring a directly adopted round-trip track up to the same metadata contract
   * {@code doRouting} provides: matched waypoints attached, indices filled,
   * origin chain coherent for voice hints, speed/profile fields, POIs, and export flags.
   */
  public void finalizeAdoptedRoundTripTrack(OsmTrack track, List<MatchedWaypoint> mwps) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()) return;
    boolean haveMwps = mwps != null && !mwps.isEmpty();
    if (ctx.isRoundTripMode()
        && !ctx.routingContext().allowSamewayback && !ctx.explicitViaRoundTrip()) {
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
        snapper.repairViaPinnedBulges(track, mwps);
        removeArtifactSpurSpans(track, mwps);
      }
    }
    rebuildOriginChain(track);
    ctx.recalcTrack(track);
    ensureInfoMessage(track);
    if (haveMwps) {
      assignMatchedWaypointIndexes(track, mwps);
      state.setMatchedWaypoints(mwps);
      track.setMatchedWaypoints(mwps);
    }
    track.processVoiceHints(ctx.routingContext());
    if (ctx.isRoundTripMode()) {
      io.consolidateRoundTripVoiceHints(track);
    }
    track.prepareSpeedProfile(ctx.routingContext());
    track.showTime = ctx.routingContext().showTime;
    track.params = ctx.routingContext().keyValues;
    if (ctx.routingContext().poipoints != null) {
      track.pois = ctx.routingContext().poipoints;
    }
    track.exportWaypoints = ctx.routingContext().exportWaypoints;
    track.exportCorrectedWaypoints = ctx.routingContext().exportCorrectedWaypoints;
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
   * Remove back-and-forth segments from a round-trip track: at each waypoint the
   * route may retrace the road it arrived on before diverging. Detects the
   * overlap by walking outward from the waypoint in both directions, removes the
   * mirrored spur, and moves the generated waypoint metadata to the surviving
   * branch node.
   */
  public void removeBackAndForthSegments(OsmTrack track, List<MatchedWaypoint> waypoints) {
    removeBackAndForthSegments(track, waypoints, false);
  }

  /**
   * As {@link #removeBackAndForthSegments(OsmTrack, List)}, but when
   * {@code onlyGenerated} is true only engine-generated waypoints
   * ({@link MatchedWaypoint#generated}) are cleaned — used by densified
   * explicit-via routes to strip out-and-back spurs at "dvia" bulges while
   * leaving user vias (and their exact pass-through) untouched.
   */
  public void removeBackAndForthSegments(OsmTrack track, List<MatchedWaypoint> waypoints, boolean onlyGenerated) {
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

        io.logInfo("removeBackAndForth: at waypoint " + waypoints.get(wi).name
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
   * Remove micro-detours: small loops where the route returns to the same area
   * within a short distance. Uses proximity matching (not just node identity) to
   * catch returns through parallel roads or dual carriageways.
   *
   * <p>Spans pinned at a generated round-trip via get an extended cap
   * ({@link WaypointSnapper#VIA_TEARDROP_MAX_ARC_M}, fraction-bounded) but must
   * also be thin ({@link #petalCompactness} ≤ {@link #VIA_TEARDROP_MAX_COMPACTNESS})
   * so genuine scenic petals survive; below {@code maxLoopDistance} behaviour is
   * unchanged.
   *
   * @param maxLoopDistance max route distance (meters) of a loop to count as a micro-detour
   */
  public void removeMicroDetours(OsmTrack track, int maxLoopDistance, List<MatchedWaypoint> waypoints) {
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
      int viaTeardropCap = (int) Math.min(WaypointSnapper.VIA_TEARDROP_MAX_ARC_M,
        WaypointSnapper.VIA_TEARDROP_MAX_ARC_FRAC * totalDist);
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
            io.logInfo("removeMicroDetours: removing " + (i - matchIdx) + " nodes (loop of " + loopDist + "m, crow-fly " + (int) crowFly + "m, ratio " + String.format("%.1f", ratioThreshold) + "x at index " + matchIdx + ")");
            int removeCount = i - matchIdx;
            nodes.subList(matchIdx + 1, i + 1).clear();
            WaypointSnapper.adjustWaypointIndices(waypoints, matchIdx, i, removeCount);
            changed = true;
            break; // restart scan since indices shifted
          }
        }

        // Register this node in the grid (even if it matched — we advanced past the match)
        long cell = ((long) cx) << 32 | (cy & 0xFFFFFFFFL);
        List<Integer> bucket = grid.get(cell);
        if (bucket == null) {
          bucket = new ArrayList<>();
          grid.put(cell, bucket);
        }
        bucket.add(i);
      }
    }
  }

  /**
   * Remove artifact near-revisit spur spans — the residual class every narrower
   * pass misses: start-stem antennas pinned at no via, pinches between
   * removeMicroDetours' 50m and the detector's 60m, arcs above the 4km via-band,
   * and overpriced-but-fat excursions. Spans come from the near-revisit primitive
   * ({@link LoopQualityMetrics#nearRevisitSpans}); a span is an artifact (removed)
   * when it is thin ({@link #petalCompactness} ≤ {@link #SPUR_ARTIFACT_MAX_COMPACTNESS})
   * or rides roads priced ≥ {@link #SPUR_ARTIFACT_MIN_COST_FACTOR}× the track
   * average — a scenic petal is neither.
   *
   * <p>Guards: spans containing a USER via are never touched; removal never takes
   * the loop below {@link #SPUR_REPAIR_MIN_DISTR} of the requested distance (or
   * 90% of current length when unknown); a removal that would create a
   * self-crossing is reverted.
   */
  public void removeArtifactSpurSpans(OsmTrack track, List<MatchedWaypoint> waypoints) {
    List<OsmPathElement> nodes = track.nodes;
    if (nodes == null || nodes.size() < 10) return;
    long totalDist = 0;
    for (int k = 1; k < nodes.size(); k++) {
      totalDist += nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    double expected = roundTripExpectedDistance();
    double minTotal = expected > 0 ? SPUR_REPAIR_MIN_DISTR * expected : 0.9 * totalDist;
    double trackCpm = WaypointSnapper.spanCostPerMeter(nodes, 0, nodes.size() - 1);

    boolean changed = true;
    while (changed) {
      changed = false;
      int maxArc = (int) Math.min(SPUR_REPAIR_MAX_ARC_M, WaypointSnapper.VIA_TEARDROP_MAX_ARC_FRAC * totalDist);
      List<int[]> spans = new ArrayList<>(LoopQualityMetrics.nearRevisitSpans(
        nodes, SPUR_REPAIR_EPS_M, SPUR_REPAIR_MIN_ARC_M, maxArc));
      double[] cum = LoopGeometry.cumulativeDistances(nodes);
      // Largest-arc first: the distance floor caps how much can be removed in
      // total, so spend that budget on the worst offender, not scan order.
      Collections.sort(spans, (a, b) -> Double.compare(cum[b[1]] - cum[b[0]], cum[a[1]] - cum[a[0]]));
      for (int[] s : spans) {
        int i = s[0];
        int j = s[1];
        double arc = cum[j] - cum[i];
        double jump = nodes.get(i).calcDistance(nodes.get(j));
        if (totalDist - (arc - jump) < minTotal) continue;
        if (spanContainsUserWaypoint(waypoints, i, j)) continue;
        boolean thin = petalCompactness(nodes, i, j, arc) <= SPUR_ARTIFACT_MAX_COMPACTNESS;
        boolean overpriced = trackCpm > 0
          && WaypointSnapper.spanCostPerMeter(nodes, i, j) >= SPUR_ARTIFACT_MIN_COST_FACTOR * trackCpm;
        if (!thin && !overpriced) continue; // scenic petal — keep
        int crossingsBefore = RoundTripQualityGate.countSelfIntersections(track);
        List<OsmPathElement> oldInterior = new ArrayList<>(nodes.subList(i + 1, j));
        nodes.subList(i + 1, j).clear();
        if (RoundTripQualityGate.countSelfIntersections(track) > crossingsBefore) {
          nodes.addAll(i + 1, oldInterior);
          continue;
        }
        WaypointSnapper.adjustWaypointIndices(waypoints, i, j - 1, oldInterior.size());
        totalDist -= (long) (arc - jump);
        io.logInfo(String.format(Locale.US,
          "removeArtifactSpurSpans: removed %.0fm spur span [%d..%d] (%s)",
          arc, i, j, thin ? "thin" : "overpriced"));
        changed = true;
        break; // indices shifted — rescan
      }
    }
  }

  /**
   * Relink each node's origin back-pointer to its predecessor in the (possibly
   * edited) nodes list. Required after round-trip node removal because
   * processVoiceHints() walks the origin chain, not the list.
   */
  public static void rebuildOriginChain(OsmTrack track) {
    List<OsmPathElement> nodes = track.nodes;
    for (int i = 0; i < nodes.size(); i++) {
      nodes.get(i).origin = (i == 0) ? null : nodes.get(i - 1);
    }
  }

  /**
   * Shape thinness of {@code nodes[fromIdx..toIdx]} as a closed petal (auto-closed
   * by the {@code toIdx → fromIdx} chord): shoelace area ÷ area of a same-perimeter
   * circle. Self-intersecting (zigzag) spans cancel in the shoelace sum and score
   * near 0 — correctly classified as thin artifacts. Clamped to [0, 1].
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

  /** Requested total loop distance from the request context, 0 when unknown. */
  private double roundTripExpectedDistance() {
    if (ctx.routingContext().roundTripLength != null) {
      return ctx.routingContext().roundTripLength;
    }
    if (ctx.routingContext().roundTripDistance != null && ctx.routingContext().roundTripDistance > 0) {
      return 2 * Math.PI * ctx.routingContext().roundTripDistance; // roundTripDistance is a radius
    }
    return 0;
  }

  /**
   * Whether a loop (matchIdx..currentIdx) is near a generated roundtrip waypoint
   * — flagged {@link MatchedWaypoint#generated} (greedy vias, densification
   * bulges) or named with the WAYPOINT "rt" prefix. User waypoints match neither.
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

  /** Whether the span (i, j) strictly contains a USER waypoint (not planner-generated). */
  private static boolean spanContainsUserWaypoint(List<MatchedWaypoint> waypoints, int i, int j) {
    if (waypoints == null) return false;
    for (int wi = 1; wi < waypoints.size() - 1; wi++) {
      MatchedWaypoint wp = waypoints.get(wi);
      if (!WaypointSnapper.isGeneratedRoundTripWaypoint(wp) && wp.indexInTrack > i && wp.indexInTrack < j) return true;
    }
    return false;
  }

  /**
   * Petal-compactness ceiling for the via-pinned band: fatter spans enclose real
   * area (a scenic petal the rider may want) and are kept; thin excursions (deep,
   * narrow, returning to the pinch point) are artifacts and removed. Out-and-back
   * ~0; 10:1 deep-narrow teardrop ~0.3; round petal 0.7+.
   */
  static final double VIA_TEARDROP_MAX_COMPACTNESS = 0.30;

  /**
   * Artifact-spur repair: compactness at or below this is an artifact by shape.
   * Raised 0.30 → 0.65 (2026-06-10, user calibration): the freiburg 80km N petals
   * (compactness 0.38 and 0.62) are detour loops to the cyclist's eye — riding a
   * circle back to within 60m of an earlier point is an artifact at any ordinary
   * fatness. Only a near-circular sub-loop (above 0.65) survives on shape alone;
   * user vias and the distance floor still protect deliberate excursions.
   */
  private static final double SPUR_ARTIFACT_MAX_COMPACTNESS = 0.65;

  /** Artifact by price: span cost/m at or above this multiple of the track average. */
  private static final double SPUR_ARTIFACT_MIN_COST_FACTOR = 1.3;

  /** Near-revisit parameters (mirror {@link LoopQualityMetrics#computeSpurInfo}). */
  private static final double SPUR_REPAIR_EPS_M = 60.0;

  private static final double SPUR_REPAIR_MIN_ARC_M = 600.0;

  private static final int SPUR_REPAIR_MAX_ARC_M = 6000;

  /** Never repair below this fraction of the requested loop distance. */
  private static final double SPUR_REPAIR_MIN_DISTR = 0.85;
}
