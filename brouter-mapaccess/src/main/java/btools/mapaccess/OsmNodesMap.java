/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;

import btools.util.ByteArrayUnifier;

public final class OsmNodesMap
{
  private HashMap<Long,OsmNode> hmap = new HashMap<Long,OsmNode>();
  
  private ByteArrayUnifier abUnifier = new ByteArrayUnifier( 16384, false );

  public ByteArrayUnifier getByteArrayUnifier()
  {
    return abUnifier;
  }
  
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

  /**
   * Put a node into the map
   * @return the previous node if that id existed, else null
   */
  public OsmNode put( long id, OsmNode node )
  {
    return hmap.put( new Long( id  ), node );
  }


  /**
   * @return the number of nodes in that map
   */
  public int size()
  {
    return hmap.size();
  }

}
