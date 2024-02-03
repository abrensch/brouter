package btools.mapaccess;

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
public interface WaypointMatcher {
  boolean match(OsmNode start, OsmNode target);
}
