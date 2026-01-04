/**
 * Checks truck physical dimension restrictions against OSM way tags
 * 
 * @author BRouter
 */
package btools.router;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;

public class TruckDimensionChecker {
  
  /**
   * Truck dimensions configuration
   */
  public static class TruckDimensions {
    public double height;      // meters
    public double width;       // meters
    public double length;      // meters
    public double weight;      // kg
    public double axleLoad;    // kg per axle
    
    public TruckDimensions(double height, double width, double length, double weight, double axleLoad) {
      this.height = height;
      this.width = width;
      this.length = length;
      this.weight = weight;
      this.axleLoad = axleLoad;
    }
  }
  
  /**
   * Check if a way is passable for the given truck dimensions
   * 
   * @param wayTags Way tags containing restriction information
   * @param dims Truck dimensions
   * @return true if way is passable, false if blocked
   */
  public static boolean checkDimensions(java.util.Map<String, String> wayTags, TruckDimensions dims) {
    if (wayTags == null || dims == null) {
      return true; // No restrictions if no data
    }
    
    // Check height restrictions
    if (isHeightRestricted(wayTags, dims.height)) {
      return false;
    }
    
    // Check width restrictions
    if (isWidthRestricted(wayTags, dims.width)) {
      return false;
    }
    
    // Check length restrictions
    if (isLengthRestricted(wayTags, dims.length)) {
      return false;
    }
    
    // Check weight restrictions
    if (isWeightRestricted(wayTags, dims.weight)) {
      return false;
    }
    
    // Check axle load restrictions
    if (isAxleLoadRestricted(wayTags, dims.axleLoad)) {
      return false;
    }
    
    return true;
  }
  
  /**
   * Check height restriction
   */
  private static boolean isHeightRestricted(java.util.Map<String, String> tags, double truckHeight) {
    String maxHeight = tags.get("maxheight");
    String maxHeightPhysical = tags.get("maxheight:physical");
    
    if (maxHeight != null) {
      double restriction = parseDimension(maxHeight);
      if (restriction > 0 && truckHeight > restriction) {
        return true;
      }
    }
    
    if (maxHeightPhysical != null) {
      double restriction = parseDimension(maxHeightPhysical);
      if (restriction > 0 && truckHeight > restriction) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Check width restriction
   */
  private static boolean isWidthRestricted(java.util.Map<String, String> tags, double truckWidth) {
    String maxWidth = tags.get("maxwidth");
    String maxWidthPhysical = tags.get("maxwidth:physical");
    
    if (maxWidth != null) {
      double restriction = parseDimension(maxWidth);
      if (restriction > 0 && truckWidth > restriction) {
        return true;
      }
    }
    
    if (maxWidthPhysical != null) {
      double restriction = parseDimension(maxWidthPhysical);
      if (restriction > 0 && truckWidth > restriction) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Check length restriction
   */
  private static boolean isLengthRestricted(java.util.Map<String, String> tags, double truckLength) {
    String maxLength = tags.get("maxlength");
    String maxLengthPhysical = tags.get("maxlength:physical");
    
    if (maxLength != null) {
      double restriction = parseDimension(maxLength);
      if (restriction > 0 && truckLength > restriction) {
        return true;
      }
    }
    
    if (maxLengthPhysical != null) {
      double restriction = parseDimension(maxLengthPhysical);
      if (restriction > 0 && truckLength > restriction) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Check weight restriction
   */
  private static boolean isWeightRestricted(java.util.Map<String, String> tags, double truckWeight) {
    String maxWeight = tags.get("maxweight");
    String maxWeightHGV = tags.get("maxweight:hgv");
    
    if (maxWeight != null) {
      double restriction = parseWeight(maxWeight);
      if (restriction > 0 && truckWeight > restriction) {
        return true;
      }
    }
    
    if (maxWeightHGV != null) {
      double restriction = parseWeight(maxWeightHGV);
      if (restriction > 0 && truckWeight > restriction) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Check axle load restriction
   */
  private static boolean isAxleLoadRestricted(java.util.Map<String, String> tags, double truckAxleLoad) {
    String maxAxleLoad = tags.get("maxaxleload");
    
    if (maxAxleLoad != null) {
      double restriction = parseWeight(maxAxleLoad);
      if (restriction > 0 && truckAxleLoad > restriction) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Parse dimension value (meters)
   * Supports formats: "4.0", "4.0 m", "400 cm", "13'2\""
   */
  private static double parseDimension(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    
    value = value.trim().toLowerCase();
    
    // Remove units and convert
    if (value.endsWith("m") || value.endsWith(" m")) {
      value = value.replaceAll("[^0-9.]", "");
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    
    if (value.endsWith("cm") || value.endsWith(" cm")) {
      value = value.replaceAll("[^0-9.]", "");
      try {
        return Double.parseDouble(value) / 100.0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    
    // Try parsing as number (assume meters)
    try {
      return Double.parseDouble(value.replaceAll("[^0-9.]", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
  
  /**
   * Parse weight value (kg)
   * Supports formats: "40000", "40 t", "40 tonnes", "40000 kg"
   */
  private static double parseWeight(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    
    value = value.trim().toLowerCase();
    
    // Remove units and convert
    if (value.endsWith("t") || value.endsWith("tonnes") || value.endsWith(" tonne")) {
      value = value.replaceAll("[^0-9.]", "");
      try {
        return Double.parseDouble(value) * 1000.0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    
    if (value.endsWith("kg") || value.endsWith(" kg")) {
      value = value.replaceAll("[^0-9.]", "");
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    
    // Try parsing as number (assume kg)
    try {
      return Double.parseDouble(value.replaceAll("[^0-9.]", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
  
  /**
   * Get restriction reason if blocked
   */
  public static String getRestrictionReason(java.util.Map<String, String> wayTags, TruckDimensions dims) {
    if (wayTags == null || dims == null) {
      return null;
    }
    
    if (isHeightRestricted(wayTags, dims.height)) {
      return "Height restriction: " + wayTags.get("maxheight") + " (truck: " + dims.height + "m)";
    }
    
    if (isWidthRestricted(wayTags, dims.width)) {
      return "Width restriction: " + wayTags.get("maxwidth") + " (truck: " + dims.width + "m)";
    }
    
    if (isLengthRestricted(wayTags, dims.length)) {
      return "Length restriction: " + wayTags.get("maxlength") + " (truck: " + dims.length + "m)";
    }
    
    if (isWeightRestricted(wayTags, dims.weight)) {
      return "Weight restriction: " + wayTags.get("maxweight") + " (truck: " + dims.weight + "kg)";
    }
    
    if (isAxleLoadRestricted(wayTags, dims.axleLoad)) {
      return "Axle load restriction: " + wayTags.get("maxaxleload") + " (truck: " + dims.axleLoad + "kg)";
    }
    
    return null;
  }
}

