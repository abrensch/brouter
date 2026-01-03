/**
 * Detects country borders and filters POIs by country
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmPos;

public class CountryBorderFilter {
  
  /**
   * Detect if route is within a single country
   * 
   * @param start Start position
   * @param end End position
   * @param viaPoints Via points
   * @return Country code if single country, null if multi-country
   */
  public String detectSingleCountry(OsmPos start, OsmPos end, List<MatchedWaypoint> viaPoints) {
    String startCountry = reverseGeocode(start);
    String endCountry = reverseGeocode(end);
    
    if (startCountry == null || endCountry == null) {
      return null; // Cannot determine
    }
    
    if (!startCountry.equals(endCountry)) {
      return null; // Multi-country route
    }
    
    // Check via points
    if (viaPoints != null) {
      for (MatchedWaypoint via : viaPoints) {
        String viaCountry = reverseGeocode(via.waypoint);
        if (viaCountry == null || !viaCountry.equals(startCountry)) {
          return null; // Multi-country route
        }
      }
    }
    
    return startCountry; // Single country
  }
  
  /**
   * Filter POIs by country code
   */
  public <T extends POI> List<T> filterPOIsByCountry(List<T> pois, String countryCode) {
    if (countryCode == null || countryCode.isEmpty()) {
      return pois;
    }
    
    return pois.stream()
        .filter(poi -> countryCode.equals(poi.getCountryCode()))
        .collect(Collectors.toList());
  }
  
  /**
   * Reverse geocode position to country code
   * Uses coordinate-based approximation for common countries
   * For full accuracy, would need OSM boundary relation data
   */
  private String reverseGeocode(OsmPos position) {
    if (position == null) {
      return null;
    }
    
    int ilon = position.getILon();
    int ilat = position.getILat();
    
    // Convert internal coordinates to degrees
    double lon = ilon / 1000000.0;
    double lat = ilat / 1000000.0;
    
    // Coordinate-based country detection (approximation)
    // This covers major countries - for full accuracy, use OSM boundary relations
    
    // Norway: 4.5°E to 31.5°E, 57.9°N to 81.0°N
    if (lon >= 4.5 && lon <= 31.5 && lat >= 57.9 && lat <= 81.0) {
      return "NO";
    }
    
    // Sweden: 11.0°E to 24.2°E, 55.3°N to 69.1°N
    if (lon >= 11.0 && lon <= 24.2 && lat >= 55.3 && lat <= 69.1) {
      return "SE";
    }
    
    // Denmark: 8.0°E to 15.2°E, 54.5°N to 57.8°N
    if (lon >= 8.0 && lon <= 15.2 && lat >= 54.5 && lat <= 57.8) {
      return "DK";
    }
    
    // Finland: 20.5°E to 31.6°E, 59.8°N to 70.1°N
    if (lon >= 20.5 && lon <= 31.6 && lat >= 59.8 && lat <= 70.1) {
      return "FI";
    }
    
    // Germany: 5.9°E to 15.0°E, 47.3°N to 55.1°N
    if (lon >= 5.9 && lon <= 15.0 && lat >= 47.3 && lat <= 55.1) {
      return "DE";
    }
    
    // France: -5.1°E to 9.6°E, 41.3°N to 51.1°N
    if (lon >= -5.1 && lon <= 9.6 && lat >= 41.3 && lat <= 51.1) {
      return "FR";
    }
    
    // UK: -8.2°E to 1.8°E, 49.9°N to 60.8°N
    if (lon >= -8.2 && lon <= 1.8 && lat >= 49.9 && lat <= 60.8) {
      return "GB";
    }
    
    // Spain: -9.3°E to 4.3°E, 35.2°N to 43.8°N
    if (lon >= -9.3 && lon <= 4.3 && lat >= 35.2 && lat <= 43.8) {
      return "ES";
    }
    
    // Italy: 6.6°E to 18.5°E, 36.6°N to 47.1°N
    if (lon >= 6.6 && lon <= 18.5 && lat >= 36.6 && lat <= 47.1) {
      return "IT";
    }
    
    // USA: -179.1°E to -66.9°E, 18.9°N to 71.4°N (mainland + Alaska)
    if (lon >= -179.1 && lon <= -66.9 && lat >= 18.9 && lat <= 71.4) {
      return "US";
    }
    
    // Canada: -141.0°E to -52.6°E, 41.7°N to 83.1°N
    if (lon >= -141.0 && lon <= -52.6 && lat >= 41.7 && lat <= 83.1) {
      return "CA";
    }
    
    // For other countries, would need OSM boundary relation data
    // or external geocoding service integration
    return null;
  }
  
  /**
   * Base interface for POIs with country code
   */
  public interface POI {
    String getCountryCode();
  }
  
  /**
   * Check if position is within country boundary
   * Uses coordinate-based approximation
   */
  public boolean isInCountry(OsmPos position, String countryCode) {
    if (position == null || countryCode == null) {
      return false;
    }
    
    String detectedCountry = reverseGeocode(position);
    return countryCode.equals(detectedCountry);
  }
}

