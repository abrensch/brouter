/**
 * Calculator for country-specific camping rules
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CampingRulesCalculator {
  
  /**
   * Country-specific camping rules
   */
  public static class CampingRules {
    public int maxNights;
    public double minDistanceFromHouses; // in meters
    public boolean requiresPermission;
    public boolean designatedSitesOnly;
    public String warning;
    
    public CampingRules(int maxNights, double minDistanceFromHouses, 
                        boolean requiresPermission, boolean designatedSitesOnly, String warning) {
      this.maxNights = maxNights;
      this.minDistanceFromHouses = minDistanceFromHouses;
      this.requiresPermission = requiresPermission;
      this.designatedSitesOnly = designatedSitesOnly;
      this.warning = warning;
    }
  }
  
  // Country-specific rules
  private static final Map<String, CampingRules> RULES = new HashMap<>();
  
  static {
    // Norway: 2 nights max, 150m from houses
    RULES.put("NO", new CampingRules(2, 150.0, false, false, 
      "Norway: Maximum 2 nights, keep 150m from houses"));
    
    // Sweden: 1-2 nights, out of sight from houses
    RULES.put("SE", new CampingRules(2, 0.0, false, false, 
      "Sweden: 1-2 nights allowed, stay out of sight from houses"));
    
    // Denmark: WARNING - Designated sites only or permission required
    RULES.put("DK", new CampingRules(0, 0.0, true, true, 
      "WARNING - Denmark: Designated sites only or permission required"));
    
    // Finland: Short periods, keep distance from homes
    RULES.put("FI", new CampingRules(1, 100.0, false, false, 
      "Finland: Short periods only, keep distance from homes"));
  }
  
  /**
   * Get camping rules for a country (ISO 3166-1 alpha-2 code)
   * 
   * @param countryCode Country code (e.g., "NO", "SE", "DK", "FI")
   * @return Camping rules, or null if country not found
   */
  public static CampingRules getRules(String countryCode) {
    if (countryCode == null) {
      return null;
    }
    return RULES.get(countryCode.toUpperCase());
  }
  
  /**
   * Check if camping is allowed at a location
   * 
   * @param countryCode Country code
   * @param distanceFromHouse Distance from nearest house in meters
   * @param isDesignatedSite Whether this is a designated camping site
   * @return true if camping is allowed, false otherwise
   */
  public static boolean isCampingAllowed(String countryCode, double distanceFromHouse, boolean isDesignatedSite) {
    CampingRules rules = getRules(countryCode);
    if (rules == null) {
      return true; // Default: allow if no rules specified
    }
    
    if (rules.designatedSitesOnly && !isDesignatedSite) {
      return false;
    }
    
    if (rules.minDistanceFromHouses > 0 && distanceFromHouse < rules.minDistanceFromHouses) {
      return false;
    }
    
    return true;
  }
  
  /**
   * Get warning message for camping in a country
   * 
   * @param countryCode Country code
   * @return Warning message, or null if no warning
   */
  public static String getWarning(String countryCode) {
    CampingRules rules = getRules(countryCode);
    return rules != null ? rules.warning : null;
  }
  
  /**
   * Get maximum nights allowed for camping
   * 
   * @param countryCode Country code
   * @return Maximum nights, or -1 if not specified
   */
  public static int getMaxNights(String countryCode) {
    CampingRules rules = getRules(countryCode);
    return rules != null ? rules.maxNights : -1;
  }
  
  /**
   * Enhanced camping rules with detailed information
   */
  public static class EnhancedCampingRules {
    public String countryName;
    public String rightName;
    public String durationRule;
    public String distanceRule;
    public List<String> additionalRules;
    public boolean wildCampingAllowed;
    
    public EnhancedCampingRules(String countryName, String rightName, String durationRule,
                                String distanceRule, List<String> additionalRules, boolean wildCampingAllowed) {
      this.countryName = countryName;
      this.rightName = rightName;
      this.durationRule = durationRule;
      this.distanceRule = distanceRule;
      this.additionalRules = additionalRules;
      this.wildCampingAllowed = wildCampingAllowed;
    }
  }
  
  /**
   * Get enhanced camping rules with detailed information
   */
  public static EnhancedCampingRules getEnhancedRules(String countryCode) {
    switch (countryCode != null ? countryCode.toUpperCase() : "") {
      case "NO":
        return new EnhancedCampingRules(
          "Norway",
          "Allemannsretten",
          "Up to 2 nights in same spot",
          "Minimum 150m from houses",
          java.util.Arrays.asList("Uncultivated land only", "No fires Apr 15-Sep 15"),
          true
        );
      case "SE":
        return new EnhancedCampingRules(
          "Sweden",
          "Allemansrätten",
          "1-2 nights recommended",
          "Out of sight from houses",
          java.util.Arrays.asList("Move if asked", "Motorhomes use designated areas"),
          true
        );
      case "DK":
        return new EnhancedCampingRules(
          "Denmark",
          "No general right",
          "Use designated sites",
          "Permission required",
          java.util.Arrays.asList("Very restrictive", "No wild camping"),
          false
        );
      case "FI":
        return new EnhancedCampingRules(
          "Finland",
          "Jokaisenoikeus",
          "Short periods allowed",
          "Reasonable distance from homes",
          java.util.Arrays.asList("Wilderness areas", "No fires during warnings"),
          true
        );
      default:
        return null;
    }
  }
}

