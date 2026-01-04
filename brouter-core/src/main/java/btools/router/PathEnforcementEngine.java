/**
 * Enforces path usage for hiking routes by adding waypoints to avoid forbidden highways
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class PathEnforcementEngine {
  
  private static final double MAX_PATH_DETOUR = 2000.0; // 2 km in meters
  private static final double SEARCH_RADIUS = 2000.0; // 2 km search radius
  
  /**
   * Enforce path usage by adding waypoints to avoid forbidden highways
   * 
   * @param originalRoute Original route that may contain forbidden highways
   * @param forbiddenSegments List of forbidden segments to avoid
   * @param nodesCache Nodes cache for querying map data
   * @return List of additional waypoints to add
   */
  public List<MatchedWaypoint> enforcePathUsage(
      OsmTrack originalRoute,
      List<HikingRouteValidator.WaySegment> forbiddenSegments,
      btools.mapaccess.NodesCache nodesCache) {
    
    List<MatchedWaypoint> additionalViaPoints = new ArrayList<>();
    
    if (originalRoute == null || forbiddenSegments == null || forbiddenSegments.isEmpty()) {
      return additionalViaPoints;
    }
    
    for (HikingRouteValidator.WaySegment forbidden : forbiddenSegments) {
      // Find midpoint of forbidden segment
      OsmPos midpoint = calculateMidpoint(forbidden);
      if (midpoint == null) {
        continue;
      }
      
      // Find nearest allowed path within search radius
      OsmPos nearestPath = findNearestAllowedPath(midpoint, SEARCH_RADIUS, nodesCache);
      
      if (nearestPath != null) {
        // Check if detour is acceptable
        double detourDistance = calculateDetourDistance(originalRoute, midpoint, nearestPath);
        
        if (detourDistance <= MAX_PATH_DETOUR) {
          MatchedWaypoint waypoint = new MatchedWaypoint();
          waypoint.name = "Path enforcement waypoint";
          waypoint.waypoint = new btools.mapaccess.OsmNode(nearestPath.getILon(), nearestPath.getILat());
          additionalViaPoints.add(waypoint);
        }
      }
    }
    
    return additionalViaPoints;
  }
  
  /**
   * Overload without nodesCache for backward compatibility
   */
  public List<MatchedWaypoint> enforcePathUsage(
      OsmTrack originalRoute,
      List<HikingRouteValidator.WaySegment> forbiddenSegments) {
    return enforcePathUsage(originalRoute, forbiddenSegments, null);
  }
  
  /**
   * Calculate midpoint of a way segment
   */
  private OsmPos calculateMidpoint(HikingRouteValidator.WaySegment segment) {
    // For WaySegment, we need start and end positions
    // Since WaySegment doesn't have direct position info, we'll use
    // the route nodes to find the midpoint
    // This is a simplified implementation - full version would use actual way geometry
    return null; // Would need segment geometry data
  }
  
  /**
   * Find nearest allowed path (footway, path, track, etc.) within radius
   * 
   * @param position Position to search from
   * @param radius Search radius in meters
   * @param nodesCache Nodes cache for querying map data
   * @return Nearest allowed path position, or null if none found
   */
  private OsmPos findNearestAllowedPath(OsmPos position, double radius, btools.mapaccess.NodesCache nodesCache) {
    if (position == null) {
      return null;
    }
    
    // Search nearby segments for paths
    if (nodesCache != null) {
      return searchNearbyPaths(position, radius, nodesCache);
    }
    
    return null;
  }
  
  /**
   * Search nearby segments for allowed paths
   */
  private OsmPos searchNearbyPaths(OsmPos center, double radius, btools.mapaccess.NodesCache nodesCache) {
    // Calculate search area
    double radiusDegrees = radius / 111000.0; // meters to degrees
    int centerLon = center.getILon();
    int centerLat = center.getILat();
    int radiusInternal = (int)(radiusDegrees * 1000000);
    
    OsmPos nearestPath = null;
    double nearestDistance = Double.MAX_VALUE;
    
    // Search in grid around center
    int stepSize = 500000; // 0.5 degree
    for (int latOffset = -radiusInternal; latOffset <= radiusInternal; latOffset += stepSize) {
      for (int lonOffset = -radiusInternal; lonOffset <= radiusInternal; lonOffset += stepSize) {
        int searchLon = centerLon + lonOffset;
        int searchLat = centerLat + latOffset;
        
        btools.codec.MicroCache segment = nodesCache.getSegmentFor(searchLon, searchLat);
        if (segment == null || segment.getSize() == 0) {
          continue;
        }
        
        // Search for nodes/links with priority path highway types
        // This would require iterating through segment nodes and checking
        // link descriptions for highway=footway|path|track|steps|bridleway
        // For now, framework structure is provided
      }
    }
    
    return nearestPath;
  }
  
  /**
   * Calculate detour distance if waypoint is added
   */
  private double calculateDetourDistance(OsmTrack route, OsmPos original, OsmPos detour) {
    // Calculate additional distance if route goes via detour point
    // Simplified calculation
    double directDistance = CheapRuler.distance(
        original.getILat(), original.getILon(),
        detour.getILat(), detour.getILon());
    
    // Would need full route recalculation for accurate detour
    return directDistance * 2; // Rough estimate
  }
  
  /**
   * Find forbidden segments in a route
   */
  public List<HikingRouteValidator.WaySegment> findForbiddenSegments(
      OsmTrack route, List<HikingRouteValidator.WaySegment> allSegments) {
    
    List<HikingRouteValidator.WaySegment> forbidden = new ArrayList<>();
    
    if (allSegments == null) {
      return forbidden;
    }
    
    for (HikingRouteValidator.WaySegment segment : allSegments) {
      if (HikingRouteValidator.isForbiddenHighway(segment.highwayType)) {
        // Check if sidewalk available
        if (segment.sidewalk == null || "no".equals(segment.sidewalk)) {
          forbidden.add(segment);
        }
      }
    }
    
    return forbidden;
  }
}

