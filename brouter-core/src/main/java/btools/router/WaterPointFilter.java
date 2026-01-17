/**
 * Filter for water points (springs, fountains, etc.) for hikers and cyclists
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.OsmPos;

public class WaterPointFilter {
  
  // Filtering constants
  public static final double MIN_DISTANCE_BETWEEN_WATER_POINTS = 4000.0; // 4 km in meters
  public static final double SEARCH_RADIUS = 2000.0; // 2 km search radius in meters
  public static final String SPRING_WARNING = "Drink at your own risk";
  
  /**
   * Filter water points to prevent clutter
   * Ensures minimum distance between water points
   * 
   * @param waterPoints List of water points along route
   * @return Filtered list of water points
   */
  public static List<WaterPoint> filterWaterPoints(List<WaterPoint> waterPoints) {
    if (waterPoints == null || waterPoints.isEmpty()) {
      return new ArrayList<>();
    }
    
    List<WaterPoint> filtered = new ArrayList<>();
    WaterPoint lastPoint = null;
    
    for (WaterPoint point : waterPoints) {
      if (lastPoint == null) {
        // First point always included
        filtered.add(point);
        lastPoint = point;
      } else {
        // Check distance from last point
        double distance = calculateDistance(lastPoint.location, point.location);
        if (distance >= MIN_DISTANCE_BETWEEN_WATER_POINTS) {
          filtered.add(point);
          lastPoint = point;
        }
      }
    }
    
    return filtered;
  }
  
  /**
   * Find water points near a rest stop
   * 
   * @param restStopLocation Location of rest stop
   * @param allWaterPoints All available water points
   * @return List of water points within search radius
   */
  public static List<WaterPoint> findWaterPointsNear(OsmPos restStopLocation, List<WaterPoint> allWaterPoints) {
    List<WaterPoint> nearby = new ArrayList<>();
    
    if (restStopLocation == null || allWaterPoints == null) {
      return nearby;
    }
    
    for (WaterPoint point : allWaterPoints) {
      double distance = calculateDistance(restStopLocation, point.location);
      if (distance <= SEARCH_RADIUS) {
        nearby.add(point);
      }
    }
    
    return nearby;
  }
  
  /**
   * Calculate distance between two points (Haversine formula approximation)
   */
  private static double calculateDistance(OsmPos p1, OsmPos p2) {
    if (p1 == null || p2 == null) {
      return Double.MAX_VALUE;
    }
    
    // Simple approximation using lat/lon
    double lat1 = p1.getILat() / 1e6;
    double lon1 = p1.getILon() / 1e6;
    double lat2 = p2.getILat() / 1e6;
    double lon2 = p2.getILon() / 1e6;
    
    // Rough distance calculation (meters)
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon / 2) * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    
    return 6371000 * c; // Earth radius in meters
  }
  
  /**
   * Represents a water point
   */
  public static class WaterPoint {
    public OsmPos location;
    public String type; // "spring", "fountain", "drinking_water", etc.
    public String name;
    public boolean isSpring; // Whether this is a natural spring
    public String warning; // Warning message (e.g., for springs)
    
    public WaterPoint(OsmPos location, String type, String name) {
      this.location = location;
      this.type = type;
      this.name = name;
      this.isSpring = "spring".equals(type) || "natural=spring".equals(type);
      if (this.isSpring) {
        this.warning = SPRING_WARNING;
      }
    }
  }
}

