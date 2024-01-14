package btools.codec;

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
public interface WaypointMatcher {
  boolean match(int ilonStart, int ilatStart, int ilonTarget, int ilatTarget);
}
