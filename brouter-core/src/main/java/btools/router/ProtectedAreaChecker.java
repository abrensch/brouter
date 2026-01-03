/**
 * Checks protected area restrictions based on IUCN protection classes and designations
 * 
 * @author BRouter
 */
package btools.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ProtectedAreaChecker {
  
  /**
   * IUCN Protection Class definitions
   */
  public enum ProtectionClass {
    STRICT_NATURE_RESERVE(1, true, "Strict nature reserve - often restricted"),
    NATIONAL_PARK(2, false, "National park - usually allowed with restrictions"),
    NATURAL_MONUMENT(3, false, "Natural monument - check local rules"),
    HABITAT_SPECIES_MANAGEMENT(4, false, "Habitat/species management - check local rules"),
    PROTECTED_LANDSCAPE(5, false, "Protected landscape - check local rules"),
    PROTECTED_AREA_SUSTAINABLE_USE(6, false, "Protected area with sustainable use - usually allowed");
    
    public final int classNumber;
    public final boolean isRestricted;
    public final String description;
    
    ProtectionClass(int classNumber, boolean isRestricted, String description) {
      this.classNumber = classNumber;
      this.isRestricted = isRestricted;
      this.description = description;
    }
    
    public static ProtectionClass fromNumber(int number) {
      for (ProtectionClass pc : values()) {
        if (pc.classNumber == number) {
          return pc;
        }
      }
      return null;
    }
  }
  
  /**
   * Designations that are usually allowed
   */
  private static final Set<String> ALLOWED_DESIGNATIONS = new HashSet<>(Arrays.asList(
    "wilderness_area"
  ));
  
  /**
   * Designations that require checking local rules
   */
  private static final Set<String> RESTRICTED_DESIGNATIONS = new HashSet<>(Arrays.asList(
    "landscape_protection_area"
  ));
  
  /**
   * Check if access is allowed based on protection class
   * 
   * @param wayTags Way tags
   * @return true if access is allowed, false if restricted
   */
  public static boolean isAccessAllowedByProtectionClass(Map<String, String> wayTags) {
    if (wayTags == null) {
      return true; // Default: allow if no restrictions
    }
    
    String protectClassStr = wayTags.get("protect_class");
    if (protectClassStr != null) {
      try {
        int protectClass = Integer.parseInt(protectClassStr);
        ProtectionClass pc = ProtectionClass.fromNumber(protectClass);
        
        if (pc != null && pc.isRestricted) {
          return false; // Class 1 (Strict nature reserve) is restricted
        }
        
        // Classes 2-6: Check for explicit restrictions
        // If protect_class exists, check for additional restrictions
        String access = wayTags.get("access");
        if (access != null && ("private".equals(access) || "no".equals(access))) {
          return false;
        }
      } catch (NumberFormatException e) {
        // Invalid protect_class value, default to allow
      }
    }
    
    return true;
  }
  
  /**
   * Check if access is allowed based on designation
   * 
   * @param wayTags Way tags
   * @return true if access is allowed, false if restricted
   */
  public static boolean isAccessAllowedByDesignation(Map<String, String> wayTags) {
    if (wayTags == null) {
      return true; // Default: allow if no restrictions
    }
    
    String designation = wayTags.get("designation");
    if (designation == null) {
      return true;
    }
    
    // Wilderness areas are usually allowed
    if (ALLOWED_DESIGNATIONS.contains(designation)) {
      // Check for explicit restrictions
      String access = wayTags.get("access");
      if (access != null && ("private".equals(access) || "no".equals(access))) {
        return false;
      }
      return true;
    }
    
    // Landscape protection areas - check local rules
    if (RESTRICTED_DESIGNATIONS.contains(designation)) {
      // Check for explicit access restrictions
      String access = wayTags.get("access");
      if (access != null && ("private".equals(access) || "no".equals(access))) {
        return false;
      }
      // If no explicit restriction, allow but may need to check local rules
      // For now, default to allow (user should check local rules)
      return true;
    }
    
    return true; // Unknown designation, default to allow
  }
  
  /**
   * Check if access is allowed considering both protection class and designation
   * 
   * @param wayTags Way tags
   * @return true if access is allowed, false if restricted
   */
  public static boolean isAccessAllowed(Map<String, String> wayTags) {
    if (!isAccessAllowedByProtectionClass(wayTags)) {
      return false;
    }
    
    if (!isAccessAllowedByDesignation(wayTags)) {
      return false;
    }
    
    return true;
  }
  
  /**
   * Get protection class information
   * 
   * @param wayTags Way tags
   * @return ProtectionClass or null if not found
   */
  public static ProtectionClass getProtectionClass(Map<String, String> wayTags) {
    if (wayTags == null) {
      return null;
    }
    
    String protectClassStr = wayTags.get("protect_class");
    if (protectClassStr != null) {
      try {
        int protectClass = Integer.parseInt(protectClassStr);
        return ProtectionClass.fromNumber(protectClass);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    
    return null;
  }
  
  /**
   * Get restriction reason
   * 
   * @param wayTags Way tags
   * @return Restriction reason, or null if no restriction
   */
  public static String getRestrictionReason(Map<String, String> wayTags) {
    if (wayTags == null) {
      return null;
    }
    
    ProtectionClass pc = getProtectionClass(wayTags);
    if (pc != null && pc.isRestricted) {
      return pc.description;
    }
    
    String designation = wayTags.get("designation");
    if (designation != null && RESTRICTED_DESIGNATIONS.contains(designation)) {
      String access = wayTags.get("access");
      if (access != null && ("private".equals(access) || "no".equals(access))) {
        return "Restricted designation: " + designation + " with access=" + access;
      }
    }
    
    return null;
  }
  
  /**
   * Check if a designation is usually allowed
   * 
   * @param designation Designation value
   * @return true if usually allowed, false if restricted or unknown
   */
  public static boolean isAllowedDesignation(String designation) {
    if (designation == null) {
      return true;
    }
    return ALLOWED_DESIGNATIONS.contains(designation);
  }
  
  /**
   * Check if a designation requires checking local rules
   * 
   * @param designation Designation value
   * @return true if requires checking local rules
   */
  public static boolean requiresLocalRulesCheck(String designation) {
    if (designation == null) {
      return false;
    }
    return RESTRICTED_DESIGNATIONS.contains(designation);
  }
}

