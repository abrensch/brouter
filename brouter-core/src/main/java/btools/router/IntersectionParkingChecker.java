/**
 * Checks if cars can park at intersections based on connecting road types
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;

public class IntersectionParkingChecker {
  
  /**
   * Road types where parking is allowed at intersections
   */
  private static final Set<String> PARKING_ALLOWED_ROAD_TYPES = new HashSet<>(Arrays.asList(
    "unclassified",
    "service",
    "track",
    "rest_area",
    "tertiary"
  ));
  
  /**
   * Check if a road type allows parking at intersections
   * 
   * @param highwayType Highway type from OSM
   * @return true if parking is allowed at intersections of this road type
   */
  public static boolean isParkingAllowedRoadType(String highwayType) {
    if (highwayType == null) {
      return false;
    }
    return PARKING_ALLOWED_ROAD_TYPES.contains(highwayType);
  }
  
  /**
   * Check if an intersection allows parking
   * An intersection allows parking if at least two connecting ways
   * are parking-allowed road types
   * 
   * @param node The intersection node
   * @param connectedWays List of highway types of connected ways
   * @return true if parking is allowed at this intersection
   */
  public static boolean isParkingAllowedAtIntersection(OsmNode node, List<String> connectedWays) {
    if (node == null || connectedWays == null || connectedWays.size() < 2) {
      return false;
    }
    
    int parkingAllowedCount = 0;
    for (String highwayType : connectedWays) {
      if (isParkingAllowedRoadType(highwayType)) {
        parkingAllowedCount++;
      }
    }
    
    // Parking allowed if at least 2 connecting roads are parking-allowed types
    return parkingAllowedCount >= 2;
  }
  
  /**
   * Get parking-allowed road types
   * 
   * @return Set of highway types where parking is allowed
   */
  public static Set<String> getParkingAllowedRoadTypes() {
    return new HashSet<>(PARKING_ALLOWED_ROAD_TYPES);
  }
  
  /**
   * Check if a way segment is a parking-allowed road type
   * 
   * @param highwayType Highway type
   * @return true if this road type allows parking at intersections
   */
  public static boolean allowsParking(String highwayType) {
    return isParkingAllowedRoadType(highwayType);
  }
}

