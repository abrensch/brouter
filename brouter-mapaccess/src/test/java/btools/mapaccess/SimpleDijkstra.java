package btools.mapaccess;

import btools.util.SortedHeap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Dijkstra router for tests.
 *
 * Cost is just the distance, one-way restrictions and turn restrictions are ignored.
 * It inserts the crosspoints of both waypoints into the graph (see {@link WaypointInserter}),
 */
public class SimpleDijkstra
{
  /**
   * A path-head: the node reached, the accumulated cost and
   * the path it was reached from (null for the start).
   */
  private static final class SimplePath
  {
    final GraphNode node;
    final int cost;
    final SimplePath origin;
    SimplePath( GraphNode node, int cost, SimplePath origin )
    {
      this.node = node;
      this.cost = cost;
      this.origin = origin;
    }
  }
  private final NodesCache nodesCache;
  private final List<GraphNode> route = new ArrayList<>();
  /**
   * @param nodesCacheTemplate the node data provider
   * @param startWP    the matched start waypoint
   * @param endWP      the matched end waypoint
   */
  public SimpleDijkstra( NodesCache nodesCacheTemplate, MatchedWaypoint startWP, MatchedWaypoint endWP )
  {
    this.nodesCache = new NodesCache(nodesCacheTemplate);

    SortedHeap<SimplePath> heap = new SortedHeap<>();

    for ( GraphNode n : new WaypointInserter( nodesCache, startWP, endWP ).getInsertedNodes() )
    {
      if ( n.hasBits( GraphNode.STARTNODE_BIT ) )
      {
        heap.add( 0, new SimplePath( n, 0, null ) );
      }
    }

    for ( ;; )
    {
      SimplePath path = heap.popLowestKeyValue();
      if ( path == null )
      {
        break;
      }
      GraphNode n = path.node;
      if ( n.hasBits( GraphNode.ENDNODE_BIT ) )
      {
        // we found it, now reconstruct the route from the path
        for ( SimplePath p = path; p != null; p = p.origin )
        {
          route.add( p.node );
        }
        Collections.reverse( route );
        break;
      }
      expand( n );
      for ( GraphLink l = n.firstLink; l != null; l = l.getNext( n ) )
      {
        GraphNode t = l.getTarget( n );
        int cost = path.cost + n.calcDistance( t );
        SimplePath np = new SimplePath( t, cost, path );
        heap.add( cost, np );
      }
    }
  }

  /**
   * @return the nodes from start to end (inclusive), or an empty list if there is no route
   */
  public List<GraphNode> getRoute()
  {
    return route;
  }

  private void expand( GraphNode n )
  {
    if ( !nodesCache.obtainNonHollowNode( n ) )
    {
      throw new RuntimeException( "Failed to obtain non-hollow node " + n );
    }
    nodesCache.expandHollowLinkTargets( n );
  }
}
