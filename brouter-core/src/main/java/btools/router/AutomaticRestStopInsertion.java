/**
 * Automatically inserts rest stops as waypoints in routes
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class AutomaticRestStopInsertion {
  
  private static final double MAX_SEARCH_RADIUS = 15000; // 15 km
  private static final double MAX_DETOUR_DISTANCE = 5000; // 5 km
  
  /**
   * Insert rest stops as waypoints in a route
   * 
   * @param originalRoute Original route track
   * @param restStops List of required rest stops
   * @param nodesCache Nodes cache for finding rest areas
   * @return List of waypoints to insert (rest areas)
   */
  public List<MatchedWaypoint> insertRestStops(
      OsmTrack originalRoute,
      List<RestPeriodCalculator.RestStopRequirement> restStops,
      btools.mapaccess.NodesCache nodesCache) {
    
    List<MatchedWaypoint> newViaPoints = new ArrayList<>();
    
    if (originalRoute == null || restStops == null || restStops.isEmpty()) {
      return newViaPoints;
    }
    
    // Estimate average speed to convert time to distance
    double averageSpeed = estimateAverageSpeed(originalRoute); // m/s
    
    for (RestPeriodCalculator.RestStopRequirement stop : restStops) {
      // Convert time position to distance
      double positionMeters = stop.position * averageSpeed;
      
      // Find nearest rest area
      RestStopFinder.RestStopCandidate restArea = findNearestRestArea(
          originalRoute, positionMeters, MAX_SEARCH_RADIUS, nodesCache);
      
      if (restArea != null && restArea.detourDistance <= MAX_DETOUR_DISTANCE) {
        MatchedWaypoint waypoint = new MatchedWaypoint();
        waypoint.name = "Rest Stop: " + restArea.type;
        waypoint.waypoint = new btools.mapaccess.OsmNode(restArea.location.getILon(), restArea.location.getILat());
        newViaPoints.add(waypoint);
      }
    }
    
    return newViaPoints;
  }
  
  /**
   * Find nearest rest area to a position along route
   */
  private RestStopFinder.RestStopCandidate findNearestRestArea(
      OsmTrack route, double positionMeters, double searchRadius,
      btools.mapaccess.NodesCache nodesCache) {
    
    // Find node at position
    OsmPathElement targetNode = findNodeAtPosition(route, positionMeters);
    if (targetNode == null || nodesCache == null) {
      return null;
    }
    
    // Search nearby segments for rest areas
    // Create a RestStopRequirement for this position
    RestPeriodCalculator.RestStopRequirement requirement = new RestPeriodCalculator.RestStopRequirement();
    requirement.position = positionMeters / estimateAverageSpeed(route);
    
    // Use RestStopFinder which has the logic to search for POIs
    return RestStopFinder.findBestRestStop(requirement, route, nodesCache);
  }
  
  /**
   * Find node at specific position along route
   */
  private OsmPathElement findNodeAtPosition(OsmTrack track, double positionMeters) {
    if (track.nodes == null || track.nodes.isEmpty()) {
      return null;
    }
    
    double accumulatedDistance = 0;
    for (int i = 0; i < track.nodes.size(); i++) {
      OsmPathElement node = track.nodes.get(i);
      if (i > 0) {
        accumulatedDistance += node.calcDistance(track.nodes.get(i - 1));
      }
      
      if (accumulatedDistance >= positionMeters) {
        return node;
      }
    }
    
    return track.nodes.get(track.nodes.size() - 1);
  }
  
  /**
   * Estimate average speed for the route (m/s)
   */
  private double estimateAverageSpeed(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return 20; // Default 20 m/s = 72 km/h
    }
    
    double totalTime = (double) track.getTotalSeconds();
    double totalDistance = (double) track.distance;
    
    if (totalTime > 0) {
      return totalDistance / totalTime;
    }
    
    return 20; // Default
  }
  
  /**
   * Recalculate route with new via-points
   * Integrates with RoutingEngine to recalculate route
   */
  public OsmTrack recalculateWithViaPoints(
      List<MatchedWaypoint> originalWaypoints,
      List<MatchedWaypoint> newViaPoints,
      RoutingContext routingContext,
      java.io.File segmentDir,
      List<btools.router.OsmNodeNamed> waypointList) {
    
    if (routingContext == null || segmentDir == null || waypointList == null) {
      return null;
    }
    
    // Combine original and new waypoints
    List<btools.router.OsmNodeNamed> allWaypoints = new ArrayList<>(waypointList);
    
    // Add new via points to waypoint list
    for (MatchedWaypoint newVia : newViaPoints) {
      if (newVia.waypoint != null) {
        btools.router.OsmNodeNamed onn = new btools.router.OsmNodeNamed(newVia.waypoint);
        onn.name = newVia.name != null ? newVia.name : "via";
        allWaypoints.add(allWaypoints.size() - 1, onn); // Insert before last (destination)
      }
    }
    
    // Create new RoutingEngine with updated waypoints
    try {
      RoutingEngine re = new RoutingEngine(
          null, // outfileBase
          null, // logfileBase
          segmentDir,
          allWaypoints,
          routingContext,
          0 // engineMode
      );
      
      // Run routing
      re.doRun(0); // No timeout limit
      
      // Get the result
      return re.getFoundTrack();
    } catch (Exception e) {
      // Log error but don't fail
      System.err.println("Error recalculating route with via points: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Overload without waypoint list for backward compatibility
   */
  public OsmTrack recalculateWithViaPoints(
      List<MatchedWaypoint> originalWaypoints,
      List<MatchedWaypoint> newViaPoints,
      RoutingContext routingContext) {
    return recalculateWithViaPoints(originalWaypoints, newViaPoints, routingContext, null, null);
  }
}

