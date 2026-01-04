/**
 * Checks if a guide is required or recommended for a route
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GuideRequirementChecker {
  
  /**
   * Alpine grades that require a guide (D and higher)
   */
  private static final Set<String> GUIDE_REQUIRED_ALPINE_GRADES = new HashSet<>(Arrays.asList(
    "D", "D+", "D-",
    "E", "E+", "E-",
    "F", "F+", "F-",
    "G", "G+", "G-"
  ));
  
  /**
   * SAC scale values that require a guide
   */
  private static final Set<String> GUIDE_REQUIRED_SAC_SCALES = new HashSet<>(Arrays.asList(
    "difficult_alpine_hiking",
    "demanding_alpine_hiking"
  ));
  
  /**
   * Check if a guide is required or recommended
   * 
   * @param wayTags Way tags
   * @return true if guide is required/recommended, false otherwise
   */
  public static boolean isGuideRequired(Map<String, String> wayTags) {
    if (wayTags == null) {
      return false;
    }
    
    // Primary indicator: guide tag
    String guide = wayTags.get("guide");
    if ("yes".equals(guide) || "recommended".equals(guide)) {
      return true;
    }
    
    // SAC scale indicators
    String sacScale = wayTags.get("sac_scale");
    if (sacScale != null && GUIDE_REQUIRED_SAC_SCALES.contains(sacScale)) {
      return true;
    }
    
    // Supervised mandatory
    String supervised = wayTags.get("supervised");
    if ("mandatory".equals(supervised)) {
      return true;
    }
    
    // Alpine grade D or higher
    String alpineGrade = wayTags.get("alpine_grade");
    if (alpineGrade != null && isGuideRequiredAlpineGrade(alpineGrade)) {
      return true;
    }
    
    // Hazard: crevasse + trail_visibility=no
    String hazard = wayTags.get("hazard");
    String trailVisibility = wayTags.get("trail_visibility");
    if ("crevasse".equals(hazard) && "no".equals(trailVisibility)) {
      return true;
    }
    
    // Climbing=rope on glacier routes
    String climbing = wayTags.get("climbing");
    String natural = wayTags.get("natural");
    if ("rope".equals(climbing) && "glacier".equals(natural)) {
      return true;
    }
    
    // Via ferrata
    String highway = wayTags.get("highway");
    if ("via_ferrata".equals(highway)) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Check if alpine grade requires a guide (D or higher)
   * 
   * @param alpineGrade Alpine grade (e.g., "D", "D+", "E", etc.)
   * @return true if grade requires guide, false otherwise
   */
  private static boolean isGuideRequiredAlpineGrade(String alpineGrade) {
    if (alpineGrade == null) {
      return false;
    }
    
    // Check exact match
    if (GUIDE_REQUIRED_ALPINE_GRADES.contains(alpineGrade)) {
      return true;
    }
    
    // Check if grade starts with D, E, F, or G (and higher)
    String upperGrade = alpineGrade.toUpperCase().trim();
    if (upperGrade.startsWith("D") || 
        upperGrade.startsWith("E") || 
        upperGrade.startsWith("F") || 
        upperGrade.startsWith("G")) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Get the reason why a guide is required
   * 
   * @param wayTags Way tags
   * @return Reason string, or null if guide not required
   */
  public static String getGuideRequirementReason(Map<String, String> wayTags) {
    if (wayTags == null) {
      return null;
    }
    
    // Check each indicator and return the first match
    String guide = wayTags.get("guide");
    if ("yes".equals(guide)) {
      return "Guide required: guide=yes";
    }
    if ("recommended".equals(guide)) {
      return "Guide recommended: guide=recommended";
    }
    
    String sacScale = wayTags.get("sac_scale");
    if (sacScale != null && GUIDE_REQUIRED_SAC_SCALES.contains(sacScale)) {
      return "Guide required: SAC scale " + sacScale;
    }
    
    String supervised = wayTags.get("supervised");
    if ("mandatory".equals(supervised)) {
      return "Guide required: supervised=mandatory";
    }
    
    String alpineGrade = wayTags.get("alpine_grade");
    if (alpineGrade != null && isGuideRequiredAlpineGrade(alpineGrade)) {
      return "Guide required: Alpine grade " + alpineGrade + " (D or higher)";
    }
    
    String hazard = wayTags.get("hazard");
    String trailVisibility = wayTags.get("trail_visibility");
    if ("crevasse".equals(hazard) && "no".equals(trailVisibility)) {
      return "Guide required: Crevasse hazard with no trail visibility";
    }
    
    String climbing = wayTags.get("climbing");
    String natural = wayTags.get("natural");
    if ("rope".equals(climbing) && "glacier".equals(natural)) {
      return "Guide required: Rope climbing on glacier route";
    }
    
    String highway = wayTags.get("highway");
    if ("via_ferrata".equals(highway)) {
      return "Guide required: Via ferrata route";
    }
    
    return null;
  }
  
  /**
   * Get all guide requirement indicators for a way
   * 
   * @param wayTags Way tags
   * @return Array of indicator strings
   */
  public static String[] getGuideIndicators(Map<String, String> wayTags) {
    if (wayTags == null) {
      return new String[0];
    }
    
    java.util.List<String> indicators = new java.util.ArrayList<>();
    
    String guide = wayTags.get("guide");
    if ("yes".equals(guide) || "recommended".equals(guide)) {
      indicators.add("guide=" + guide);
    }
    
    String sacScale = wayTags.get("sac_scale");
    if (sacScale != null && GUIDE_REQUIRED_SAC_SCALES.contains(sacScale)) {
      indicators.add("sac_scale=" + sacScale);
    }
    
    String supervised = wayTags.get("supervised");
    if ("mandatory".equals(supervised)) {
      indicators.add("supervised=mandatory");
    }
    
    String alpineGrade = wayTags.get("alpine_grade");
    if (alpineGrade != null && isGuideRequiredAlpineGrade(alpineGrade)) {
      indicators.add("alpine_grade=" + alpineGrade);
    }
    
    String hazard = wayTags.get("hazard");
    String trailVisibility = wayTags.get("trail_visibility");
    if ("crevasse".equals(hazard) && "no".equals(trailVisibility)) {
      indicators.add("hazard=crevasse + trail_visibility=no");
    }
    
    String climbing = wayTags.get("climbing");
    String natural = wayTags.get("natural");
    if ("rope".equals(climbing) && "glacier".equals(natural)) {
      indicators.add("climbing=rope on glacier");
    }
    
    String highway = wayTags.get("highway");
    if ("via_ferrata".equals(highway)) {
      indicators.add("highway=via_ferrata");
    }
    
    return indicators.toArray(new String[0]);
  }
  
  /**
   * Check if route requires a guide (for route validation)
   * 
   * @param routeSegments List of way segments with tags
   * @return true if any segment requires a guide
   */
  public static boolean routeRequiresGuide(java.util.List<Map<String, String>> routeSegments) {
    if (routeSegments == null) {
      return false;
    }
    
    for (Map<String, String> segmentTags : routeSegments) {
      if (isGuideRequired(segmentTags)) {
        return true;
      }
    }
    
    return false;
  }
}

