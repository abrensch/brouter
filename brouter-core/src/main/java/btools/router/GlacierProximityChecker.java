/**
 * Checks minimum camping distance from glaciers
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class GlacierProximityChecker {
  
  /**
   * Minimum distance from glaciers for camping (meters)
   */
  public static final double MIN_CAMPING_DISTANCE_FROM_GLACIER = 200.0; // meters
  
  /**
   * Building/hut types that are suitable for camping and exempt from glacier distance rule
   */
  private static final Set<String> CAMPING_BUILDING_TYPES = new HashSet<>(Arrays.asList(
    "alpine_hut",
    "wilderness_hut",
    "basic_hut",
    "shelter",
    "hut",
    "cabin",
    "chalet"
  ));
  
  /**
   * Check if a position is too close to a glacier for camping
   * 
   * @param position Camping position
   * @param nearbyGlaciers List of nearby glacier positions
   * @param hasCampingBuilding Whether there's a camping-suitable building at this position
   * @return true if too close to glacier (and no exempt building), false otherwise
   */
  public static boolean isTooCloseToGlacier(
      OsmPos position,
      List<OsmPos> nearbyGlaciers,
      boolean hasCampingBuilding) {
    
    if (position == null || nearbyGlaciers == null || nearbyGlaciers.isEmpty()) {
      return false; // No glaciers nearby, safe
    }
    
    // Buildings/huts suitable for camping are exempt
    if (hasCampingBuilding) {
      return false;
    }
    
    // Check distance to nearest glacier
    for (OsmPos glacier : nearbyGlaciers) {
      double distance = calculateDistance(position, glacier);
      if (distance < MIN_CAMPING_DISTANCE_FROM_GLACIER) {
        return true; // Too close to glacier
      }
    }
    
    return false; // Safe distance from all glaciers
  }
  
  /**
   * Check if a building type is suitable for camping (exempt from glacier distance rule)
   * 
   * @param buildingType Building type (e.g., "alpine_hut", "shelter")
   * @return true if building is suitable for camping, false otherwise
   */
  public static boolean isCampingBuilding(String buildingType) {
    if (buildingType == null) {
      return false;
    }
    return CAMPING_BUILDING_TYPES.contains(buildingType);
  }
  
  /**
   * Check if a way/node has a camping-suitable building
   * 
   * @param tags Tags from way or node
   * @return true if has camping building, false otherwise
   */
  public static boolean hasCampingBuilding(Map<String, String> tags) {
    if (tags == null) {
      return false;
    }
    
    // Check tourism tags (alpine_hut, wilderness_hut, etc.)
    String tourism = tags.get("tourism");
    if (tourism != null && isCampingBuilding(tourism)) {
      return true;
    }
    
    // Check amenity tags (shelter, etc.)
    String amenity = tags.get("amenity");
    if (amenity != null && ("shelter".equals(amenity))) {
      // Check if it's a basic hut or suitable for camping
      String shelterType = tags.get("shelter_type");
      if (shelterType != null && isCampingBuilding(shelterType)) {
        return true;
      }
      // Basic shelters are usually suitable
      String locked = tags.get("locked");
      if (!"yes".equals(locked)) {
        return true; // Unlocked shelter is suitable for camping
      }
    }
    
    // Check building tags
    String building = tags.get("building");
    if (building != null && isCampingBuilding(building)) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Calculate distance between two positions (meters)
   */
  private static double calculateDistance(OsmPos p1, OsmPos p2) {
    if (p1 == null || p2 == null) {
      return Double.MAX_VALUE;
    }
    
    return CheapRuler.distance(p1.getILat(), p1.getILon(), p2.getILat(), p2.getILon());
  }
  
  /**
   * Get minimum camping distance from glaciers
   * 
   * @return Minimum distance in meters
   */
  public static double getMinCampingDistance() {
    return MIN_CAMPING_DISTANCE_FROM_GLACIER;
  }
  
  /**
   * Check if a way/node is a glacier
   * 
   * @param tags Tags from way or node
   * @return true if is a glacier, false otherwise
   */
  public static boolean isGlacier(Map<String, String> tags) {
    if (tags == null) {
      return false;
    }
    
    String natural = tags.get("natural");
    return "glacier".equals(natural);
  }
  
  /**
   * Get warning message if too close to glacier
   * 
   * @param distance Distance to nearest glacier
   * @return Warning message
   */
  public static String getGlacierWarning(double distance) {
    if (distance < MIN_CAMPING_DISTANCE_FROM_GLACIER) {
      return String.format(
        "WARNING: Camping location is %.0f meters from glacier. Minimum safe distance is %.0f meters.",
        distance,
        MIN_CAMPING_DISTANCE_FROM_GLACIER
      );
    }
    return null;
  }
}

