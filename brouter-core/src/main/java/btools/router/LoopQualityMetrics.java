package btools.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Quality metrics for evaluating round-trip/loop routes.
 * Computes road reuse percentage, distance accuracy ratio,
 * direction adherence, continuity, compactness, cost and closure
 * from an OsmTrack.
 */
public final class LoopQualityMetrics {

  private final double roadReusePercent;
  private final double distanceRatio;
  private final double directionDeltaDegrees;
  private final int actualDistanceMeters;
  private final int requestedDistanceMeters;
  private final double continuityScore;
  private final int maxGapMeters;
  private final int totalGapMeters;
  private final double compactnessScore;
  private final double averageCostPerMeter;
  private final int closureDistanceMeters;

  private LoopQualityMetrics(double roadReusePercent, double distanceRatio,
                             double directionDeltaDegrees, int actualDistanceMeters,
                             int requestedDistanceMeters, double continuityScore,
                             int maxGapMeters, int totalGapMeters,
                             double compactnessScore, double averageCostPerMeter,
                             int closureDistanceMeters) {
    this.roadReusePercent = roadReusePercent;
    this.distanceRatio = distanceRatio;
    this.directionDeltaDegrees = directionDeltaDegrees;
    this.actualDistanceMeters = actualDistanceMeters;
    this.requestedDistanceMeters = requestedDistanceMeters;
    this.continuityScore = continuityScore;
    this.maxGapMeters = maxGapMeters;
    this.totalGapMeters = totalGapMeters;
    this.compactnessScore = compactnessScore;
    this.averageCostPerMeter = averageCostPerMeter;
    this.closureDistanceMeters = closureDistanceMeters;
  }

  /**
   * Compute quality metrics for a round-trip track.
   *
   * @param track                  the routed track
   * @param requestedDistanceMeters the requested round-trip distance
   * @param requestedDirectionDeg  the requested start direction in degrees [0, 360)
   * @return computed metrics
   */
  public static LoopQualityMetrics compute(OsmTrack track, int requestedDistanceMeters,
                                           double requestedDirectionDeg) {
    List<OsmPathElement> nodes = track.nodes;

    double reusePercent = computeRoadReusePercent(nodes);
    double distRatio = (requestedDistanceMeters > 0)
      ? (double) track.distance / requestedDistanceMeters
      : 0.0;
    double dirDelta = computeDirectionDelta(nodes, requestedDirectionDeg);

    // Continuity: detect beeline gaps (consecutive points >100m apart)
    int[] gapInfo = computeGapInfo(nodes);
    int maxGap = gapInfo[0];
    int totalGap = gapInfo[1];
    double contScore = (track.distance > 0)
      ? Math.max(0.0, Math.min(1.0, 1.0 - (double) totalGap / track.distance))
      : 1.0;

    // Compactness: convex hull area vs ideal circle area
    double compact = computeCompactnessScore(nodes, track.distance);

    // Average cost per meter
    double avgCostPerMeter = (track.distance > 0)
      ? (double) track.cost / track.distance
      : 0.0;

    // Closure distance
    int closureDist = 0;
    if (nodes.size() >= 2) {
      OsmPathElement first = nodes.get(0);
      OsmPathElement last = nodes.get(nodes.size() - 1);
      closureDist = first.calcDistance(last);
    }

    return new LoopQualityMetrics(reusePercent, distRatio, dirDelta,
      track.distance, requestedDistanceMeters, contScore, maxGap, totalGap,
      compact, avgCostPerMeter, closureDist);
  }

  /**
   * Compute the percentage of the track that reuses the same road edges.
   * An edge is identified by the unordered pair of consecutive node positions.
   * If an edge appears N times, (N-1) traversals count as reuse.
   */
  static double computeRoadReusePercent(List<OsmPathElement> nodes) {
    if (nodes.size() < 2) return 0.0;

    Map<Long, int[]> edgeCounts = new HashMap<>();
    double totalDistance = 0;
    double reusedDistance = 0;

    for (int i = 1; i < nodes.size(); i++) {
      OsmPathElement a = nodes.get(i - 1);
      OsmPathElement b = nodes.get(i);
      int segDist = a.calcDistance(b);
      totalDistance += segDist;

      // Create undirected edge key: sort the two position IDs
      long idA = a.getIdFromPos();
      long idB = b.getIdFromPos();
      long edgeKey = (idA <= idB) ? (idA * 31 + idB) : (idB * 31 + idA);

      int[] entry = edgeCounts.get(edgeKey);
      if (entry == null) {
        edgeCounts.put(edgeKey, new int[]{1, segDist});
      } else {
        if (entry[0] == 1) {
          // Second traversal — this and all subsequent are reuse
          reusedDistance += segDist;
        } else {
          reusedDistance += segDist;
        }
        entry[0]++;
      }
    }

    return (totalDistance > 0) ? (reusedDistance / totalDistance) * 100.0 : 0.0;
  }

  /**
   * Compute the angular delta between the requested direction and the
   * actual initial heading of the track. Uses the first few nodes to
   * average out GPS jitter.
   */
  static double computeDirectionDelta(List<OsmPathElement> nodes,
                                      double requestedDirectionDeg) {
    if (nodes.size() < 2) return 0.0;

    // Use up to the 5th node for a more stable heading
    OsmPathElement first = nodes.get(0);
    int headingIdx = Math.min(5, nodes.size() - 1);
    OsmPathElement headingNode = nodes.get(headingIdx);

    double actualDirection = CheapAngleMeter.getDirection(
      first.getILon(), first.getILat(),
      headingNode.getILon(), headingNode.getILat());

    return CheapAngleMeter.getDifferenceFromDirection(requestedDirectionDeg, actualDirection);
  }

  /**
   * Compute gap information for consecutive track points.
   * A "gap" is any segment where consecutive points are more than 100m apart,
   * which may indicate a beeline/ferry shortcut.
   *
   * @return int[]{maxGapMeters, totalGapMeters}
   */
  static int[] computeGapInfo(List<OsmPathElement> nodes) {
    int maxGap = 0;
    int totalGap = 0;
    int gapThreshold = 100; // meters

    for (int i = 1; i < nodes.size(); i++) {
      int dist = nodes.get(i - 1).calcDistance(nodes.get(i));
      if (dist > gapThreshold) {
        totalGap += dist;
        if (dist > maxGap) {
          maxGap = dist;
        }
      }
    }
    return new int[]{maxGap, totalGap};
  }

  /**
   * Compute the compactness score of the track shape.
   * Uses the ratio of the convex hull area of track points to the area
   * of a circle with the same circumference as the track distance.
   * A perfect circle scores 1.0, an out-and-back line scores ~0.
   *
   * @param nodes    track points
   * @param distance total track distance in meters
   * @return compactness score clamped to [0, 1]
   */
  static double computeCompactnessScore(List<OsmPathElement> nodes, int distance) {
    if (nodes.size() < 3 || distance <= 0) return 0.0;

    // Subsample for performance (at most ~1000 points, minimum step of 1)
    List<double[]> points = new ArrayList<>();
    long centerILatSum = 0;
    int step = Math.max(1, nodes.size() / 1000);
    for (int i = 0; i < nodes.size(); i += step) {
      OsmPathElement n = nodes.get(i);
      centerILatSum += n.getILat();
      points.add(new double[]{n.getILon(), n.getILat()});
    }
    // Always include the last point
    OsmPathElement last = nodes.get(nodes.size() - 1);
    if (points.isEmpty() || points.get(points.size() - 1)[0] != last.getILon()
      || points.get(points.size() - 1)[1] != last.getILat()) {
      points.add(new double[]{last.getILon(), last.getILat()});
    }

    if (points.size() < 3) return 0.0;

    // Get meter scales at the center latitude
    int centerILat = (int) (centerILatSum / points.size());
    double[] kxky = CheapRuler.getLonLatToMeterScales(centerILat);
    double kx = kxky[0]; // ilon units to meters
    double ky = kxky[1]; // ilat units to meters

    // Convert to meter coordinates
    double[] xs = new double[points.size()];
    double[] ys = new double[points.size()];
    for (int i = 0; i < points.size(); i++) {
      xs[i] = points.get(i)[0] * kx;
      ys[i] = points.get(i)[1] * ky;
    }

    double hullArea = convexHullArea(xs, ys);

    // Area of a circle with circumference = distance
    // circumference = 2*PI*r => r = distance / (2*PI)
    // area = PI * r^2 = PI * (distance / (2*PI))^2 = distance^2 / (4*PI)
    double circleArea = (double) distance * distance / (4.0 * Math.PI);

    if (circleArea <= 0) return 0.0;
    return Math.max(0.0, Math.min(1.0, hullArea / circleArea));
  }

  /**
   * Compute the area of the convex hull of a set of 2D points using
   * Andrew's monotone chain algorithm. O(n log n).
   *
   * @param xs x coordinates
   * @param ys y coordinates
   * @return area of the convex hull
   */
  static double convexHullArea(double[] xs, double[] ys) {
    int n = xs.length;
    if (n < 3) return 0.0;

    // Create index array and sort by x, then y
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) idx[i] = i;
    Arrays.sort(idx, new Comparator<Integer>() {
      @Override
      public int compare(Integer a, Integer b) {
        int cx = Double.compare(xs[a], xs[b]);
        return cx != 0 ? cx : Double.compare(ys[a], ys[b]);
      }
    });

    // Build lower hull
    int[] hull = new int[2 * n];
    int k = 0;
    for (int i = 0; i < n; i++) {
      while (k >= 2 && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) {
        k--;
      }
      hull[k++] = idx[i];
    }
    // Build upper hull
    int lower = k + 1;
    for (int i = n - 2; i >= 0; i--) {
      while (k >= lower && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) {
        k--;
      }
      hull[k++] = idx[i];
    }
    // k-1 is the hull size (last point == first point)

    // Shoelace formula for area
    double area = 0;
    for (int i = 0; i < k - 1; i++) {
      int j = (i + 1) % (k - 1);
      area += xs[hull[i]] * ys[hull[j]];
      area -= xs[hull[j]] * ys[hull[i]];
    }
    return Math.abs(area) / 2.0;
  }

  /** Cross product of vectors OA and OB where O=hull[o], A=hull[a], B=idx[b] */
  private static double cross(double[] xs, double[] ys, int o, int a, int b) {
    return (xs[a] - xs[o]) * (ys[b] - ys[o])
      - (ys[a] - ys[o]) * (xs[b] - xs[o]);
  }

  /**
   * Compute a weighted composite quality score in [0, 1].
   * Higher is better. Combines all metric dimensions.
   */
  public double compositeScore() {
    double distScore = 1.0 - Math.min(1.0, Math.abs(distanceRatio - 1.0) / 0.5);
    double reuseScore = 1.0 - Math.min(1.0, roadReusePercent / 50.0);
    double dirScore = 1.0 - Math.min(1.0, directionDeltaDegrees / 180.0);

    return 0.20 * distScore
      + 0.20 * reuseScore
      + 0.15 * dirScore
      + 0.20 * continuityScore
      + 0.15 * compactnessScore
      + 0.10 * (1.0 - Math.min(1.0, closureDistanceMeters / 1000.0));
  }

  public double getRoadReusePercent() {
    return roadReusePercent;
  }

  public double getDistanceRatio() {
    return distanceRatio;
  }

  public double getDirectionDeltaDegrees() {
    return directionDeltaDegrees;
  }

  public int getActualDistanceMeters() {
    return actualDistanceMeters;
  }

  public int getRequestedDistanceMeters() {
    return requestedDistanceMeters;
  }

  public double getContinuityScore() {
    return continuityScore;
  }

  public int getMaxGapMeters() {
    return maxGapMeters;
  }

  public int getTotalGapMeters() {
    return totalGapMeters;
  }

  public double getCompactnessScore() {
    return compactnessScore;
  }

  public double getAverageCostPerMeter() {
    return averageCostPerMeter;
  }

  public int getClosureDistanceMeters() {
    return closureDistanceMeters;
  }

  @Override
  public String toString() {
    return String.format(
      "LoopQualityMetrics[reuse=%.1f%%, distRatio=%.2f (%dm/%dm), dirDelta=%.1f°, " +
        "continuity=%.2f (maxGap=%dm, totalGap=%dm), compactness=%.2f, " +
        "cost/m=%.1f, closure=%dm, composite=%.2f]",
      roadReusePercent, distanceRatio, actualDistanceMeters, requestedDistanceMeters,
      directionDeltaDegrees, continuityScore, maxGapMeters, totalGapMeters,
      compactnessScore, averageCostPerMeter, closureDistanceMeters, compositeScore());
  }
}
