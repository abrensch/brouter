/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;

public final class OsmNodesMap
{
  private HashMap<Long,OsmNode> hmap = new HashMap<Long,OsmNode>();

  private NodesList completedNodes = null;

  /**
   * Get a node from the map
   * @return the node for the given id if exist, else null
   */
  public OsmNode get( long id )
  {
    return hmap.get( new Long( id  ) );
  }


  public void remove( long id )
  {
    hmap.remove( new Long( id  ) );
  }

  public void removeCompletedNodes()
  {
    for( NodesList le = completedNodes; le != null; le = le.next )
    {
      remove( le.node.getIdFromPos() );
    }
    completedNodes = null;
  }

  public void registerCompletedNode( OsmNode n )
  {
      if ( n.completed ) return;
      n.completed = true;
      NodesList le = new NodesList();
      le.node = n;
      if ( completedNodes != null ) le.next = completedNodes;
      completedNodes = le;
  }

  /**
   * Put a node into the map
   * @return the previous node if that id existed, else null
   */
  public OsmNode put( long id, OsmNode node )
  {
    return hmap.put( new Long( id  ), node );
  }

  /**
   * Return the internal list.
   * A reference is returned, not a copy-
   * @return the nodes list
   */
  public Collection<OsmNode> nodes()
  {
    return hmap.values();
  }

  /**
   * @return the number of nodes in that map
   */
  public int size()
  {
    return hmap.size();
  }

  /**
   * cleanup the map by removing the nodes
   * with no hollow issues
   */

  private int dontCareCount = 0;

  public void removeCompleteNodes()
  {
   if ( ++dontCareCount < 5 ) return;
   dontCareCount = 0;

   ArrayList<OsmNode> delNodes = new ArrayList<OsmNode>();

    for( OsmNode n : hmap.values() )
    {
      if ( n.isHollow() || n.hasHollowLinks() )
      {
        continue;
      }
      delNodes.add( n );
    }

    if ( delNodes.size() > 0 )
    {
//      System.out.println( "removing " + delNodes.size() + " nodes" );
      for( OsmNode n : delNodes )
      {
        hmap.remove( new Long( n.getIdFromPos() ) );
      }
    }
  }
}
