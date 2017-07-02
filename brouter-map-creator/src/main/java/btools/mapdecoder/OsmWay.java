package btools.mapdecoder;

import java.util.List;

public class OsmWay extends OsmObject
{
  public List<OsmNode> nodes;

  // bounding box
  int minx;
  int miny;
  int maxx;
  int maxy;

  public void calcBBox()
  {
    for( int i=0; i<nodes.size(); i++ )
    {
      OsmNode n = nodes.get(i);
      if ( i == 0 )
      {
        minx = maxx = n.ilon;
        miny = maxy = n.ilat;
      }
      else
      {
        if ( n.ilon < minx ) minx = n.ilon;
        if ( n.ilon > maxx ) maxx = n.ilon;
        if ( n.ilat < miny ) miny = n.ilat;
        if ( n.ilat > maxy ) maxy = n.ilat;
      }              
    }
  }
 
  public boolean inBBox( int z, int x, int y )
  {
    int shift = 28-z;
    int x0 = x << shift;
    int x1 = (x+1) << shift;
    int y0 = y << shift;
    int y1 = (y+1) << shift;
    boolean outofbox = x1 < minx || x0 >= maxx  || y1 < miny || y0 >= maxy;
    return !outofbox;
  }
}
