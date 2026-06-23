package btools.codec;

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
public interface WaypointMatcher {
  /**
   * Begin feeding one way's geometry. Returns false to tell the decoder to stop
   * feeding this way (matcher exhausted for it).
   *
   * @param wayDescription the way's tag-value description bitmap (may be null).
   *                       The implementation stashes it on each match so round-trip
   *                       via-snapping can score the matched way's profile cost
   *                       factor. Normal routing ignores it (the field stays unread),
   *                       so a null is harmless on that path.
   */
  boolean start(int ilonStart, int ilatStart, int ilonTarget, int ilatTarget, boolean useAsStartWay, byte[] wayDescription);

  void transferNode(int ilon, int ilat);

  void end();

  boolean hasMatch(int lon, int lat);
}
