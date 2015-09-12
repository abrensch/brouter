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
  private HashMap<OsmNode,OsmNode> hmap = new HashMap<OsmNode,OsmNode>();
  
  private ByteArrayUnifier abUnifier = new ByteArrayUnifier( 16384, false );

  private OsmNode testKey = new OsmNode();

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
    testKey.ilon = (int)(id >> 32);
    testKey.ilat = (int)(id & 0xffffffff);
    return hmap.get( testKey );
  }


  public void remove( OsmNode node )
  {
    hmap.remove( node );
  }

  /**
   * Put a node into the map
   * @return the previous node if that id existed, else null
   */
  public OsmNode put( OsmNode node )
  {
    return hmap.put( node, node );
  }


  /**
   * @return the number of nodes in that map
   */
  public int size()
  {
    return hmap.size();
  }

}
