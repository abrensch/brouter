package btools.router;

/**
 * Test regions for loop quality verification.
 * Each region defines a start point, the segment tile(s) needed,
 * and terrain-dependent quality thresholds.
 */
public enum LoopTestRegion {
  // Existing test area — suburban road network
  DREIEICH(8.720, 50.000, "E5_N50.rd5", 25.0, 0.4, 2.5, 170),
  // Dense city grid with many routing options
  URBAN_BERLIN(13.400, 52.520, "E10_N50.rd5", 25.0, 0.4, 2.5, 170),
  // Mountain roads, limited connectivity, dead-end valleys force extreme detours
  ALPINE_INNSBRUCK(11.400, 47.260, "E10_N45.rd5", 45.0, 0.3, 5.5, 180),
  // Constrained by water on one side
  COASTAL_NICE(7.270, 43.700, "E5_N40.rd5", 40.0, 0.4, 3.0, 170),
  // Low road density countryside with limited alternatives
  RURAL_LOZERE(3.500, 44.500, "E0_N40.rd5", 35.0, 0.4, 3.0, 170);

  /** Longitude in decimal degrees */
  public final double lon;
  /** Latitude in decimal degrees */
  public final double lat;
  /** BRouter internal longitude: (lon + 180) * 1e6 */
  public final int ilon;
  /** BRouter internal latitude: (lat + 90) * 1e6 */
  public final int ilat;
  /** Segment tile filename (e.g., E5_N50.rd5) */
  public final String segmentFile;
  /** Maximum acceptable road reuse percentage for this terrain */
  public final double maxReusePercent;
  /** Minimum acceptable distance ratio (actual/requested) */
  public final double minDistanceRatio;
  /** Maximum acceptable distance ratio (actual/requested) */
  public final double maxDistanceRatio;
  /** Maximum acceptable direction delta in degrees */
  public final double maxDirectionDelta;

  LoopTestRegion(double lon, double lat, String segmentFile,
                 double maxReusePercent, double minDistanceRatio,
                 double maxDistanceRatio, double maxDirectionDelta) {
    this.lon = lon;
    this.lat = lat;
    this.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    this.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    this.segmentFile = segmentFile;
    this.maxReusePercent = maxReusePercent;
    this.minDistanceRatio = minDistanceRatio;
    this.maxDistanceRatio = maxDistanceRatio;
    this.maxDirectionDelta = maxDirectionDelta;
  }
}
