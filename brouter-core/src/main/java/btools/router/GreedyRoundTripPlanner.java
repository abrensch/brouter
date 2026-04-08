package btools.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Greedy sub-route algorithm for cycling round-trip generation.
 * <p>
 * Follows the pattern from "Efficient Dijkstra-Based Greedy Algorithm for
 * Cycle-Route Planning" (CEUR-WS Vol-3885):
 * <ol>
 *   <li>Generate candidate waypoints at the target sub-route distance</li>
 *   <li>Score ALL candidates by air-distance heuristics (O(1) each, no routing)</li>
 *   <li>Rank candidates; route only the top pick via full Dijkstra</li>
 *   <li>If route fails, try the next-ranked candidate</li>
 *   <li>Compute ONE return path to start; check if loop closes within tolerance</li>
 *   <li>Repeat until loop closes or max steps exhausted</li>
 * </ol>
 * This gives 1-2 Dijkstra per step (sub-route + return) instead of N per step,
 * making the algorithm practical for real-time use.
 */
public class GreedyRoundTripPlanner {

  private static final int DEFAULT_SUB_ROUTE_COUNT = 5;
  private static final double DEFAULT_TOLERANCE = 0.05;
  private static final int DEFAULT_MAX_ATTEMPTS = 8;
  private static final int CANDIDATE_DIRECTIONS = 12;
  private static final double ROAD_INDIRECTNESS = 1.3;
  private static final long SUB_ROUTE_TIMEOUT_MS = 10000;
  // Max candidates to actually route if higher-ranked ones fail
  private static final int MAX_ROUTE_ATTEMPTS = 3;

  private final RoutingEngine engine;
  private final CandidateScorer scorer;

  private final int subRouteCount;
  private final double tolerance;
  private final int maxAttempts;

  public GreedyRoundTripPlanner(RoutingEngine engine) {
    this(engine, new CandidateScorer(), DEFAULT_SUB_ROUTE_COUNT, DEFAULT_TOLERANCE, DEFAULT_MAX_ATTEMPTS);
  }

  public GreedyRoundTripPlanner(RoutingEngine engine, CandidateScorer scorer,
                                int subRouteCount, double tolerance, int maxAttempts) {
    this.engine = engine;
    this.scorer = scorer;
    this.subRouteCount = subRouteCount;
    this.tolerance = tolerance;
    this.maxAttempts = maxAttempts;
  }

  /**
   * Plan a greedy round-trip loop.
   */
  public RoundTripResult plan(OsmNodeNamed start, double desiredDistance, double startDirection) {
    RoundTripResult result = new RoundTripResult();
    double subTarget = desiredDistance / subRouteCount;
    Map<Long, Integer> visitedEdgeCounts = new HashMap<>();
    List<OsmTrack> segments = new ArrayList<>();
    int totalAttempts = 0;
    double totalDistance = 0;

    MatchedWaypoint startMwp = matchPoint(start.ilon, start.ilat, "greedy_start");
    if (startMwp == null) {
      result.setFallbackReason("start point not on road network");
      return result;
    }

    MatchedWaypoint currentMwp = startMwp;
    List<MatchedWaypoint> waypointStack = new ArrayList<>();
    waypointStack.add(startMwp);

    OsmTrack bestFallbackTrack = null;
    double bestFallbackError = Double.MAX_VALUE;

    DirectionPreference dirPref = DirectionPreference.ANY;
    if (startDirection >= 0) {
      dirPref = nearestDirectionPreference(startDirection);
    }

    double searchRadius = desiredDistance / 4.0;

    for (int step = 1; step <= subRouteCount; step++) {
      boolean candidateFound = false;
      double localRadius = subTarget;
      int currentIlon = currentMwp.crosspoint.getILon();
      int currentIlat = currentMwp.crosspoint.getILat();

      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        totalAttempts++;

        double airRadius = localRadius / ROAD_INDIRECTNESS;

        // --- Phase 1: Generate candidates and score by heuristics (no routing) ---
        List<CandidatePoint> candidates = generateCandidatePoints(
          currentIlon, currentIlat, airRadius, step, startDirection);

        // Score using air-distance estimates — O(1) per candidate
        for (CandidatePoint cp : candidates) {
          double airDistToCp = CheapRuler.distance(currentIlon, currentIlat, cp.ilon, cp.ilat);
          double estimatedRouteDist = airDistToCp * ROAD_INDIRECTNESS;
          double airDistToStart = CheapRuler.distance(cp.ilon, cp.ilat, start.ilon, start.ilat);
          double estimatedReturn = airDistToStart * ROAD_INDIRECTNESS;
          double distFromStart = airDistToStart;

          cp.score = scorer.score(
            estimatedRouteDist, subTarget,
            totalDistance, estimatedReturn, desiredDistance,
            cp.bearing, dirPref,
            step, subRouteCount,
            0.0, // can't estimate visited ratio without routing
            distFromStart, searchRadius);
        }

        // Rank by score (lowest = best)
        candidates.sort(Comparator.comparingDouble(c -> c.score));

        // --- Phase 2: Route only top candidates until one succeeds ---
        ScoredRoute accepted = null;
        int routeAttempts = Math.min(MAX_ROUTE_ATTEMPTS, candidates.size());

        for (int r = 0; r < routeAttempts; r++) {
          CandidatePoint cp = candidates.get(r);

          // Match from + to and route
          MatchedWaypoint fromMwp = matchPoint(currentIlon, currentIlat, "greedy_from");
          MatchedWaypoint toMwp = matchPoint(cp.ilon, cp.ilat, "greedy_to");
          if (fromMwp == null || toMwp == null) continue;

          // Reject candidates that snapped too far from the geometric point.
          // This catches points over water/restricted areas that snap to distant ferries.
          double snapDist = CheapRuler.distance(cp.ilon, cp.ilat,
            toMwp.crosspoint.getILon(), toMwp.crosspoint.getILat());
          if (snapDist > airRadius * 0.5) continue;

          // Pass visited edges as refTrack so the router applies a 2x cost penalty
          // on already-traversed roads, naturally preferring fresh roads where available.
          OsmTrack subTrack = timedFindTrack("greedy-sub", fromMwp, toMwp,
            segments.isEmpty() ? null : buildRefTrack(segments));
          if (subTrack == null || subTrack.distance == 0) continue;

          // Reject routes that are wildly longer than the air-distance estimate.
          // This catches ferry routes and extreme detours through impassable terrain.
          double airDist = CheapRuler.distance(currentIlon, currentIlat, cp.ilon, cp.ilat);
          if (subTrack.distance > airDist * 3.0) continue;

          accepted = new ScoredRoute();
          accepted.track = subTrack;
          accepted.toMwp = toMwp;
          accepted.routeDistance = subTrack.distance;
          accepted.heuristicScore = cp.score;
          accepted.visitedRatio = computeTrackVisitedRatio(subTrack, visitedEdgeCounts);
          break;
        }

        if (accepted == null) {
          result.addDiagnostic("step " + step + " attempt " + attempt
            + ": no routable candidate at radius " + (int) localRadius);
          localRadius /= 2;
          continue;
        }

        result.addDiagnostic("step " + step + ": routed " + (int) accepted.routeDistance
          + "m (target " + (int) subTarget + "m)"
          + ", reuse=" + String.format("%.1f%%", accepted.visitedRatio * 100));

        // --- Phase 3: Accept sub-route, advance position ---
        addVisitedEdges(accepted.track, visitedEdgeCounts);
        segments.add(accepted.track);
        totalDistance += accepted.routeDistance;

        // Use actual track endpoint for next step
        OsmPathElement lastNode = accepted.track.nodes.get(accepted.track.nodes.size() - 1);
        MatchedWaypoint nextMwp = matchPoint(lastNode.getILon(), lastNode.getILat(), "greedy_next");
        currentMwp = (nextMwp != null) ? nextMwp : accepted.toMwp;
        waypointStack.add(currentMwp);

        // --- Phase 4: Check loop closure (ONE return Dijkstra per step) ---
        int curIlon = currentMwp.crosspoint.getILon();
        int curIlat = currentMwp.crosspoint.getILat();
        double airDistToStart = CheapRuler.distance(curIlon, curIlat, start.ilon, start.ilat);
        double minReturn = airDistToStart * ROAD_INDIRECTNESS;

        // Skip return check if closure is mathematically impossible
        if (totalDistance + minReturn < desiredDistance * (1 - tolerance)) {
          candidateFound = true;
          break;
        }

        // One Dijkstra: return path to start
        MatchedWaypoint returnFrom = matchPoint(curIlon, curIlat, "greedy_return_from");
        MatchedWaypoint returnTo = matchPoint(start.ilon, start.ilat, "greedy_return_to");
        if (returnFrom != null && returnTo != null) {
          OsmTrack returnTrack = timedFindTrack("greedy-return", returnFrom, returnTo,
            buildRefTrack(segments));
          if (returnTrack != null && returnTrack.distance > 0) {
            double closedDistance = totalDistance + returnTrack.distance;
            double error = Math.abs(closedDistance - desiredDistance) / desiredDistance;

            // Track best fallback
            if (error < bestFallbackError) {
              bestFallbackError = error;
              bestFallbackTrack = mergeSegments(segments, returnTrack);
            }

            // Within tolerance → close the loop
            if (error <= tolerance) {
              addVisitedEdges(returnTrack, visitedEdgeCounts);
              segments.add(returnTrack);
              OsmTrack finalTrack = mergeSegments(segments, null);
              result.setTrack(finalTrack);
              result.setTotalDistanceMeters((int) closedDistance);
              result.setWithinTolerance(true);
              result.setSubRoutesChosen(step);
              result.setAttemptsUsed(totalAttempts);
              result.setReusedEdgeRatio(computeReusedEdgeRatio(finalTrack));
              result.addDiagnostic("loop closed at step " + step
                + ", total=" + (int) closedDistance + "m"
                + ", error=" + String.format("%.1f%%", error * 100));
              return result;
            }

            // Too long → undo sub-route, shrink radius, retry
            if (closedDistance > desiredDistance * (1 + tolerance)) {
              result.addDiagnostic("step " + step + ": projected " + (int) closedDistance
                + "m exceeds desired " + (int) desiredDistance + "m, halving radius");
              segments.remove(segments.size() - 1);
              totalDistance -= accepted.routeDistance;
              removeVisitedEdges(accepted.track, visitedEdgeCounts);
              waypointStack.remove(waypointStack.size() - 1);
              currentMwp = waypointStack.get(waypointStack.size() - 1);
              currentIlon = currentMwp.crosspoint.getILon();
              currentIlat = currentMwp.crosspoint.getILat();
              localRadius /= 2;
              continue;
            }

            // Between (1-tol) and (1+tol) but not within tol? → too short, continue
          }
        }

        candidateFound = true;
        break;
      }

      if (!candidateFound) {
        result.addDiagnostic("step " + step + ": exhausted all " + maxAttempts + " attempts");
        break;
      }
    }

    // Return best fallback
    if (bestFallbackTrack != null) {
      result.setTrack(bestFallbackTrack);
      result.setTotalDistanceMeters(bestFallbackTrack.distance);
      result.setWithinTolerance(false);
      result.setFallbackReason("best error=" + String.format("%.1f%%", bestFallbackError * 100));
      result.setSubRoutesChosen(segments.size());
      result.setAttemptsUsed(totalAttempts);
      result.setReusedEdgeRatio(computeReusedEdgeRatio(bestFallbackTrack));
      return result;
    }

    // Last resort: force-close
    if (!segments.isEmpty()) {
      int curIlon = currentMwp.crosspoint.getILon();
      int curIlat = currentMwp.crosspoint.getILat();
      MatchedWaypoint returnFrom = matchPoint(curIlon, curIlat, "greedy_force_from");
      MatchedWaypoint returnTo = matchPoint(start.ilon, start.ilat, "greedy_force_to");
      if (returnFrom != null && returnTo != null) {
        OsmTrack returnTrack = timedFindTrack("greedy-force-close",
          returnFrom, returnTo, buildRefTrack(segments));
        if (returnTrack != null && returnTrack.distance > 0) {
          segments.add(returnTrack);
          OsmTrack finalTrack = mergeSegments(segments, null);
          result.setTrack(finalTrack);
          result.setTotalDistanceMeters(finalTrack.distance);
          result.setWithinTolerance(false);
          result.setFallbackReason("forced closure");
          result.setSubRoutesChosen(segments.size());
          result.setAttemptsUsed(totalAttempts);
          result.setReusedEdgeRatio(computeReusedEdgeRatio(finalTrack));
          return result;
        }
      }
    }

    result.setFallbackReason("could not build any loop");
    return result;
  }

  // --- Candidate generation ---

  private List<CandidatePoint> generateCandidatePoints(
    int fromIlon, int fromIlat, double airRadius, int step, double startDirection) {

    List<CandidatePoint> points = new ArrayList<>();
    double angleStep = 360.0 / CANDIDATE_DIRECTIONS;
    double baseAngle = (step <= 2 && startDirection >= 0) ? startDirection : 0;

    for (int i = 0; i < CANDIDATE_DIRECTIONS; i++) {
      double bearing = CheapAngleMeter.normalize(baseAngle + i * angleStep);
      int[] dest = CheapRuler.destination(fromIlon, fromIlat, airRadius, bearing);

      CandidatePoint cp = new CandidatePoint();
      cp.ilon = dest[0];
      cp.ilat = dest[1];
      cp.bearing = bearing;
      points.add(cp);
    }
    return points;
  }

  // --- Routing with timeout ---

  private OsmTrack timedFindTrack(String name, MatchedWaypoint from, MatchedWaypoint to,
                                  OsmTrack refTrack) {
    long savedStartTime = engine.startTime;
    long savedMaxRunningTime = engine.maxRunningTime;
    try {
      engine.startTime = System.currentTimeMillis();
      engine.maxRunningTime = SUB_ROUTE_TIMEOUT_MS;
      return engine.findTrack(name, from, to, null, refTrack, false);
    } catch (IllegalArgumentException e) {
      return null;
    } finally {
      engine.startTime = savedStartTime;
      engine.maxRunningTime = savedMaxRunningTime;
    }
  }

  // --- Waypoint matching ---

  private MatchedWaypoint matchPoint(int ilon, int ilat, String name) {
    try {
      engine.resetCache(false);
      MatchedWaypoint mwp = new MatchedWaypoint();
      mwp.waypoint = new OsmNode(ilon, ilat);
      mwp.name = name;
      List<MatchedWaypoint> mwpList = new ArrayList<>();
      mwpList.add(mwp);
      engine.nodesCache.matchWaypointsToNodes(mwpList, 2000, engine.islandNodePairs);
      if (mwp.crosspoint == null || mwp.node1 == null || mwp.node2 == null) {
        return null;
      }
      return mwp;
    } catch (Exception e) {
      return null;
    }
  }

  // --- Track management ---

  private OsmTrack buildRefTrack(List<OsmTrack> segments) {
    if (segments.isEmpty()) return null;
    return mergeSegments(segments, null);
  }

  private OsmTrack mergeSegments(List<OsmTrack> segments, OsmTrack finalSegment) {
    OsmTrack merged = new OsmTrack();
    for (OsmTrack seg : segments) {
      appendTrack(merged, seg);
    }
    if (finalSegment != null) {
      appendTrack(merged, finalSegment);
    }
    merged.buildMap();
    return merged;
  }

  private void appendTrack(OsmTrack target, OsmTrack source) {
    if (source.nodes == null) return;
    boolean first = true;
    for (OsmPathElement node : source.nodes) {
      if (first && !target.nodes.isEmpty()) {
        OsmPathElement last = target.nodes.get(target.nodes.size() - 1);
        if (last.getILon() == node.getILon() && last.getILat() == node.getILat()) {
          first = false;
          continue;
        }
      }
      first = false;
      target.nodes.add(node);
    }
    target.distance += source.distance;
    target.ascend += source.ascend;
    target.cost += source.cost;
  }

  // --- Visited edge tracking (ref-counted) ---

  private void addVisitedEdges(OsmTrack track, Map<Long, Integer> edgeCounts) {
    if (track.nodes == null || track.nodes.size() < 2) return;
    for (int i = 1; i < track.nodes.size(); i++) {
      edgeCounts.merge(edgeKey(track.nodes.get(i - 1), track.nodes.get(i)), 1, Integer::sum);
    }
  }

  private void removeVisitedEdges(OsmTrack track, Map<Long, Integer> edgeCounts) {
    if (track.nodes == null || track.nodes.size() < 2) return;
    for (int i = 1; i < track.nodes.size(); i++) {
      long key = edgeKey(track.nodes.get(i - 1), track.nodes.get(i));
      edgeCounts.compute(key, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }
  }

  private double computeTrackVisitedRatio(OsmTrack track, Map<Long, Integer> edgeCounts) {
    if (edgeCounts.isEmpty() || track.nodes == null || track.nodes.size() < 2) return 0.0;
    int total = 0;
    int visited = 0;
    for (int i = 1; i < track.nodes.size(); i++) {
      total++;
      if (edgeCounts.containsKey(edgeKey(track.nodes.get(i - 1), track.nodes.get(i)))) {
        visited++;
      }
    }
    return total > 0 ? (double) visited / total : 0.0;
  }

  private static long edgeKey(OsmPathElement a, OsmPathElement b) {
    long idA = a.getIdFromPos();
    long idB = b.getIdFromPos();
    long lo = Math.min(idA, idB);
    long hi = Math.max(idA, idB);
    return lo ^ (hi * 0x9E3779B97F4A7C15L);
  }

  private double computeReusedEdgeRatio(OsmTrack track) {
    if (track.nodes == null || track.nodes.size() < 2) return 0.0;
    return LoopQualityMetrics.compute(track, track.distance, 0).getRoadReusePercent() / 100.0;
  }

  private DirectionPreference nearestDirectionPreference(double bearing) {
    bearing = CheapAngleMeter.normalize(bearing);
    DirectionPreference best = DirectionPreference.ANY;
    double minDiff = Double.MAX_VALUE;
    for (DirectionPreference dp : DirectionPreference.values()) {
      if (dp == DirectionPreference.ANY) continue;
      double diff = CheapAngleMeter.getDifferenceFromDirection(dp.bearing, bearing);
      if (diff < minDiff) {
        minDiff = diff;
        best = dp;
      }
    }
    return best;
  }

  /** Geometric candidate point with heuristic score (before routing). */
  private static final class CandidatePoint {
    int ilon;
    int ilat;
    double bearing;
    double score; // heuristic score — set during scoring phase
  }

  /** A candidate that has been routed. */
  private static final class ScoredRoute {
    OsmTrack track;
    MatchedWaypoint toMwp;
    double routeDistance;
    double heuristicScore;
    double visitedRatio;
  }
}
