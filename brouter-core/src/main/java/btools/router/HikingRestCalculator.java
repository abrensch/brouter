/**
 * Calculator for hiking rest suggestions
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class HikingRestCalculator {
  
  // Hiking rest constants
  public static final double SUGGESTED_REST_DISTANCE = 11295.0; // 11.295 km
  public static final double ALTERNATIVE_REST_DISTANCE = 2275.2; // 2.2752 km
  public static final double MAX_DAILY_DISTANCE = 40000.0; // 40 km per day
  
  /**
   * Calculate suggested rest stops for hiking
   * 
   * @param totalDistance Total distance in meters
   * @param useAlternative Whether to use alternative (shorter) rest distance
   * @return List of suggested rest stops
   */
  public static List<HikingRestStop> calculateRestStops(double totalDistance, boolean useAlternative) {
    List<HikingRestStop> restStops = new ArrayList<>();
    
    double restDistance = useAlternative ? ALTERNATIVE_REST_DISTANCE : SUGGESTED_REST_DISTANCE;
    
    if (totalDistance <= restDistance) {
      return restStops; // No rest needed
    }
    
    double currentDistance = 0;
    
    while (currentDistance < totalDistance) {
      double remainingDistance = totalDistance - currentDistance;
      
      if (remainingDistance <= restDistance) {
        break; // Can complete without additional rest
      }
      
      currentDistance += restDistance;
      
      HikingRestStop restStop = new HikingRestStop();
      restStop.position = currentDistance;
      restStop.distanceFromStart = currentDistance;
      restStop.isAlternative = useAlternative;
      restStops.add(restStop);
    }
    
    return restStops;
  }
  
  /**
   * Calculate daily segments for hiking (max 40 km per day)
   * 
   * @param totalDistance Total distance in meters
   * @return List of daily segments
   */
  public static List<DailySegment> calculateDailySegments(double totalDistance) {
    List<DailySegment> segments = new ArrayList<>();
    
    if (totalDistance <= MAX_DAILY_DISTANCE) {
      DailySegment segment = new DailySegment();
      segment.day = 1;
      segment.startDistance = 0;
      segment.endDistance = totalDistance;
      segment.distance = totalDistance;
      segments.add(segment);
      return segments;
    }
    
    int day = 1;
    double currentDistance = 0;
    
    while (currentDistance < totalDistance) {
      double remainingDistance = totalDistance - currentDistance;
      double segmentDistance = Math.min(MAX_DAILY_DISTANCE, remainingDistance);
      
      DailySegment segment = new DailySegment();
      segment.day = day;
      segment.startDistance = currentDistance;
      segment.endDistance = currentDistance + segmentDistance;
      segment.distance = segmentDistance;
      segments.add(segment);
      
      currentDistance += segmentDistance;
      day++;
    }
    
    return segments;
  }
  
  /**
   * Represents a hiking rest stop
   */
  public static class HikingRestStop {
    public double position; // Position along route in meters
    public double distanceFromStart; // Distance from start in meters
    public boolean isAlternative; // Whether using alternative (shorter) distance
    public RestStopPOISearcher.RestStopPOIs nearbyPOIs; // Water points and cabins nearby
  }
  
  /**
   * Represents a daily hiking segment
   */
  public static class DailySegment {
    public int day;
    public double startDistance; // Start distance in meters
    public double endDistance; // End distance in meters
    public double distance; // Segment distance in meters
  }
}

