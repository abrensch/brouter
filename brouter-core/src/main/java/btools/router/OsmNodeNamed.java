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
  public double radius; // radius of nogopoint (in meters)
  public double nogoWeight;  // weight for nogopoint
  public boolean isNogo = false;

  @Override
  public String toString()
  {
    if ( Double.isNaN(nogoWeight ) ) {
      return ilon + "," + ilat + "," + name;
    } else {
      return ilon + "," + ilat + "," + name + "," + nogoWeight;
    }
  }

  public static OsmNodeNamed decodeNogo( String s )
  {
    OsmNodeNamed n = new OsmNodeNamed();
    int idx1 = s.indexOf( ',' );
    n.ilon = Integer.parseInt( s.substring( 0, idx1 ) );
    int idx2 = s.indexOf( ',', idx1+1 );
    n.ilat = Integer.parseInt( s.substring( idx1+1, idx2 ) );
    int idx3 = s.indexOf( ',', idx2+1 );
    if ( idx3 == -1) {
        n.name = s.substring( idx2 + 1 );
        n.nogoWeight = Double.NaN;
    } else {
        n.name = s.substring( idx2+1, idx3 );
        n.nogoWeight = Double.parseDouble( s.substring( idx3 + 1 ) );
    }
    n.isNogo = true;
    return n;
  }
}
