package btools.router;

/**
 * Holds the result of a single loop quality test case for report generation.
 *
 * <p>Top-level (was nested in {@code LoopQualityTest}) so it can be shared by
 * the producer side — the parameterized test forks that route loops and persist
 * results — and the consumer side — {@link LoopQualityReport}, run as a separate
 * single-JVM Gradle task that reads the persisted results back and renders the
 * HTML/GeoJSON report. Decoupling the two is what lets the test forks run with a
 * small per-fork heap (routing only) while report generation gets its own heap.
 */
class LoopQualityResult {
  final String label;
  final LoopTestRegion region;
  final int distanceMeters;
  final String profileName;
  final double direction;
  final LoopQualityMetrics metrics; // null if routing failed
  final String error; // null if routing succeeded
  final double[][] coordinates; // [lon, lat] pairs; null if routing failed
  final String variant; // "probe", "isochrone", "greedy", "iso_greedy"

  LoopQualityResult(String label, LoopTestRegion region, int distanceMeters,
                    String profileName, double direction,
                    LoopQualityMetrics metrics, String error, double[][] coordinates,
                    String variant) {
    this.label = label;
    this.region = region;
    this.distanceMeters = distanceMeters;
    this.profileName = profileName;
    this.direction = direction;
    this.metrics = metrics;
    this.error = error;
    this.coordinates = coordinates;
    this.variant = variant != null ? variant : "probe";
  }

  boolean passed() {
    if (metrics == null) return false;
    return metrics.getRoadReusePercent() <= region.maxReusePercent
      && metrics.getDistanceRatio() >= region.minDistanceRatio
      && metrics.getDistanceRatio() <= region.maxDistanceRatio
      && metrics.getDirectionDeltaDegrees() <= region.maxDirectionDelta;
  }
}
