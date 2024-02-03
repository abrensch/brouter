/**
 * Filter to eliminate some transfer nodes (Douglas-Peucker algorithm)
 *
 * @author ab
 */
package btools.mapcreator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.util.CheapRuler;

public class DPFilter {
  private static double dp_sql_threshold = 0.4 * 0.4;

  /**
   * A Transfer-Node has 2 links only (one forwrad/one reverse)
   * with the same way-tags, no node tags, no turn-restrictions,
   * and is not part of the global border
   *
   * @return true if this is a transfer node
   */
  private static boolean isTransferNode(OsmNode n) {
    if (n.nodeDescription != null || n.firstRestriction != null
        || n.hasBits(OsmNode.BORDER_BIT) || n.linkCount() != 2) {
      return false;
    }
    OsmLink l1 = n.getFirstLink();
    OsmLink l2 = l1.getNext( n );
    if ( l1.isReverse(n) == l2.isReverse(n) ) {
      return false;
    }
    return Arrays.equals( l1.wayDescription, l2.wayDescription );
  }

  private static void dropTransferNode(OsmNode n) {
    if ( n.linkCount() == 0 ) {
      return; // self-dropped (last member of a tranfernode-ring)
    }
    if ( !isTransferNode(n) ) {
      throw new RuntimeException( "not a transfer node!" );
    }
    OsmLink l1 = n.getFirstLink();
    OsmLink l2 = l1.getNext( n );
    OsmNode n1 = l1.getTarget( n );
    OsmNode n2 = l2.getTarget( n );
    byte[] wayDescription = l1.wayDescription;
    boolean reverse = l2.isReverse(n);
    n.vanish();
    if ( n1 != n2 ) { // prevent 2-node-loops from dropping transfernode-rings
      if (reverse) {
        n2.createLink(wayDescription, n1);
      } else {
        n1.createLink(wayDescription, n2);
      }
    }
  }


  /*
   * for each node (except first+last), eventually set the DP_SURVIVOR_BIT
   */
  public static void doDPFilter(List<OsmNode> nodes) {

    // find the transfer-nodes
    for( OsmNode n : nodes ) {
      if ( isTransferNode(n) ) {
        n.setBits( OsmNode.TRANSFERNODE_BIT );
      }
    }

    // loop over network nodes and follow forward links
    for( OsmNode n : nodes ) {
      if ( n.hasBits( OsmNode.TRANSFERNODE_BIT ) ) {
        continue;
      }
      OsmLink l = n.firstLink;
      while (l != null) {
        if ( !l.isReverse(n)) {
          if ( l.getTarget(n).hasBits(OsmNode.TRANSFERNODE_BIT) ) {
            doDPFilter( n, l );
          }
        }
        l = l.getNext(n);
      }
    }
    // and finally drop all non-survivors
    for( OsmNode n : nodes ) {
      if (n.hasBits(OsmNode.TRANSFERNODE_BIT) && !n.hasBits(OsmNode.DP_SURVIVOR_BIT)) {
        dropTransferNode(n);
      }
    }
  }

  private static void doDPFilter(OsmNode node, OsmLink link) {

    List<OsmNode> nodeList = new ArrayList<>(8);
    nodeList.add(node);
    OsmNode n = node;
    OsmLink l = link;
    while( n != null ) {
      OsmNode target = l.getTarget(n);
      nodeList.add(target);
      n = null;
      if (target.hasBits(OsmNode.TRANSFERNODE_BIT)) {
        l = target.firstLink;
        while (l != null) {
          if (!l.isReverse(target)) {
            n = target;
            break;
          }
          l = l.getNext(target);
        }
      }
    }
    if ( nodeList.size() > 2 ) {
      doDPFilter( nodeList, 0, nodeList.size()-1 );
    }
  }

  private static void doDPFilter(List<OsmNode> nodes, int first, int last) {
    double maxSqDist = -1.;
    int index = -1;
    OsmNode p1 = nodes.get(first);
    OsmNode p2 = nodes.get(last);

    double[] lonlat2m = CheapRuler.getLonLatToMeterScales((p1.iLat + p2.iLat) >> 1);
    double dlon2m = lonlat2m[0];
    double dlat2m = lonlat2m[1];
    double dx = (p2.iLon - p1.iLon) * dlon2m;
    double dy = (p2.iLat - p1.iLat) * dlat2m;
    double d2 = dx * dx + dy * dy;
    for (int i = first + 1; i < last; i++) {
      OsmNode p = nodes.get(i);
      double t = 0.;
      if (d2 != 0f) {
        t = ((p.iLon - p1.iLon) * dlon2m * dx + (p.iLat - p1.iLat) * dlat2m * dy) / d2;
        t = t > 1. ? 1. : (t < 0. ? 0. : t);
      }
      double dx2 = (p.iLon - (p1.iLon + t * (p2.iLon - p1.iLon))) * dlon2m;
      double dy2 = (p.iLat - (p1.iLat + t * (p2.iLat - p1.iLat))) * dlat2m;
      double sqDist = dx2 * dx2 + dy2 * dy2;
      if (sqDist > maxSqDist) {
        index = i;
        maxSqDist = sqDist;
      }
    }
    if (index >= 0) {
      if (index - first > 1) {
        doDPFilter(nodes, first, index);
      }
      if (maxSqDist >= dp_sql_threshold) {
        nodes.get(index).setBits(OsmNode.DP_SURVIVOR_BIT);
      }
      if (last - index > 1) {
        doDPFilter(nodes, index, last);
      }
    }
  }
}
