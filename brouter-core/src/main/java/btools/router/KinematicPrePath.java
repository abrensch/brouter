/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmNode;

final class KinematicPrePath extends OsmPrePath {
  public double angle;
  public int priorityclassifier;
  public int classifiermask;

  protected void initPrePath(OsmPath origin, RoutingContext rc) {
    byte[] description = link.wayDescription;
    if (description == null) throw new IllegalArgumentException("null description for: " + link);

    // extract the 3 positions of the first section
    int lon0 = origin.originLon;
    int lat0 = origin.originLat;

    OsmNode p1 = sourceNode;
    int lon1 = p1.getILon();
    int lat1 = p1.getILat();

    boolean isReverse = link.isReverse(sourceNode);

    // evaluate the way tags
    rc.expCtxWay.evaluate(rc.inverseDirection ^ isReverse, description);

    int lon2 = targetNode.iLon;
    int lat2 = targetNode.iLat;

    int dist = rc.calcDistance(lon1, lat1, lon2, lat2);

    angle = rc.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2);
    priorityclassifier = (int) rc.expCtxWay.getPriorityClassifier();
    classifiermask = (int) rc.expCtxWay.getClassifierMask();
  }
}
