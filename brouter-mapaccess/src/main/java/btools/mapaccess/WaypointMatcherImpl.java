package btools.mapaccess;

import java.util.List;
import java.util.Collections;
import java.util.Comparator;

import btools.util.CheapRuler;
import btools.util.CheapAngleMeter;

/**
 * the WaypointMatcher is feeded by the decoder with geoemtries of ways that are
 * already check for allowed access according to the current routing profile
 * <p>
 * It matches these geometries against the list of waypoints to find the best
 * match for each waypoint
 */
public final class WaypointMatcherImpl implements WaypointMatcher {
  private static final int MAX_POINTS = 5;

  private List<MatchedWaypoint> waypoints;
  private OsmNodePairSet islandPairs;

  private OsmNode start;
  private OsmNode target;
  private boolean anyUpdate;

  private Comparator<MatchedWaypoint> comparator;

  public WaypointMatcherImpl(List<MatchedWaypoint> waypoints, double maxDistance, OsmNodePairSet islandPairs) {
    this.waypoints = waypoints;
    this.islandPairs = islandPairs;
    MatchedWaypoint last = null;
    for (MatchedWaypoint mwp : waypoints) {
      mwp.radius = maxDistance;
      if (last != null && mwp.directionToNext == -1) {
        last.directionToNext = CheapAngleMeter.getDirection(last.waypoint.iLon, last.waypoint.iLat, mwp.waypoint.iLon, mwp.waypoint.iLat);
      }
      last = mwp;
    }
    // last point has no angle so we are looking back
    int lastidx = waypoints.size() - 2;
    if (lastidx < 0) {
      last.directionToNext = -1;
    } else {
      last.directionToNext = CheapAngleMeter.getDirection(last.waypoint.iLon, last.waypoint.iLat, waypoints.get(lastidx).waypoint.iLon, waypoints.get(lastidx).waypoint.iLat);
    }

    // sort result list
    comparator = new Comparator<>() {
      @Override
      public int compare(MatchedWaypoint mw1, MatchedWaypoint mw2) {
        int cmpDist = Double.compare(mw1.radius, mw2.radius);
        if (cmpDist != 0) return cmpDist;
        return Double.compare(mw1.directionDiff, mw2.directionDiff);
      }
    };

  }

  private void checkSegment(int lon1, int lat1, int lon2, int lat2) {
    // todo: bounding-box pre-filter

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

      if (mwp.direct &&
        (i == 0 ||
          waypoints.get(i - 1).direct)
      ) {
        if (mwp.crossPoint == null) {
          mwp.crossPoint = new OsmNode();
          mwp.crossPoint.iLon = mwp.waypoint.iLon;
          mwp.crossPoint.iLat = mwp.waypoint.iLat;
          mwp.hasUpdate = true;
          anyUpdate = true;
        }
        continue;
      }

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
        mwp.hasUpdate = true;
        anyUpdate = true;
        // calculate crosspoint
        if (mwp.crossPoint == null)
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

  @Override
  public boolean match(OsmNode start, OsmNode target) {
    if (islandPairs.size() > 0) {
      if (islandPairs.hasPair(start.getILat(), target.getIdFromPos())) {
        return false;
      }
    }
    this.start = start;
    this.target = target;
    anyUpdate = false;
    end();
    return true;
  }

  private void end() {
    checkSegment(start.iLon, start.iLat, target.iLon, target.iLat);
    if (anyUpdate) {
      for (MatchedWaypoint mwp : waypoints) {
        if (mwp.hasUpdate) {
          double angle = CheapAngleMeter.getDirection(start.iLon, start.iLat, target.iLon, target.iLat);
          double diff = CheapAngleMeter.getDifferenceFromDirection(mwp.directionToNext, angle);

          mwp.hasUpdate = false;

          MatchedWaypoint mw = new MatchedWaypoint();
          mw.waypoint = new OsmNode();
          mw.waypoint.iLon = mwp.waypoint.iLon;
          mw.waypoint.iLat = mwp.waypoint.iLat;
          mw.crossPoint = new OsmNode();
          mw.crossPoint.iLon = mwp.crossPoint.iLon;
          mw.crossPoint.iLat = mwp.crossPoint.iLat;
          mw.node1 = new OsmNode(start.iLon, start.iLat);
          mw.node2 = new OsmNode(target.iLon, target.iLat);
          mw.name = mwp.name + "_w_" + mwp.crossPoint.hashCode();
          mw.radius = mwp.radius;
          mw.directionDiff = diff;
          mw.directionToNext = mwp.directionToNext;

          updateWayList(mwp.wayNearest, mw);

          // revers
          angle = CheapAngleMeter.getDirection(target.iLon, target.iLat, start.iLon, start.iLat);
          diff = CheapAngleMeter.getDifferenceFromDirection(mwp.directionToNext, angle);
          mw = new MatchedWaypoint();
          mw.waypoint = new OsmNode();
          mw.waypoint.iLon = mwp.waypoint.iLon;
          mw.waypoint.iLat = mwp.waypoint.iLat;
          mw.crossPoint = new OsmNode();
          mw.crossPoint.iLon = mwp.crossPoint.iLon;
          mw.crossPoint.iLat = mwp.crossPoint.iLat;
          mw.node1 = new OsmNode(target.iLon, target.iLat);
          mw.node2 = new OsmNode(start.iLon, start.iLat);
          mw.name = mwp.name + "_w2_" + mwp.crossPoint.hashCode();
          mw.radius = mwp.radius;
          mw.directionDiff = diff;
          mw.directionToNext = mwp.directionToNext;

          updateWayList(mwp.wayNearest, mw);

          MatchedWaypoint way = mwp.wayNearest.get(0);
          mwp.crossPoint.iLon = way.crossPoint.iLon;
          mwp.crossPoint.iLat = way.crossPoint.iLat;
          mwp.node1 = new OsmNode(way.node1.iLon, way.node1.iLat);
          mwp.node2 = new OsmNode(way.node2.iLon, way.node2.iLat);
          mwp.directionDiff = way.directionDiff;
          mwp.radius = way.radius;

        }
      }
    }
  }

  // check limit of list size (avoid long runs)
  void updateWayList(List<MatchedWaypoint> ways, MatchedWaypoint mw) {
    ways.add(mw);
    // use only shortest distances by smallest direction difference
    Collections.sort(ways, comparator);
    if (ways.size() > MAX_POINTS) ways.remove(MAX_POINTS);

  }


}
