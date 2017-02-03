package btools.codec;

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
public interface WaypointMatcher
{
  void startNode( int ilon, int ilat, byte[] wayTags );
  void transferNode( int ilon, int ilat );
  void endNode( int ilon, int ilat );
}
