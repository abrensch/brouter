/**
 * Calculator for trekking cyclist rest suggestions
 * 
 * Scaled from hiking rest distances by factor of 2.5x
 * Typical daily coverage: Hiker ~40 km/day, Trekking Cyclist (loaded) ~100 km/day
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class CyclingRestCalculator {
  
  // Cycling rest constants (scaled from hiking by 2.5x)
  public static final double MAIN_REST_DISTANCE = 28240.0; // 28.24 km (11.295 km * 2.5)
  public static final double ALTERNATIVE_REST_DISTANCE = 5690.0; // 5.69 km (2.275 km * 2.5)
  public static final double MAX_DAILY_DISTANCE = 100000.0; // 100 km per day (40 km * 2.5)
  public static final double MIN_DISTANCE_FROM_MAIN_REST = 1000.0; // 1 km minimum distance from main rest for alternative
  
  /**
   * Calculate suggested rest stops for trekking cyclists
   * 
   * @param totalDistance Total distance in meters
   * @param useAlternative Whether to use alternative (shorter) rest distance
   * @return List of suggested rest stops
   */
  public static List<CyclingRestStop> calculateRestStops(double totalDistance, boolean useAlternative) {
    List<CyclingRestStop> restStops = new ArrayList<>();
    
    if (totalDistance <= MAIN_REST_DISTANCE) {
      return restStops; // No rest needed
    }
    
    // First, calculate main rest stops (every 28.24 km)
    List<Double> mainRestPositions = new ArrayList<>();
    double currentDistance = MAIN_REST_DISTANCE;
    
    while (currentDistance < totalDistance) {
      mainRestPositions.add(currentDistance);
      
      CyclingRestStop restStop = new CyclingRestStop();
      restStop.position = currentDistance;
      restStop.distanceFromStart = currentDistance;
      restStop.isMainRest = true;
      restStop.isAlternative = false;
      restStops.add(restStop);
      
      currentDistance += MAIN_REST_DISTANCE;
    }
    
    // If using alternative, add alternative rest stops (every 5.69 km)
    // but only if >1 km from nearest main rest
    if (useAlternative) {
      currentDistance = ALTERNATIVE_REST_DISTANCE;
      
      while (currentDistance < totalDistance) {
        // Check if this position is >1 km from any main rest
        boolean tooCloseToMainRest = false;
        for (Double mainRestPos : mainRestPositions) {
          double distanceToMainRest = Math.abs(currentDistance - mainRestPos);
          if (distanceToMainRest <= MIN_DISTANCE_FROM_MAIN_REST) {
            tooCloseToMainRest = true;
            break;
          }
        }
        
        // Also check if we already have a rest stop at this position
        boolean alreadyExists = false;
        for (CyclingRestStop existing : restStops) {
          double distanceToExisting = Math.abs(currentDistance - existing.position);
          if (distanceToExisting < 100.0) { // Within 100m, consider it the same
            alreadyExists = true;
            break;
          }
        }
        
        if (!tooCloseToMainRest && !alreadyExists) {
          CyclingRestStop restStop = new CyclingRestStop();
          restStop.position = currentDistance;
          restStop.distanceFromStart = currentDistance;
          restStop.isMainRest = false;
          restStop.isAlternative = true;
          restStops.add(restStop);
        }
        
        currentDistance += ALTERNATIVE_REST_DISTANCE;
      }
      
      // Sort rest stops by position
      restStops.sort((a, b) -> Double.compare(a.position, b.position));
    }
    
    return restStops;
  }
  
  /**
   * Calculate daily segments for trekking cyclists (max 100 km per day)
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
   * Represents a cycling rest stop
   */
  public static class CyclingRestStop {
    public double position; // Position along route in meters
    public double distanceFromStart; // Distance from start in meters
    public boolean isMainRest; // Whether this is a main rest (28.24 km)
    public boolean isAlternative; // Whether this is an alternative rest (5.69 km)
    public RestStopPOISearcher.RestStopPOIs nearbyPOIs; // Water points and cabins nearby
  }
  
  /**
   * Represents a daily cycling segment
   */
  public static class DailySegment {
    public int day;
    public double startDistance; // Start distance in meters
    public double endDistance; // End distance in meters
    public double distance; // Segment distance in meters
  }
}

