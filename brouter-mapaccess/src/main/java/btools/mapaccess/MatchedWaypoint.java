/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public final class MatchedWaypoint {
  public OsmNode node1;
  public OsmNode node2;
  public OsmNode crossPoint;
  public OsmNode waypoint;
  public String name;  // waypoint name used in error messages
  public double radius;  // distance in meter between waypoint and crosspoint
  public boolean direct;  // from this point go direct to next = beeline routing
  public int indexInTrack = 0;
  public double directionToNext = -1;
  public double directionDiff = 361;

  public List<MatchedWaypoint> wayNearest = new ArrayList<>();
  public boolean hasUpdate;

  public void writeToStream(DataOutput dos) throws IOException {
    dos.writeInt(node1.iLat);
    dos.writeInt(node1.iLon);
    dos.writeInt(node2.iLat);
    dos.writeInt(node2.iLon);
    dos.writeInt(crossPoint.iLat);
    dos.writeInt(crossPoint.iLon);
    dos.writeInt(waypoint.iLat);
    dos.writeInt(waypoint.iLon);
    dos.writeDouble(radius);
  }

  public static MatchedWaypoint readFromStream(DataInput dis) throws IOException {
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.node1 = new OsmNode();
    mwp.node2 = new OsmNode();
    mwp.crossPoint = new OsmNode();
    mwp.waypoint = new OsmNode();

    mwp.node1.iLat = dis.readInt();
    mwp.node1.iLon = dis.readInt();
    mwp.node2.iLat = dis.readInt();
    mwp.node2.iLon = dis.readInt();
    mwp.crossPoint.iLat = dis.readInt();
    mwp.crossPoint.iLon = dis.readInt();
    mwp.waypoint.iLat = dis.readInt();
    mwp.waypoint.iLon = dis.readInt();
    mwp.radius = dis.readDouble();
    return mwp;
  }

}
