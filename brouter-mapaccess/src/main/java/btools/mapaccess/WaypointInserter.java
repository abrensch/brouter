/**
 * WaypointInserter is used to insert the start- and end-nodes
 * into the graph.
 *
 * Every link between the two nodes a matched waypoint is located on
 * gets it's own crosspoint node (parallel links = 2-node-loops), so
 * a waypoint results in a list of nodes. If the crosspoint is identical
 * to one of the two nodes, no node is inserted and that node is used.
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.SortedHeap;

import java.util.ArrayList;
import java.util.List;

public class WaypointInserter {

  private final List<GraphNode> insertedNodes = new ArrayList<>();

  /**
   * Insert the crosspoints for the start- and the end-waypoint into the graph.
   *
   * @param nodesCache the node data provider
   * @param startWP    the matched start waypoint
   * @param endWP      the matched end waypoint
   */
  public WaypointInserter(NodesCache nodesCache, MatchedWaypoint startWP, MatchedWaypoint endWP) {
    GraphNode s1 = nodesCache.getGraphNode(startWP.node1);
    GraphNode s2 = nodesCache.getGraphNode(startWP.node2);
    GraphNode e1 = nodesCache.getGraphNode(endWP.node1);
    GraphNode e2 = nodesCache.getGraphNode(endWP.node2);

    for (GraphNode n : new GraphNode[]{s1, s2, e1, e2}) {
      if (!nodesCache.obtainNonHollowNode(n)) {
        throw new RuntimeException("Failed to obtain non-hollow node");
      }
      nodesCache.expandHollowLinkTargets(n);
    }

    GraphNode cs = new GraphNode( startWP.crossPoint.getIdFromPos() );
    GraphNode ce = new GraphNode( endWP.crossPoint.getIdFromPos() );
    cs.setBits( GraphNode.STARTNODE_BIT );
    ce.setBits( GraphNode.ENDNODE_BIT );

    boolean sameSegment = (s1 == e1 && s2 == e2) || (s1 == e2 && s2 == e1);

    if ( sameSegment ) {
      insertNodesBetween(s1, s2, new GraphNode[]{cs,ce});
    } else {
      insertNodesBetween(s1, s2, new GraphNode[]{cs});
      insertNodesBetween(e1, e2, new GraphNode[]{ce});
    }
  }

  /**
   * Get the list of nodes this linker instance inserted into the graph.
   *
   * @return the list of inserted nodes
   */
  public List<GraphNode> getInsertedNodes()
  {
    return insertedNodes;
  }

  // insert a waypoint into all links a-b
  private void insertNodesBetween(GraphNode n1, GraphNode n2, GraphNode[] splitNodes) {
    for (GraphLink link : linksBetween(n1, n2)) {
      insertNodesIntoLink(n1, link, splitNodes);
    }
  }

  public void insertNodesIntoLink(GraphNode n, GraphLink link, GraphNode[] splitNodes)
  {
    SortedHeap<GraphNode> sortedNodes = new SortedHeap<>();
    for ( GraphNode sn : splitNodes )
    {
      sortedNodes.add( n.calcDistance( sn ), sn );
    }
    GraphNode t = link.getTarget(n);
    for ( ; ; )
    {
      GraphNode splitNode = sortedNodes.popLowestKeyValue();
      if ( splitNode == null )
      {
        break;
      }
      GraphNode nn = insertNodeIntoLink( n, link, splitNode );
      if ( nn != n && nn != t) {
        // the link was split, so we need to insert the remaining nodes into the new link
        for( GraphLink l = nn.firstLink; l != null; l = l.getNext(nn) ) {
          if ( l.getTarget(nn) != n ) {
            link = l;
          }
        }
        n = nn;
      }
      n.setBits( splitNode.getBits() );
      if (!insertedNodes.contains(n)) {
        insertedNodes.add(n);
      }
    }
  }

  /**
   * Insert a node into the given link
   *
   * @param n           any one of the two nodes the link connects
   * @param link        the link to split
   * @param splitNode   template for the new node (only the position is used)
   * @return the inserted node, or n or t if the split node has the same position (nothing inserted)
   */
  public GraphNode insertNodeIntoLink(GraphNode n, GraphLink link, GraphNode splitNode) {

    GraphNode t = link.getTarget(n);
    boolean reverse = link.isReverse(n);
    byte[] wayDescription = link.wayDescription;

    if (splitNode.getIdFromPos() == n.getIdFromPos()) {
      return n;
    }
    if (splitNode.getIdFromPos() == t.getIdFromPos()) {
      return t;
    }

    // remove the link
    n.unlinkLink(link);
    if (!link.isLinkUnused()) {
      t.unlinkLink(link);
    }

    // create a copy (just as a clear concept on graph-members)
    splitNode = new GraphNode(splitNode.getIdFromPos());

    // elevation interpolation
    if (n.sElev == GraphNode.NO_ELEV || t.sElev == GraphNode.NO_ELEV) {
      splitNode.sElev = GraphNode.NO_ELEV;
    } else {
      double f = n.calcDistance( splitNode ) / (double)n.calcDistance( t );
      splitNode.sElev = (short) (n.sElev * (1. - f) + t.sElev * f);
    }

    if (reverse) {
      t.createLink(wayDescription, splitNode);
      splitNode.createLink(wayDescription, n);
    } else {
      n.createLink(wayDescription, splitNode);
      splitNode.createLink(wayDescription, t);
    }

    // check if we tampered with a TR
    checkTrPairing(n, t, splitNode);
    checkTrPairing(t, n, splitNode);

    return splitNode;
  }

  private void checkTrPairing(GraphNode n1, GraphNode n2, GraphNode n3) {

    boolean n1n2StillConnected = false;
    for (GraphLink l = n1.firstLink; l != null; l = l.getNext(n1)) {
      if (l.getTarget(n1) == n2) {
        n1n2StillConnected = true;
      }
    }

    for (TurnRestriction tr = n1.firstRestriction; tr != null; tr = tr.next) {
      boolean fromMatch = tr.fromLon == n2.iLon && tr.fromLat == n2.iLat;
      boolean toMatch = tr.toLon == n2.iLon && tr.toLat == n2.iLat;
      if (fromMatch || toMatch) {
        if (n1n2StillConnected) {
          n1.addTurnRestriction(tr.createCopy());
        }
        if (fromMatch) {
          tr.fromLon = n3.iLon;
          tr.fromLat = n3.iLat;
        }
        if (toMatch) {
          tr.toLon = n3.iLon;
          tr.toLat = n3.iLat;
        }
      }
    }
  }

  private static List<GraphLink> linksBetween(GraphNode a, GraphNode b) {
    List<GraphLink> links = new ArrayList<>();
    for (GraphLink l = a.firstLink; l != null; l = l.getNext(a)) {
      GraphNode target = l.getTarget(a);
      if (target == b) {
        links.add(l);
      }
    }
    return links;
  }
}
