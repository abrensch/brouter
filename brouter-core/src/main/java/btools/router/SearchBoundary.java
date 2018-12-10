/**
 * static helper class for handling datafiles
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmNode;


public final class SearchBoundary
{

    private int minlon0;
    private int minlat0;
    private int maxlon0;
    private int maxlat0;

    private int minlon;
    private int minlat;
    private int maxlon;
    private int maxlat;
    private int radius;
    private OsmNode p;

    int direction;

    /**
     * @param radius    Search radius in meters.
     */
    public SearchBoundary( OsmNode n, int radius, int direction )
    {
      this.radius = radius;
      this.direction = direction;

      p = new OsmNode( n.ilon, n.ilat );

      int lon = (n.ilon / 5000000 ) * 5000000;
      int lat = (n.ilat / 5000000 ) * 5000000;

      minlon0 = lon -  5000000;
      minlat0 = lat -  5000000;
      maxlon0 = lon + 10000000;
      maxlat0 = lat + 10000000;

      minlon = lon -  1000000;
      minlat = lat -  1000000;
      maxlon = lon +  6000000;
      maxlat = lat +  6000000;
    }

    public static String getFileName( OsmNode n )
    {
      int lon = (n.ilon / 5000000 ) * 5000000;
      int lat = (n.ilat / 5000000 ) * 5000000;

      int dlon = lon / 1000000 -180;
      int dlat = lat / 1000000 - 90;

      String slon = dlon < 0 ? "W" + (-dlon) : "E" + dlon;
      String slat = dlat < 0 ? "S" + (-dlat) : "N" + dlat;
      return slon + "_" + slat + ".trf";
    }

    public boolean isInBoundary( OsmNode n, int cost )
    {
      if ( radius > 0 )
      {
        return n.calcDistance( p ) < radius;
      }
      if ( cost == 0 )
      {
        return n.ilon > minlon0 && n.ilon < maxlon0 && n.ilat > minlat0 && n.ilat < maxlat0;
      }
      return n.ilon > minlon && n.ilon < maxlon && n.ilat > minlat && n.ilat < maxlat;
    }

    public int getBoundaryDistance( OsmNode n )
    {
      switch( direction )
      {
        case 0: return n.calcDistance( new OsmNode( n.ilon, minlat ) );
        case 1: return n.calcDistance( new OsmNode( minlon, n.ilat ) );
        case 2: return n.calcDistance( new OsmNode( n.ilon, maxlat ) );
        case 3: return n.calcDistance( new OsmNode( maxlon, n.ilat ) );
        default: throw new IllegalArgumentException( "undefined direction: "+ direction );
      }
    }

}
