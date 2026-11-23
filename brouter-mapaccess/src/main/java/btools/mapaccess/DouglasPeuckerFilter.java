/**
 * Filter to eliminate some transfer nodes (Douglas-Peucker algorithm)
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import btools.util.CheapRuler;

public class DouglasPeuckerFilter {
  private static double dp_sql_threshold = 0.4 * 0.4;

  /**
   * A Transfer-Node has 2 links only (one forward/one reverse)
   * with the same way-tags, no node tags, no turn-restrictions,
   * is not a TR target and is not part of the global border
   *
   * @param n the node to check
   * @return true if this is a transfer node
   */
  private static boolean isTransferNode(GraphNode n) {
    if (n.nodeDescription != null || n.firstRestriction != null
        || n.hasBits(GraphNode.BORDER_BIT) || n.hasBits(GraphNode.TR_TARGET_BIT) || n.linkCount() != 2) {
      return false;
    }
    GraphLink l1 = n.firstLink;
    GraphLink l2 = l1.getNext( n );
    if ( l1.isReverse(n) == l2.isReverse(n) ) {
      return false;
    }
    return Arrays.equals( l1.wayDescription, l2.wayDescription );
  }

  private static void dropTransferNode(GraphNode n) {
    if ( n.linkCount() == 0 ) {
      return; // self-dropped (last member of a tranfernode-ring)
    }
    if ( !isTransferNode(n) ) {
      throw new RuntimeException( "not a transfer node!" );
    }
    GraphLink l1 = n.firstLink;
    GraphLink l2 = l1.getNext( n );
    GraphNode n1 = l1.getTarget( n );
    GraphNode n2 = l2.getTarget( n );
    if ( n1.linkForTarget( n2.iLon, n2.iLat ) != null ) {
      return; // do not create a new 2-node loop
    }
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


  /**
   * look for forward transfer node chains and apply
   * Douglas-Peucker filtering to them
   */
  public static void filter(List<GraphNode> nodes) {

    // find the transfer-nodes
    for( GraphNode n : nodes ) {
      if ( isTransferNode(n) ) {
        n.setBits( GraphNode.TRANSFERNODE_BIT );
      }
    }

    // loop over network nodes and follow forward links
    for( GraphNode n : nodes ) {
      if ( n.hasBits( GraphNode.TRANSFERNODE_BIT ) ) {
        continue;
      }
      GraphLink l = n.firstLink;
      while (l != null) {
        if ( !l.isReverse(n)) {
          if ( l.getTarget(n).hasBits(GraphNode.TRANSFERNODE_BIT) ) {
            filter( n, l );
          }
        }
        l = l.getNext(n);
      }
    }
    // and finally drop all non-survivors
    for( GraphNode n : nodes ) {
      if (n.hasBits(GraphNode.TRANSFERNODE_BIT) && !n.hasBits(GraphNode.DP_SURVIVOR_BIT)) {
        dropTransferNode(n);
      }
    }
  }

  private static void filter(GraphNode node, GraphLink link) {

    List<GraphNode> nodeList = new ArrayList<>(8);
    nodeList.add(node);
    GraphNode n = node;
    GraphLink l = link;
    while( n != null ) {
      GraphNode target = l.getTarget(n);
      nodeList.add(target);
      n = null;
      if (target.hasBits(GraphNode.TRANSFERNODE_BIT)) {
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
      filter( nodeList, 0, nodeList.size()-1 );
    }
  }

  /**
   * for each node (except first+last), eventually set the DP_SURVIVOR_BIT
   * @param nodes the list of nodes
   * @param first the index of the first node
   * @param last the index of the last node
   */
  private static void filter(List<GraphNode> nodes, int first, int last) {
    double maxSqDist = -1.;
    int index = -1;
    GraphNode p1 = nodes.get(first);
    GraphNode p2 = nodes.get(last);

    double[] lonlat2m = CheapRuler.getLonLatToMeterScales((p1.iLat + p2.iLat) >> 1);
    double dlon2m = lonlat2m[0];
    double dlat2m = lonlat2m[1];
    double dx = (p2.iLon - p1.iLon) * dlon2m;
    double dy = (p2.iLat - p1.iLat) * dlat2m;
    double d2 = dx * dx + dy * dy;
    for (int i = first + 1; i < last; i++) {
      GraphNode p = nodes.get(i);
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
        filter(nodes, first, index);
      }
      if (maxSqDist >= dp_sql_threshold) {
        nodes.get(index).setBits(GraphNode.DP_SURVIVOR_BIT);
      }
      if (last - index > 1) {
        filter(nodes, index, last);
      }
    }
  }
}
