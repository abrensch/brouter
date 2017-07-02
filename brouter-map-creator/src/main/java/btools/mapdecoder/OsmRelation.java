package btools.mapdecoder;

import java.util.List;

public class OsmRelation extends OsmObject
{
  public List<OsmRelationMember> members;

  // bounding box
  int minx;
  int miny;
  int maxx;
  int maxy;

  public void calcBBox()
  {
    for( int i=0; i<members.size(); i++ )
    {
      OsmWay w = members.get(i).way;
      if ( i == 0 )
      {
        minx = w.minx;
        maxx = w.maxx;
        miny = w.miny;
        maxy = w.maxy;
      }
      else
      {
        if ( w.minx < minx ) minx = w.minx;
        if ( w.maxx > maxx ) maxx = w.maxx;
        if ( w.miny < miny ) miny = w.miny;
        if ( w.maxy > maxy ) maxy = w.maxy;
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
