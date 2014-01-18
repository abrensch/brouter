/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmNode;

public class OsmNodeNamed extends OsmNode
{
  public String name;
  public double radius; // radius of nogopoint
  public boolean isNogo = false;
  
  @Override
  public String toString()
  {
    return ilon + "," + ilat + "," + name;
  }
  
  public static OsmNodeNamed decodeNogo( String s )
  {
    OsmNodeNamed n = new OsmNodeNamed();
    int idx1 = s.indexOf( ',' );
    n.ilon = Integer.parseInt( s.substring( 0, idx1 ) );
    int idx2 = s.indexOf( ',', idx1+1 );
    n.ilat = Integer.parseInt( s.substring( idx1+1, idx2 ) );
    n.name = s.substring( idx2+1 );
    n.isNogo = true;
    return n;
  }
}
