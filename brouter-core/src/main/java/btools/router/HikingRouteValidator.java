/**
 * Validates hiking routes for forbidden highways and path prioritization
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class HikingRouteValidator {
  
  // Forbidden highways for hikers
  private static final Set<String> FORBIDDEN_HIGHWAYS = new HashSet<>(Arrays.asList(
    "motorway", "trunk", "primary", "primary_link", 
    "motorway_link", "trunk_link"
  ));
  
  // Priority paths (lowest cost)
  private static final Set<String> PRIORITY_PATHS = new HashSet<>(Arrays.asList(
    "footway", "path", "track", "steps", "bridleway"
  ));
  
  /**
   * Route validation result
   */
  public static class RouteValidationResult {
    public double pathPercentage;      // Percentage of route on priority paths
    public double forbiddenPercentage;  // Percentage of route on forbidden highways
    public double secondaryPercentage; // Percentage on secondary roads
    public int forbiddenSegments;      // Number of forbidden segments
    public List<String> warnings;      // Warning messages
    public boolean isValid;            // Overall validity
    
    public RouteValidationResult() {
      this.warnings = new ArrayList<>();
    }
  }
  
  /**
   * Validate a hiking route
   * 
   * @param track The route track to validate
   * @return Validation result with statistics and warnings
   */
  public RouteValidationResult validate(OsmTrack track) {
    RouteValidationResult result = new RouteValidationResult();
    
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      result.isValid = false;
      result.warnings.add("Empty or null track");
      return result;
    }
    
    double totalLength = 0;
    double pathLength = 0;
    double forbiddenLength = 0;
    double secondaryLength = 0;
    
    // Analyze each segment
    for (int i = 0; i < track.nodes.size() - 1; i++) {
      OsmPathElement node1 = track.nodes.get(i);
      OsmPathElement node2 = track.nodes.get(i + 1);
      
      double segmentLength = node1.calcDistance(node2);
      totalLength += segmentLength;
      
      // Get highway type from node (would need way data)
      // For now, we'll need to check tags if available
      String highwayType = getHighwayType(node1, node2);
      
      if (highwayType != null) {
        if (FORBIDDEN_HIGHWAYS.contains(highwayType)) {
          forbiddenLength += segmentLength;
          result.forbiddenSegments++;
        } else if (PRIORITY_PATHS.contains(highwayType)) {
          pathLength += segmentLength;
        } else {
          secondaryLength += segmentLength;
        }
      }
    }
    
    // Calculate percentages
    if (totalLength > 0) {
      result.pathPercentage = (pathLength / totalLength) * 100.0;
      result.forbiddenPercentage = (forbiddenLength / totalLength) * 100.0;
      result.secondaryPercentage = (secondaryLength / totalLength) * 100.0;
    }
    
    // Generate warnings
    if (result.forbiddenPercentage > 0) {
      result.warnings.add(String.format(
        "Route uses %.1f%% forbidden highways (motorways, trunk, primary roads)",
        result.forbiddenPercentage
      ));
    }
    
    if (result.pathPercentage < 50.0 && result.forbiddenPercentage == 0) {
      result.warnings.add(String.format(
        "Only %.1f%% of route uses priority paths (footways, paths, tracks)",
        result.pathPercentage
      ));
    }
    
    // Route is valid if no forbidden highways
    result.isValid = result.forbiddenPercentage == 0;
    
    return result;
  }
  
  /**
   * Check if a highway type is forbidden for hikers
   */
  public static boolean isForbiddenHighway(String highwayType) {
    return highwayType != null && FORBIDDEN_HIGHWAYS.contains(highwayType);
  }
  
  /**
   * Check if a highway type is a priority path
   */
  public static boolean isPriorityPath(String highwayType) {
    return highwayType != null && PRIORITY_PATHS.contains(highwayType);
  }
  
  /**
   * Get highway type from nodes (simplified - would need way data in real implementation)
   */
  private String getHighwayType(OsmPathElement node1, OsmPathElement node2) {
    // In real implementation, this would access way tags
    // For now, return null (would need integration with way data)
    return null;
  }
  
  /**
   * Validate route with way tags
   */
  public RouteValidationResult validateWithWays(OsmTrack track, List<WaySegment> segments) {
    RouteValidationResult result = new RouteValidationResult();
    
    if (track == null || segments == null || segments.isEmpty()) {
      result.isValid = false;
      result.warnings.add("Empty or null track/segments");
      return result;
    }
    
    double totalLength = 0;
    double pathLength = 0;
    double forbiddenLength = 0;
    double secondaryLength = 0;
    
    for (WaySegment segment : segments) {
      totalLength += segment.length;
      String highwayType = segment.highwayType;
      String footTag = segment.footTag;
      
      // Check if foot is explicitly forbidden
      if ("no".equals(footTag) || "private".equals(footTag)) {
        forbiddenLength += segment.length;
        result.forbiddenSegments++;
        continue;
      }
      
      if (highwayType != null) {
        if (FORBIDDEN_HIGHWAYS.contains(highwayType)) {
          // Check if sidewalk available
          if (segment.sidewalk == null || "no".equals(segment.sidewalk)) {
            forbiddenLength += segment.length;
            result.forbiddenSegments++;
          } else {
            secondaryLength += segment.length;
          }
        } else if (PRIORITY_PATHS.contains(highwayType)) {
          pathLength += segment.length;
        } else {
          secondaryLength += segment.length;
        }
      }
    }
    
    // Calculate percentages
    if (totalLength > 0) {
      result.pathPercentage = (pathLength / totalLength) * 100.0;
      result.forbiddenPercentage = (forbiddenLength / totalLength) * 100.0;
      result.secondaryPercentage = (secondaryLength / totalLength) * 100.0;
    }
    
    // Generate warnings
    if (result.forbiddenPercentage > 0) {
      result.warnings.add(String.format(
        "Route uses %.1f%% forbidden highways",
        result.forbiddenPercentage
      ));
    }
    
    if (result.pathPercentage < 50.0 && result.forbiddenPercentage == 0) {
      result.warnings.add(String.format(
        "Only %.1f%% of route uses priority paths",
        result.pathPercentage
      ));
    }
    
    result.isValid = result.forbiddenPercentage == 0;
    
    return result;
  }
  
  /**
   * Represents a way segment with tags
   */
  public static class WaySegment {
    public String highwayType;
    public String footTag;
    public String sidewalk;
    public double length;
    
    public WaySegment(String highwayType, String footTag, String sidewalk, double length) {
      this.highwayType = highwayType;
      this.footTag = footTag;
      this.sidewalk = sidewalk;
      this.length = length;
    }
  }
}

