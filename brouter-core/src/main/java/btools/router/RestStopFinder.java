/**
 * Finds rest areas, motels, and service areas along a route
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class RestStopFinder {
  
  private static final double SEARCH_RADIUS = 5000; // 5 km search radius
  private static final double MAX_DETOUR_DISTANCE = 2000; // 2 km max detour
  
  /**
   * Find rest stops near a specific position along the route
   * 
   * @param position Position along route (in meters from start)
   * @param track The route track
   * @param nodesCache Nodes cache for searching
   * @return List of potential rest stops
   */
  public static List<RestStopCandidate> findRestStopsNear(
      double position, 
      OsmTrack track, 
      btools.mapaccess.NodesCache nodesCache) {
    
    List<RestStopCandidate> candidates = new ArrayList<>();
    
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return candidates;
    }
    
    // Find the node closest to the position
    OsmPathElement targetNode = findNodeAtPosition(track, position);
    if (targetNode == null) {
      return candidates;
    }
    
    // Search for rest areas, services, and motels in the area
    // Query nearby segments for nodes with rest area tags
    if (nodesCache != null) {
      candidates = searchNearbySegments(targetNode, nodesCache, SEARCH_RADIUS);
    }
    
    return candidates;
  }
  
  /**
   * Search nearby segments for rest area POIs
   * This searches MicroCache segments around the target position for nodes
   * that might be rest areas, services, or motels.
   */
  private static List<RestStopCandidate> searchNearbySegments(
      OsmPathElement centerNode,
      btools.mapaccess.NodesCache nodesCache,
      double searchRadius) {
    
    List<RestStopCandidate> candidates = new ArrayList<>();
    
    if (centerNode == null || nodesCache == null) {
      return candidates;
    }
    
    // Calculate search grid (segments are 5x5 degrees, ~111km per degree at equator)
    // Search radius in degrees (approximate)
    double radiusDegrees = searchRadius / 111000.0; // meters to degrees
    
    int centerLon = centerNode.getILon();
    int centerLat = centerNode.getILat();
    
    // Search in a grid around the center point
    // Use smaller step size to cover more segments
    int stepSize = 500000; // 0.5 degree in internal coordinates (~55km)
    int radiusInternal = (int)(radiusDegrees * 1000000);
    
    // Iterate through nearby segments
    for (int latOffset = -radiusInternal; latOffset <= radiusInternal; latOffset += stepSize) {
      for (int lonOffset = -radiusInternal; lonOffset <= radiusInternal; lonOffset += stepSize) {
        int searchLon = centerLon + lonOffset;
        int searchLat = centerLat + latOffset;
        
        // Get segment for this area
        btools.codec.MicroCache segment = nodesCache.getSegmentFor(searchLon, searchLat);
        if (segment == null || segment.getSize() == 0) {
          continue;
        }
        
        // Search nodes in this segment
        // Note: MicroCache stores nodes in a compact format
        // We would need to iterate through all nodes and check their tags
        // This requires accessing the MicroCache's internal node list
        // For now, we provide the framework - actual POI detection would
        // require iterating through segment.getSize() nodes and checking
        // nodeDescription tags for highway=rest_area, highway=services, tourism=motel
        
        // The implementation would:
        // 1. Get all node IDs from the segment
        // 2. For each node, parse its description
        // 3. Check if it has rest area tags
        // 4. Calculate distance and create RestStopCandidate
      }
    }
    
    return candidates;
  }
  
  /**
   * Find the best rest stop for a required rest period
   * 
   * @param requirement Required rest stop
   * @param track The route track
   * @param nodesCache Nodes cache
   * @return Best rest stop candidate, or null if none found
   */
  public static RestStopCandidate findBestRestStop(
      RestPeriodCalculator.RestStopRequirement requirement,
      OsmTrack track,
      btools.mapaccess.NodesCache nodesCache) {
    
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return null;
    }
    
    // Find position along route
    double positionMeters = requirement.position * estimateAverageSpeed(track);
    
    // Search for rest stops near this position
    List<RestStopCandidate> candidates = findRestStopsNear(positionMeters, track, nodesCache);
    
    if (candidates.isEmpty()) {
      return null;
    }
    
    // Find the closest candidate that's not too far off route
    RestStopCandidate best = null;
    double bestScore = Double.MAX_VALUE;
    
    for (RestStopCandidate candidate : candidates) {
      double detour = candidate.detourDistance;
      double distanceFromRequired = Math.abs(candidate.positionAlongRoute - positionMeters);
      double score = detour * 2 + distanceFromRequired; // Prefer closer, less detour
      
      if (detour <= MAX_DETOUR_DISTANCE && score < bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    
    return best;
  }
  
  /**
   * Find node at specific position along route
   */
  private static OsmPathElement findNodeAtPosition(OsmTrack track, double positionMeters) {
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
  private static double estimateAverageSpeed(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return 20; // Default 20 m/s = 72 km/h
    }
    
    double totalTime = (double) track.getTotalSeconds();
    double totalDistance = track.distance;
    
    if (totalTime > 0) {
      return totalDistance / totalTime;
    }
    
    return 20; // Default
  }
  
  /**
   * Represents a potential rest stop location
   */
  public static class RestStopCandidate {
    public OsmPos location;
    public String type; // "rest_area", "services", "motel"
    public double positionAlongRoute; // Position along route in meters
    public double detourDistance; // Additional distance to reach this stop
    public String name;
    public double distanceFromRoute; // Distance from route in meters
  }
}

