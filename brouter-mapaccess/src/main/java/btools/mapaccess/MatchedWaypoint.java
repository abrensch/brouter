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
    writeNode(node1,dos);
    writeNode(node2,dos);
    writeNode(crossPoint,dos);
    writeNode(waypoint,dos);
  }

  public static MatchedWaypoint readFromStream(DataInput dis) throws IOException {
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.node1 = readNode(dis);
    mwp.node2 = readNode(dis);
    mwp.crossPoint = readNode(dis);
    mwp.waypoint = readNode(dis);
    mwp.radius = dis.readDouble();
    return mwp;
  }

  private static void writeNode(OsmNode node, DataOutput dos) throws IOException {
    dos.writeInt(node.iLat);
    dos.writeInt(node.iLon);
  }
  private static OsmNode readNode(DataInput dis) throws IOException {
    int iLat = dis.readInt();
    int iLon = dis.readInt();
    return new OsmNode( iLon, iLat );
  }

}
