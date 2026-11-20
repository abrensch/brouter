package btools.mapaccess;

import java.util.List;

import btools.util.CheapRuler;

/**
 * the WaypointMatcher is feeded by the decoder with geoemtries of ways that are
 * already checked for allowed access according to the current routing profile
 * <p>
 * It matches these geometries against the list of waypoints to find the best
 * match for each waypoint
 */
public final class WaypointMatcher {

  private final List<MatchedWaypoint> waypoints;
  private final OsmNodePairSet islandPairs;

  public WaypointMatcher(List<MatchedWaypoint> waypoints, double maxDistance, OsmNodePairSet islandPairs) {
    this.waypoints = waypoints;
    this.islandPairs = islandPairs;
    for (MatchedWaypoint mwp : waypoints) {
      mwp.radius = maxDistance;
    }
  }

  public void match(OsmNode start, OsmNode target) {
    if (islandPairs.size() > 0) {
      if (islandPairs.hasPair(start.getIdFromPos(), target.getIdFromPos())) {
        return;
      }
    }
    int lon1 = start.iLon;
    int lat1 = start.iLat;
    int lon2 = target.iLon;
    int lat2 = target.iLat;

    double[] lonlat2m = CheapRuler.getLonLatToMeterScales((lat1 + lat2) >> 1);
    double dlon2m = lonlat2m[0];
    double dlat2m = lonlat2m[1];

    double dx = (lon2 - lon1) * dlon2m;
    double dy = (lat2 - lat1) * dlat2m;
    double d = Math.sqrt(dy * dy + dx * dx);

    if (d == 0.)
      return;

    for (int i = 0; i < waypoints.size(); i++) {
      MatchedWaypoint mwp = waypoints.get(i);
      OsmNode wp = mwp.waypoint;
      double x1 = (lon1 - wp.iLon) * dlon2m;
      double y1 = (lat1 - wp.iLat) * dlat2m;
      double x2 = (lon2 - wp.iLon) * dlon2m;
      double y2 = (lat2 - wp.iLat) * dlat2m;
      double r12 = x1 * x1 + y1 * y1;
      double r22 = x2 * x2 + y2 * y2;
      double radius = Math.abs(r12 < r22 ? y1 * dx - x1 * dy : y2 * dx - x2 * dy) / d;

      if (radius <= mwp.radius) {
        double s1 = x1 * dx + y1 * dy;
        double s2 = x2 * dx + y2 * dy;

        if (s1 < 0.) {
          s1 = -s1;
          s2 = -s2;
        }
        if (s2 > 0.) {
          radius = Math.sqrt(s1 < s2 ? r12 : r22);
          if (radius > mwp.radius)
            continue;
        }
        // new match for that waypoint
        mwp.radius = radius; // shortest distance to way
        mwp.node1 = start;
        mwp.node2 = target;

        // calculate crosspoint
        mwp.crossPoint = new OsmNode();
        if (s2 < 0.) {
          double wayfraction = -s2 / (d * d);
          double xm = x2 - wayfraction * dx;
          double ym = y2 - wayfraction * dy;
          mwp.crossPoint.iLon = (int) (xm / dlon2m + wp.iLon);
          mwp.crossPoint.iLat = (int) (ym / dlat2m + wp.iLat);
        } else if (s1 > s2) {
          mwp.crossPoint.iLon = lon2;
          mwp.crossPoint.iLat = lat2;
        } else {
          mwp.crossPoint.iLon = lon1;
          mwp.crossPoint.iLat = lat1;
        }
      }
    }
  }


}
