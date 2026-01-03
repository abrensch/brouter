/**
 * Searches for POIs (water points, cabins, huts) near rest stops
 * 
 * @author BRouter
 */
package btools.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmPos;
import btools.util.CheapRuler;

public class RestStopPOISearcher {
  
  // Search radius constants
  public static final double WATER_SEARCH_RADIUS = 2000.0; // 2 km for water points
  public static final double CABIN_SEARCH_RADIUS = 5000.0; // 5 km for cabins/huts
  
  // Grid search step size (in internal coordinates, ~100m)
  private static final int GRID_STEP = 10000; // ~100 meters
  
  /**
   * Search for water points and cabins/huts near a rest stop location
   * 
   * @param restStopLocation Location of the rest stop
   * @param nodesCache Nodes cache for searching
   * @param routingContext Routing context for decoding node descriptions
   * @return POI information for the rest stop
   */
  public static RestStopPOIs searchPOIsNearRestStop(
      OsmPos restStopLocation,
      btools.mapaccess.NodesCache nodesCache,
      RoutingContext routingContext) {
    
    RestStopPOIs pois = new RestStopPOIs();
    pois.restStopLocation = restStopLocation;
    
    if (restStopLocation == null || nodesCache == null || routingContext == null) {
      return pois;
    }
    
    int centerLon = restStopLocation.getILon();
    int centerLat = restStopLocation.getILat();
    
    // Search for water points (2 km radius)
    searchWaterPoints(centerLon, centerLat, WATER_SEARCH_RADIUS, nodesCache, routingContext, pois);
    
    // Search for cabins/huts (5 km radius)
    searchCabins(centerLon, centerLat, CABIN_SEARCH_RADIUS, nodesCache, routingContext, pois);
    
    // Sort by distance
    pois.waterPoints.sort(Comparator.comparingDouble(w -> w.distanceFromRestStop));
    pois.cabins.sort(Comparator.comparingDouble(c -> c.distanceFromRestStop));
    
    return pois;
  }
  
  /**
   * Search for water points in segments around the center
   */
  private static void searchWaterPoints(int centerLon, int centerLat, double radius,
      btools.mapaccess.NodesCache nodesCache, RoutingContext routingContext, RestStopPOIs pois) {
    
    OsmPos centerPos = new OsmNode(centerLon, centerLat);
    
    // Convert radius to internal coordinates (approximate: 1 degree ≈ 111km)
    int radiusInternal = (int)(radius * 9.0); // ~9 internal units per meter at mid-latitudes
    int stepSize = 1000000; // 1 degree step for segment search
    
    // Search nearby segments
    for (int latOffset = -radiusInternal; latOffset <= radiusInternal; latOffset += stepSize) {
      for (int lonOffset = -radiusInternal; lonOffset <= radiusInternal; lonOffset += stepSize) {
        int searchLon = centerLon + lonOffset;
        int searchLat = centerLat + latOffset;
        
        // Get segment for this area
        btools.codec.MicroCache segment = nodesCache.getSegmentFor(searchLon, searchLat);
        if (segment == null || segment.getSize() == 0) {
          continue;
        }
        
        // Iterate through all nodes in the segment
        int segmentSize = segment.getSize();
        for (int i = 0; i < segmentSize; i++) {
          long nodeId = segment.getIdForIndex(i);
          OsmNode node = new OsmNode(nodeId);
          
          // Check distance first (before parsing node body)
          double distance = calculateDistance(centerPos, node);
          if (distance > radius) {
            continue;
          }
          
          // Get node description if available
          if (nodesCache.obtainNonHollowNode(node) && node.nodeDescription != null) {
            // Decode node description
            String nodeDesc = routingContext.expctxNode.getKeyValueDescription(false, node.nodeDescription);
            if (nodeDesc != null && !nodeDesc.isEmpty()) {
              // Check for water point tags
              WaterPointInfo waterPoint = checkWaterPointTags(node, nodeDesc, distance);
              if (waterPoint != null) {
                pois.waterPoints.add(waterPoint);
              }
            }
          }
        }
      }
    }
  }
  
  /**
   * Search for cabins/huts in segments around the center
   */
  private static void searchCabins(int centerLon, int centerLat, double radius,
      btools.mapaccess.NodesCache nodesCache, RoutingContext routingContext, RestStopPOIs pois) {
    
    OsmPos centerPos = new OsmNode(centerLon, centerLat);
    
    // Convert radius to internal coordinates
    int radiusInternal = (int)(radius * 9.0);
    int stepSize = 1000000; // 1 degree step for segment search
    
    // Search nearby segments
    for (int latOffset = -radiusInternal; latOffset <= radiusInternal; latOffset += stepSize) {
      for (int lonOffset = -radiusInternal; lonOffset <= radiusInternal; lonOffset += stepSize) {
        int searchLon = centerLon + lonOffset;
        int searchLat = centerLat + latOffset;
        
        // Get segment for this area
        btools.codec.MicroCache segment = nodesCache.getSegmentFor(searchLon, searchLat);
        if (segment == null || segment.getSize() == 0) {
          continue;
        }
        
        // Iterate through all nodes in the segment
        int segmentSize = segment.getSize();
        for (int i = 0; i < segmentSize; i++) {
          long nodeId = segment.getIdForIndex(i);
          OsmNode node = new OsmNode(nodeId);
          
          // Check distance first (before parsing node body)
          double distance = calculateDistance(centerPos, node);
          if (distance > radius) {
            continue;
          }
          
          // Get node description if available
          if (nodesCache.obtainNonHollowNode(node) && node.nodeDescription != null) {
            // Decode node description
            String nodeDesc = routingContext.expctxNode.getKeyValueDescription(false, node.nodeDescription);
            if (nodeDesc != null && !nodeDesc.isEmpty()) {
              // Check for cabin/hut tags
              CabinInfo cabin = checkCabinTags(node, nodeDesc, distance);
              if (cabin != null) {
                pois.cabins.add(cabin);
              }
            }
          }
        }
      }
    }
  }
  
  /**
   * Check if node description contains water point tags
   */
  private static WaterPointInfo checkWaterPointTags(OsmNode node, String nodeDesc, double distance) {
    String descLower = nodeDesc.toLowerCase();
    
    // Check for water point tags
    String type = null;
    String name = extractName(nodeDesc);
    
    if (descLower.contains("amenity=drinking_water") || descLower.contains("amenity drinking_water")) {
      type = "drinking_water";
    } else if (descLower.contains("amenity=fountain") || descLower.contains("amenity fountain")) {
      type = "fountain";
    } else if (descLower.contains("natural=spring") || descLower.contains("natural spring")) {
      type = "spring";
    }
    
    if (type != null) {
      return new WaterPointInfo(node, type, name, distance);
    }
    
    return null;
  }
  
  /**
   * Check if node description contains cabin/hut tags
   */
  private static CabinInfo checkCabinTags(OsmNode node, String nodeDesc, double distance) {
    String descLower = nodeDesc.toLowerCase();
    
    // Check for cabin/hut tags
    String type = null;
    String name = extractName(nodeDesc);
    boolean locked = descLower.contains("locked=yes") || descLower.contains("access=private");
    boolean hasWater = descLower.contains("drinking_water=yes") || descLower.contains("amenity=drinking_water");
    
    if (descLower.contains("tourism=alpine_hut") || descLower.contains("tourism alpine_hut")) {
      type = "alpine_hut";
    } else if (descLower.contains("tourism=wilderness_hut") || descLower.contains("tourism wilderness_hut")) {
      type = "wilderness_hut";
    } else if (descLower.contains("tourism=hut") || descLower.contains("tourism hut")) {
      type = "hut";
    } else if (descLower.contains("tourism=cabin") || descLower.contains("tourism cabin")) {
      type = "cabin";
    } else if (descLower.contains("building=cabin") || descLower.contains("building cabin")) {
      type = "cabin";
    }
    
    if (type != null) {
      CabinInfo cabin = new CabinInfo(node, type, name, distance);
      cabin.locked = locked;
      cabin.hasWater = hasWater;
      return cabin;
    }
    
    return null;
  }
  
  /**
   * Extract name from node description
   */
  private static String extractName(String nodeDesc) {
    // Try to extract name from description
    // Format is typically "key1=value1 key2=value2 ..."
    String[] parts = nodeDesc.split(" ");
    for (String part : parts) {
      if (part.startsWith("name=")) {
        return part.substring(5);
      }
    }
    return null;
  }
  
  /**
   * Calculate distance between two points in meters
   */
  public static double calculateDistance(OsmPos p1, OsmPos p2) {
    if (p1 == null || p2 == null) {
      return Double.MAX_VALUE;
    }
    
    // CheapRuler.distance takes int parameters (internal coordinates)
    int ilon1 = p1.getILon();
    int ilat1 = p1.getILat();
    int ilon2 = p2.getILon();
    int ilat2 = p2.getILat();
    
    // CheapRuler.distance returns distance in km, convert to meters
    return CheapRuler.distance(ilon1, ilat1, ilon2, ilat2) * 1000.0;
  }
  
  /**
   * Container for POI information near a rest stop
   */
  public static class RestStopPOIs {
    public OsmPos restStopLocation;
    public List<WaterPointInfo> waterPoints = new ArrayList<>();
    public List<CabinInfo> cabins = new ArrayList<>();
    
    public boolean hasWater() {
      return waterPoints != null && !waterPoints.isEmpty();
    }
    
    public boolean hasCabins() {
      return cabins != null && !cabins.isEmpty();
    }
    
    public WaterPointInfo getNearestWater() {
      if (!hasWater()) return null;
      return waterPoints.get(0); // Assuming sorted by distance
    }
    
    public CabinInfo getNearestCabin() {
      if (!hasCabins()) return null;
      return cabins.get(0); // Assuming sorted by distance
    }
  }
  
  /**
   * Information about a water point
   */
  public static class WaterPointInfo {
    public OsmPos location;
    public String type; // "drinking_water", "fountain", "spring"
    public String name;
    public double distanceFromRestStop; // meters
    public boolean isSpring;
    public String warning; // Warning for springs
    
    public WaterPointInfo(OsmPos location, String type, String name, double distance) {
      this.location = location;
      this.type = type;
      this.name = name;
      this.distanceFromRestStop = distance;
      this.isSpring = "spring".equals(type) || "natural=spring".equals(type);
      if (this.isSpring) {
        this.warning = "Drink at your own risk";
      }
    }
  }
  
  /**
   * Information about a cabin or hut
   */
  public static class CabinInfo {
    public OsmPos location;
    public String type; // "cabin", "hut", "alpine_hut", "wilderness_hut"
    public String name;
    public double distanceFromRestStop; // meters
    public boolean locked;
    public boolean hasWater;
    public boolean hasShelter;
    
    public CabinInfo(OsmPos location, String type, String name, double distance) {
      this.location = location;
      this.type = type;
      this.name = name;
      this.distanceFromRestStop = distance;
      this.locked = false; // Would be determined from OSM tags
      this.hasWater = false; // Would be determined from OSM tags
      this.hasShelter = true; // Cabins/huts typically provide shelter
    }
  }
}

