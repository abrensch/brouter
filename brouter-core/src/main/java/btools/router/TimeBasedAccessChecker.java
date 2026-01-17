/**
 * Checks time-based access restrictions from OSM conditional tags
 * 
 * @author BRouter
 */
package btools.router;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeBasedAccessChecker {
  
  private LocalDateTime currentTime;
  
  public TimeBasedAccessChecker(LocalDateTime currentTime) {
    this.currentTime = currentTime;
  }
  
  /**
   * Check if access is allowed for a vehicle type at current time
   * 
   * @param wayTags Way tags containing conditional restrictions
   * @param vehicleType Vehicle type (e.g., "hgv", "motor_vehicle", "vehicle")
   * @return true if access is allowed, false if restricted
   */
  public boolean isAccessAllowed(Map<String, String> wayTags, String vehicleType) {
    if (wayTags == null || currentTime == null) {
      return true; // Default: allow if no restrictions
    }
    
    // Check conditional tags for this vehicle type
    String conditionalTag = vehicleType + ":conditional";
    String value = wayTags.get(conditionalTag);
    
    if (value != null) {
      return checkConditionalValue(value);
    }
    
    // Check general conditional access
    String accessConditional = wayTags.get("access:conditional");
    if (accessConditional != null) {
      return checkConditionalValue(accessConditional);
    }
    
    // Check maxweight:conditional (affects trucks)
    if ("hgv".equals(vehicleType) || "motor_vehicle".equals(vehicleType)) {
      String maxweightConditional = wayTags.get("maxweight:conditional");
      if (maxweightConditional != null) {
        return checkConditionalValue(maxweightConditional);
      }
    }
    
    return true; // Default: allow
  }
  
  /**
   * Parse and check conditional value
   * Format: "no @ (Mo-Fr 07:00-09:00)" or "destination @ (Mo-Fr 22:00-06:00)"
   */
  private boolean checkConditionalValue(String conditionalValue) {
    if (conditionalValue == null || conditionalValue.trim().isEmpty()) {
      return true;
    }
    
    // Parse format: "value @ (time_condition)"
    Pattern pattern = Pattern.compile("(.+?)\\s*@\\s*\\((.+?)\\)");
    Matcher matcher = pattern.matcher(conditionalValue);
    
    if (!matcher.find()) {
      return true; // Invalid format, default allow
    }
    
    String value = matcher.group(1).trim();
    String timeCondition = matcher.group(2).trim();
    
    // Check if current time matches condition
    boolean timeMatches = checkTimeCondition(timeCondition);
    
    if (!timeMatches) {
      return true; // Condition not active, default access
    }
    
    // Check if value restricts access
    return !isRestrictiveValue(value);
  }
  
  /**
   * Check if time condition matches current time
   * Supports: "Mo-Fr 07:00-09:00", "Sa-Su 22:00-06:00", "24/7", etc.
   */
  private boolean checkTimeCondition(String timeCondition) {
    if (timeCondition == null || timeCondition.trim().isEmpty()) {
      return false;
    }
    
    timeCondition = timeCondition.trim();
    
    // Handle "24/7" or "always"
    if (timeCondition.equalsIgnoreCase("24/7") || timeCondition.equalsIgnoreCase("always")) {
      return true;
    }
    
    // Parse day range and time range
    // Format: "Mo-Fr 07:00-09:00" or "Sa-Su 22:00-06:00"
    Pattern pattern = Pattern.compile("([A-Za-z]{2})-([A-Za-z]{2})\\s+(\\d{2}:\\d{2})-(\\d{2}:\\d{2})");
    Matcher matcher = pattern.matcher(timeCondition);
    
    if (!matcher.find()) {
      return false; // Invalid format
    }
    
    String dayStart = matcher.group(1);
    String dayEnd = matcher.group(2);
    String timeStart = matcher.group(3);
    String timeEnd = matcher.group(4);
    
    // Check if current day is in range
    DayOfWeek currentDay = currentTime.getDayOfWeek();
    if (!isDayInRange(currentDay, dayStart, dayEnd)) {
      return false;
    }
    
    // Check if current time is in range
    LocalTime currentLocalTime = currentTime.toLocalTime();
    LocalTime startTime = LocalTime.parse(timeStart);
    LocalTime endTime = LocalTime.parse(timeEnd);
    
    if (startTime.isBefore(endTime)) {
      // Normal range: 07:00-09:00
      return !currentLocalTime.isBefore(startTime) && !currentLocalTime.isAfter(endTime);
    } else {
      // Overnight range: 22:00-06:00
      return !currentLocalTime.isBefore(startTime) || !currentLocalTime.isAfter(endTime);
    }
  }
  
  /**
   * Check if day is in range
   */
  private boolean isDayInRange(DayOfWeek day, String dayStart, String dayEnd) {
    int dayValue = day.getValue(); // 1=Monday, 7=Sunday
    int startValue = parseDay(dayStart);
    int endValue = parseDay(dayEnd);
    
    if (startValue <= endValue) {
      return dayValue >= startValue && dayValue <= endValue;
    } else {
      // Wrap around (e.g., Sa-Su)
      return dayValue >= startValue || dayValue <= endValue;
    }
  }
  
  /**
   * Parse day abbreviation to day of week value
   */
  private int parseDay(String dayAbbr) {
    switch (dayAbbr.toUpperCase()) {
      case "MO": return 1;
      case "TU": return 2;
      case "WE": return 3;
      case "TH": return 4;
      case "FR": return 5;
      case "SA": return 6;
      case "SU": return 7;
      default: return 0;
    }
  }
  
  /**
   * Check if value restricts access
   */
  private boolean isRestrictiveValue(String value) {
    if (value == null) {
      return false;
    }
    
    value = value.trim().toLowerCase();
    
    // Restrictive values
    return value.equals("no") || 
           value.equals("private") || 
           value.equals("restricted") ||
           value.startsWith("destination") ||
           value.startsWith("delivery");
  }
  
  /**
   * Set current time for checking
   */
  public void setCurrentTime(LocalDateTime currentTime) {
    this.currentTime = currentTime;
  }
  
  /**
   * Get current time
   */
  public LocalDateTime getCurrentTime() {
    return currentTime;
  }
}

