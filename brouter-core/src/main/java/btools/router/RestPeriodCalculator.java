/**
 * Calculator for EU Regulation EC 561/2006 rest period compliance
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class RestPeriodCalculator {
  
  // EU Regulation EC 561/2006 constants
  public static final double MAX_DRIVING_TIME_BEFORE_BREAK = 4.5 * 3600; // 4.5 hours in seconds
  public static final double MANDATORY_BREAK_DURATION = 45 * 60; // 45 minutes in seconds
  public static final double MIN_BREAK_DURATION = 15 * 60; // 15 minutes in seconds
  public static final double SECOND_BREAK_DURATION = 30 * 60; // 30 minutes in seconds
  public static final double MAX_DAILY_DRIVING = 9 * 3600; // 9 hours in seconds
  public static final double MAX_DAILY_DRIVING_EXTENDED = 10 * 3600; // 10 hours (max 2x per week)
  public static final double DAILY_REST_DURATION = 11 * 3600; // 11 hours in seconds
  public static final double DAILY_REST_DURATION_REDUCED = 9 * 3600; // 9 hours (max 3x per week)
  public static final double WEEKLY_REST_DURATION = 45 * 3600; // 45 hours in seconds
  public static final int MAX_DAYS_BEFORE_WEEKLY_REST = 6;
  
  /**
   * Calculate required rest stops for a route based on driving time
   * 
   * @param totalDrivingTime Total driving time in seconds
   * @return List of required rest stops with their positions (in seconds from start)
   */
  public static List<RestStopRequirement> calculateRestStops(double totalDrivingTime) {
    List<RestStopRequirement> restStops = new ArrayList<>();
    
    if (totalDrivingTime <= MAX_DRIVING_TIME_BEFORE_BREAK) {
      return restStops; // No rest required
    }
    
    double currentTime = 0;
    double accumulatedDrivingTime = 0;
    int breakCount = 0;
    
    while (currentTime < totalDrivingTime) {
      double remainingTime = totalDrivingTime - currentTime;
      
      if (accumulatedDrivingTime >= MAX_DRIVING_TIME_BEFORE_BREAK) {
        // Need a break
        RestStopRequirement restStop = new RestStopRequirement();
        restStop.position = currentTime;
        restStop.drivingTimeBeforeStop = accumulatedDrivingTime;
        
        // First break can be split: 15min + 30min, or single 45min
        if (breakCount == 0) {
          restStop.duration = MANDATORY_BREAK_DURATION;
          restStop.canSplit = true;
        } else {
          restStop.duration = MANDATORY_BREAK_DURATION;
          restStop.canSplit = false;
        }
        
        restStops.add(restStop);
        accumulatedDrivingTime = 0; // Reset after break
        breakCount++;
        currentTime += restStop.duration;
      } else if (remainingTime <= MAX_DRIVING_TIME_BEFORE_BREAK - accumulatedDrivingTime) {
        // Can complete route without additional break
        break;
      } else {
        // Continue driving
        double driveSegment = Math.min(
          MAX_DRIVING_TIME_BEFORE_BREAK - accumulatedDrivingTime,
          remainingTime
        );
        accumulatedDrivingTime += driveSegment;
        currentTime += driveSegment;
      }
    }
    
    return restStops;
  }
  
  /**
   * Calculate cumulative driving time at each point in the route
   * 
   * @param track The route track
   * @return Array of cumulative driving times (in seconds) for each node
   */
  public static double[] calculateCumulativeDrivingTime(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return new double[0];
    }
    
    double[] cumulativeTime = new double[track.nodes.size()];
    double totalTime = 0;
    double lastBreakTime = 0;
    double accumulatedDriving = 0;
    
    for (int i = 0; i < track.nodes.size(); i++) {
      OsmPathElement node = track.nodes.get(i);
      double segmentTime = node.getTime() - (i > 0 ? track.nodes.get(i - 1).getTime() : 0);
      
      if (segmentTime > 0) {
        accumulatedDriving += segmentTime;
        
        // Check if we need a break
        if (accumulatedDriving >= MAX_DRIVING_TIME_BEFORE_BREAK) {
          lastBreakTime += MANDATORY_BREAK_DURATION;
          accumulatedDriving = 0;
        }
      }
      
      totalTime = node.getTime() + lastBreakTime;
      cumulativeTime[i] = totalTime;
    }
    
    return cumulativeTime;
  }
  
  /**
   * Get driving time statistics for a route
   */
  public static class DrivingTimeStats {
    public double totalDrivingTime;
    public double totalTimeWithBreaks;
    public int requiredBreaks;
    public boolean requiresDailyRest;
    public boolean requiresWeeklyRest;
    public List<RestStopRequirement> restStops;
  }
  
  public static DrivingTimeStats calculateStats(OsmTrack track) {
    DrivingTimeStats stats = new DrivingTimeStats();
    
    if (track == null || track.nodes == null || track.nodes.isEmpty()) {
      return stats;
    }
    
    double totalTime = (double) track.getTotalSeconds();
    stats.totalDrivingTime = totalTime;
    stats.restStops = calculateRestStops(totalTime);
    stats.requiredBreaks = stats.restStops.size();
    stats.totalTimeWithBreaks = totalTime + (stats.requiredBreaks * MANDATORY_BREAK_DURATION);
    stats.requiresDailyRest = totalTime > MAX_DAILY_DRIVING;
    stats.requiresWeeklyRest = false; // Would need weekly tracking
    
    return stats;
  }
  
  /**
   * Calculate suggested breaks for cars (non-mandatory, but recommended)
   * 
   * @param totalDrivingTime Total driving time in seconds
   * @return List of suggested rest stops
   */
  public static List<RestStopRequirement> calculateCarBreaks(double totalDrivingTime) {
    List<RestStopRequirement> restStops = new ArrayList<>();
    
    if (totalDrivingTime <= MAX_DRIVING_TIME_BEFORE_BREAK) {
      return restStops; // No break needed
    }
    
    double currentTime = 0;
    double accumulatedDrivingTime = 0;
    
    while (currentTime < totalDrivingTime) {
      double remainingTime = totalDrivingTime - currentTime;
      
      if (accumulatedDrivingTime >= MAX_DRIVING_TIME_BEFORE_BREAK) {
        // Suggest a break
        RestStopRequirement restStop = new RestStopRequirement();
        restStop.position = currentTime;
        restStop.drivingTimeBeforeStop = accumulatedDrivingTime;
        restStop.duration = MANDATORY_BREAK_DURATION; // 45 minutes
        restStop.canSplit = true; // Can be split for cars
        restStop.isMandatory = false; // Suggested, not mandatory
        restStops.add(restStop);
        accumulatedDrivingTime = 0;
        currentTime += restStop.duration;
      } else if (remainingTime <= MAX_DRIVING_TIME_BEFORE_BREAK - accumulatedDrivingTime) {
        break;
      } else {
        double driveSegment = Math.min(
          MAX_DRIVING_TIME_BEFORE_BREAK - accumulatedDrivingTime,
          remainingTime
        );
        accumulatedDrivingTime += driveSegment;
        currentTime += driveSegment;
      }
    }
    
    // Check for daily rest (after 9 hours)
    if (totalDrivingTime > MAX_DAILY_DRIVING) {
      RestStopRequirement dailyRest = new RestStopRequirement();
      dailyRest.position = totalDrivingTime;
      dailyRest.drivingTimeBeforeStop = totalDrivingTime;
      dailyRest.duration = DAILY_REST_DURATION; // 11 hours
      dailyRest.isMandatory = false; // Suggested
      dailyRest.isDailyRest = true;
      restStops.add(dailyRest);
    }
    
    return restStops;
  }
  
  /**
   * Calculate weekly rest suggestion (after 6 days)
   * Note: This requires tracking across multiple routes/days
   */
  public static RestStopRequirement calculateWeeklyRest(int daysDriving) {
    if (daysDriving >= MAX_DAYS_BEFORE_WEEKLY_REST) {
      RestStopRequirement weeklyRest = new RestStopRequirement();
      weeklyRest.duration = WEEKLY_REST_DURATION; // 45 hours
      weeklyRest.isMandatory = false; // Suggested
      weeklyRest.isWeeklyRest = true;
      return weeklyRest;
    }
    return null;
  }
  
  /**
   * Represents a required rest stop
   */
  public static class RestStopRequirement {
    public double position; // Position in route (seconds from start)
    public double drivingTimeBeforeStop; // Cumulative driving time before this stop
    public double duration; // Required rest duration in seconds
    public boolean canSplit; // Whether break can be split (15min + 30min)
    public double preferredDistance; // Preferred distance from start (meters)
    public boolean isMandatory = true; // Whether this is mandatory (trucks) or suggested (cars)
    public boolean isDailyRest = false; // Whether this is a daily rest
    public boolean isWeeklyRest = false; // Whether this is a weekly rest
  }
}

