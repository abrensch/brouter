/**
 * Checks path priority based on fixme and trailblazed tags
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Map;

public class PathPriorityChecker {
  
  /**
   * Calculate penalty for fixme paths
   * These paths should be completely avoided (very high cost)
   * 
   * @param wayTags Way tags
   * @return Penalty value (positive = higher cost, 0 = no penalty)
   *         Returns 1000.0 to effectively block paths with fixme tags
   */
  public static double getFixmePenalty(Map<String, String> wayTags) {
    if (wayTags == null) {
      return 0.0;
    }
    
    String fixme = wayTags.get("fixme");
    if (fixme == null || fixme.trim().isEmpty()) {
      return 0.0;
    }
    
    // Any fixme tag indicates the path needs attention - avoid it completely
    // Return very high penalty (1000.0 = 100000% cost increase) to effectively block
    return 1000.0;
  }
  
  /**
   * Calculate bonus for trailblazed=yes paths
   * These paths should have higher priority (lower cost)
   * 
   * @param wayTags Way tags
   * @return Bonus value (negative = lower cost, 0 = no bonus)
   */
  public static double getTrailblazedBonus(Map<String, String> wayTags) {
    if (wayTags == null) {
      return 0.0;
    }
    
    String trailblazed = wayTags.get("trailblazed");
    if (trailblazed == null) {
      return 0.0;
    }
    
    // Check for trailblazed=yes
    if ("yes".equalsIgnoreCase(trailblazed)) {
      // Apply bonus: -0.2 = 20% cost reduction (higher priority)
      return -0.2;
    }
    
    return 0.0;
  }
  
  /**
   * Get combined path priority adjustment
   * Combines fixme penalty and trailblazed bonus
   * 
   * @param wayTags Way tags
   * @return Combined adjustment (negative = bonus, positive = penalty, 0 = no change)
   */
  public static double getPathPriorityAdjustment(Map<String, String> wayTags) {
    double fixmePenalty = getFixmePenalty(wayTags);
    double trailblazedBonus = getTrailblazedBonus(wayTags);
    return fixmePenalty + trailblazedBonus;
  }
  
  /**
   * Check if path has fixme=resurvey;refine
   * 
   * @param wayTags Way tags
   * @return true if path has fixme tag indicating resurvey/refine needed
   */
  public static boolean hasFixmeResurveyRefine(Map<String, String> wayTags) {
    return getFixmePenalty(wayTags) > 0.0;
  }
  
  /**
   * Check if path is trailblazed
   * 
   * @param wayTags Way tags
   * @return true if path has trailblazed=yes
   */
  public static boolean isTrailblazed(Map<String, String> wayTags) {
    return getTrailblazedBonus(wayTags) < 0.0;
  }
  
  /**
   * Calculate route preference bonus based on routing mode
   * For walking/foot mode: prefer route=hiking routes
   * For cycling/bike mode: prefer route=bicycle routes
   * 
   * @param wayTags Way tags
   * @param footMode True if routing for foot/walking
   * @param bikeMode True if routing for bicycle/cycling
   * @return Bonus value (negative = lower cost, 0 = no bonus)
   *         Returns -0.3 for route=hiking when footMode is true
   *         Returns -0.3 for route=bicycle when bikeMode is true
   */
  public static double getRoutePreferenceBonus(Map<String, String> wayTags, boolean footMode, boolean bikeMode) {
    if (wayTags == null) {
      return 0.0;
    }
    
    String route = wayTags.get("route");
    if (route == null || route.trim().isEmpty()) {
      return 0.0;
    }
    
    // For walking/foot mode: prefer route=hiking routes
    if (footMode && "hiking".equalsIgnoreCase(route)) {
      // Apply bonus: -0.3 = 30% cost reduction (higher priority)
      return -0.3;
    }
    
    // For cycling/bike mode: prefer route=bicycle routes
    if (bikeMode && "bicycle".equalsIgnoreCase(route)) {
      // Apply bonus: -0.3 = 30% cost reduction (higher priority)
      return -0.3;
    }
    
    return 0.0;
  }
  
  /**
   * Get combined path priority adjustment including route preferences
   * Combines fixme penalty, trailblazed bonus, and route preference bonus
   * 
   * @param wayTags Way tags
   * @param footMode True if routing for foot/walking
   * @param bikeMode True if routing for bicycle/cycling
   * @return Combined adjustment (negative = bonus, positive = penalty, 0 = no change)
   */
  public static double getPathPriorityAdjustmentWithRoutePreference(Map<String, String> wayTags, boolean footMode, boolean bikeMode) {
    double fixmePenalty = getFixmePenalty(wayTags);
    double trailblazedBonus = getTrailblazedBonus(wayTags);
    double routePreferenceBonus = getRoutePreferenceBonus(wayTags, footMode, bikeMode);
    return fixmePenalty + trailblazedBonus + routePreferenceBonus;
  }
}

