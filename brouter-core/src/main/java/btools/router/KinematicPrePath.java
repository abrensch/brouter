/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.GraphNode;

final class KinematicPrePath extends OsmPrePath {
  public double angle;
  public int priorityclassifier;
  public int classifiermask;

  protected void initPrePath(OsmPath origin, RoutingContext rc) {
    byte[] description = link.wayDescription;
    if (description == null) throw new IllegalArgumentException("null description for: " + link);

    // extract the 3 positions of the first section
    GraphNode previousNode = origin.sourceNode == null ? null : origin.sourceNode;
    boolean isStartpoint = previousNode == null;
    int lon0 = isStartpoint ? -1 : previousNode.iLon;
    int lat0 = isStartpoint ? -1 : previousNode.iLat;

    int lon1 = sourceNode.iLon;
    int lat1 = sourceNode.iLat;

    int lon2 = targetNode.iLon;
    int lat2 = targetNode.iLat;

    boolean isReverse = link.isReverse(sourceNode);

    // evaluate the way tags
    rc.expCtxWay.evaluate(rc.inverseDirection ^ isReverse, description);

    angle = isStartpoint ? 0. : rc.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2);
    priorityclassifier = (int) rc.expCtxWay.getPriorityClassifier();
    classifiermask = (int) rc.expCtxWay.getClassifierMask();
  }
}
