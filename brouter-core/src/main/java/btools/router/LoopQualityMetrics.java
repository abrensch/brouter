package btools.router;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Quality metrics for evaluating round-trip/loop routes.
 * Computes road reuse percentage, distance accuracy ratio,
 * and direction adherence from an OsmTrack.
 */
public final class LoopQualityMetrics {

  private final double roadReusePercent;
  private final double distanceRatio;
  private final double directionDeltaDegrees;
  private final int actualDistanceMeters;
  private final int requestedDistanceMeters;

  private LoopQualityMetrics(double roadReusePercent, double distanceRatio,
                             double directionDeltaDegrees, int actualDistanceMeters,
                             int requestedDistanceMeters) {
    this.roadReusePercent = roadReusePercent;
    this.distanceRatio = distanceRatio;
    this.directionDeltaDegrees = directionDeltaDegrees;
    this.actualDistanceMeters = actualDistanceMeters;
    this.requestedDistanceMeters = requestedDistanceMeters;
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

    return new LoopQualityMetrics(reusePercent, distRatio, dirDelta,
      track.distance, requestedDistanceMeters);
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

  @Override
  public String toString() {
    return String.format(
      "LoopQualityMetrics[reuse=%.1f%%, distRatio=%.2f (%dm/%dm), dirDelta=%.1f°]",
      roadReusePercent, distanceRatio, actualDistanceMeters, requestedDistanceMeters,
      directionDeltaDegrees);
  }
}
