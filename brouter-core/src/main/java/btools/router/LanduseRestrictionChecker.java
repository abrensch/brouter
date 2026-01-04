/**
 * Checks forbidden landuse tags for cars, cyclists, and hikers
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LanduseRestrictionChecker {
  
  /**
   * Forbidden cultivated/agricultural landuse types
   */
  private static final Set<String> FORBIDDEN_CULTIVATED = new HashSet<>(Arrays.asList(
    "farmland",
    "farmyard",
    "vineyard",
    "orchard",
    "greenhouse_horticulture",
    "plant_nursery"
  ));
  
  /**
   * Forbidden residential/commercial/industrial landuse types
   */
  private static final Set<String> FORBIDDEN_RESIDENTIAL = new HashSet<>(Arrays.asList(
    "residential",
    "commercial",
    "industrial",
    "retail",
    "garages",
    "railway",
    "quarry"
  ));
  
  /**
   * Forbidden restricted access landuse types
   */
  private static final Set<String> FORBIDDEN_RESTRICTED = new HashSet<>(Arrays.asList(
    "military",
    "construction"
  ));
  
  /**
   * All forbidden landuse types
   */
  private static final Set<String> ALL_FORBIDDEN_LANDUSE = new HashSet<>();
  static {
    ALL_FORBIDDEN_LANDUSE.addAll(FORBIDDEN_CULTIVATED);
    ALL_FORBIDDEN_LANDUSE.addAll(FORBIDDEN_RESIDENTIAL);
    ALL_FORBIDDEN_LANDUSE.addAll(FORBIDDEN_RESTRICTED);
  }
  
  /**
   * Check if a way has forbidden landuse
   * 
   * @param wayTags Way tags
   * @return true if way has forbidden landuse, false otherwise
   */
  public static boolean hasForbiddenLanduse(Map<String, String> wayTags) {
    if (wayTags == null) {
      return false;
    }
    
    String landuse = wayTags.get("landuse");
    if (landuse != null && ALL_FORBIDDEN_LANDUSE.contains(landuse)) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Check if a way has forbidden access
   * 
   * @param wayTags Way tags
   * @return true if way has forbidden access (private or no), false otherwise
   */
  public static boolean hasForbiddenAccess(Map<String, String> wayTags) {
    if (wayTags == null) {
      return false;
    }
    
    String access = wayTags.get("access");
    if (access != null && ("private".equals(access) || "no".equals(access))) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Check if access is allowed (no forbidden landuse and no forbidden access)
   * 
   * @param wayTags Way tags
   * @return true if access is allowed, false if forbidden
   */
  public static boolean isAccessAllowed(Map<String, String> wayTags) {
    if (hasForbiddenLanduse(wayTags)) {
      return false;
    }
    
    if (hasForbiddenAccess(wayTags)) {
      return false;
    }
    
    return true;
  }
  
  /**
   * Get the reason for restriction
   * 
   * @param wayTags Way tags
   * @return Restriction reason, or null if no restriction
   */
  public static String getRestrictionReason(Map<String, String> wayTags) {
    if (wayTags == null) {
      return null;
    }
    
    String landuse = wayTags.get("landuse");
    if (landuse != null && ALL_FORBIDDEN_LANDUSE.contains(landuse)) {
      if (FORBIDDEN_CULTIVATED.contains(landuse)) {
        return "Forbidden landuse: " + landuse + " (cultivated/agricultural)";
      } else if (FORBIDDEN_RESIDENTIAL.contains(landuse)) {
        return "Forbidden landuse: " + landuse + " (residential/commercial/industrial)";
      } else if (FORBIDDEN_RESTRICTED.contains(landuse)) {
        return "Forbidden landuse: " + landuse + " (restricted access)";
      }
    }
    
    String access = wayTags.get("access");
    if (access != null && ("private".equals(access) || "no".equals(access))) {
      return "Forbidden access: " + access;
    }
    
    return null;
  }
  
  /**
   * Get all forbidden landuse types
   * 
   * @return Set of forbidden landuse types
   */
  public static Set<String> getForbiddenLanduseTypes() {
    return new HashSet<>(ALL_FORBIDDEN_LANDUSE);
  }
  
  /**
   * Check if a specific landuse type is forbidden
   * 
   * @param landuseType Landuse type to check
   * @return true if forbidden, false otherwise
   */
  public static boolean isForbiddenLanduse(String landuseType) {
    if (landuseType == null) {
      return false;
    }
    return ALL_FORBIDDEN_LANDUSE.contains(landuseType);
  }
}

