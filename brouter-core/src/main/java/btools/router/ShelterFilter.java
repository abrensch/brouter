/**
 * Filters shelters and huts with distance-based filtering
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class ShelterFilter {
  
  private static final double MIN_DISTANCE_BETWEEN_SHELTERS = 1300.0; // meters
  private static final int MAX_SHELTERS_PER_REST_STOP = 5;
  
  /**
   * Shelter types with priority
   */
  public enum ShelterType {
    ALPINE_HUT(1, "alpine_hut"),
    WILDERNESS_HUT(2, "wilderness_hut"),
    BASIC_HUT(3, "basic_hut"),
    SHELTER(4, "shelter"),
    CAMP_SITE(5, "camp_site"),
    PICNIC(6, "picnic_table");
    
    public final int priority;
    public final String osmValue;
    
    ShelterType(int priority, String osmValue) {
      this.priority = priority;
      this.osmValue = osmValue;
    }
  }
  
  /**
   * Represents a shelter or hut
   */
  public static class Shelter {
    public OsmPos location;
    public ShelterType type;
    public String name;
    public double distanceFromRoute;
    public boolean locked;
    public String countryCode;
    
    public Shelter(OsmPos location, ShelterType type, String name, double distanceFromRoute) {
      this.location = location;
      this.type = type;
      this.name = name;
      this.distanceFromRoute = distanceFromRoute;
      this.locked = false;
    }
  }
  
  /**
   * Filter shelters to prevent clutter
   * Ensures minimum distance between shelters and limits count
   * 
   * @param shelters List of shelters to filter
   * @return Filtered list of shelters
   */
  public List<Shelter> filterShelters(List<Shelter> shelters) {
    if (shelters == null || shelters.isEmpty()) {
      return new ArrayList<>();
    }
    
    // Sort by priority, then distance
    shelters.sort(Comparator
        .comparing((Shelter s) -> s.type.priority)
        .thenComparing(s -> s.distanceFromRoute));
    
    List<Shelter> filtered = new ArrayList<>();
    
    for (Shelter shelter : shelters) {
      // Skip locked basic huts
      if (shelter.type == ShelterType.BASIC_HUT && shelter.locked) {
        continue;
      }
      
      // Check minimum distance from already filtered shelters
      if (isMinDistanceAway(shelter, filtered, MIN_DISTANCE_BETWEEN_SHELTERS)) {
        filtered.add(shelter);
        if (filtered.size() >= MAX_SHELTERS_PER_REST_STOP) {
          break;
        }
      }
    }
    
    return filtered;
  }
  
  /**
   * Check if shelter is minimum distance away from existing shelters
   */
  private boolean isMinDistanceAway(Shelter shelter, List<Shelter> existing, double minDistance) {
    if (existing.isEmpty()) {
      return true;
    }
    
    for (Shelter existingShelter : existing) {
      double distance = calculateDistance(shelter.location, existingShelter.location);
      if (distance < minDistance) {
        return false;
      }
    }
    
    return true;
  }
  
  /**
   * Calculate distance between two points (meters)
   */
  private double calculateDistance(OsmPos p1, OsmPos p2) {
    if (p1 == null || p2 == null) {
      return Double.MAX_VALUE;
    }
    
    return CheapRuler.distance(p1.getILat(), p1.getILon(), p2.getILat(), p2.getILon());
  }
  
  /**
   * Get shelter priority (lower is better)
   */
  public int getShelterPriority(Shelter shelter) {
    return shelter.type.priority;
  }
  
  /**
   * Filter shelters by country
   */
  public List<Shelter> filterByCountry(List<Shelter> shelters, String countryCode) {
    if (countryCode == null || countryCode.isEmpty()) {
      return shelters;
    }
    
    List<Shelter> filtered = new ArrayList<>();
    for (Shelter shelter : shelters) {
      if (countryCode.equals(shelter.countryCode)) {
        filtered.add(shelter);
      }
    }
    return filtered;
  }
}

