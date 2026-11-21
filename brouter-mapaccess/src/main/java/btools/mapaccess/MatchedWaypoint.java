/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.mapaccess;

public final class MatchedWaypoint {
  public GraphNode node1;
  public GraphNode node2;
  public GraphNode crossPoint;
  public GraphNode waypoint;
  public String name;  // waypoint name used in error messages
  public double radius;  // distance in meter between waypoint and crosspoint
  public boolean direct;  // from this point go direct to next = beeline routing
  public int indexInTrack = 0;
}
