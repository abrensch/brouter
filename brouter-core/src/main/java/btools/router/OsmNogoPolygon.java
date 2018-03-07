/**********************************************************************************************
   Copyright (C) 2018 Norbert Truchsess norbert.truchsess@t-online.de

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <http://www.gnu.org/licenses/>.

   The following methods are based on work of Dan Sunday published at:
   http://geomalgorithms.com/a03-_inclusion.html

   cn_PnPoly, wn_PnPoly, inSegment, intersect2D_2Segments

**********************************************************************************************/
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class OsmNogoPolygon extends OsmNodeNamed
{
  public final static class Point
  {
    public final int y;
    public final int x;

    Point(final int lon, final int lat)
    {
      x = lon;
      y = lat;
    }
  }

  public final List<Point> points = new ArrayList<Point>();
  
  public final boolean isClosed;
  
  public OsmNogoPolygon(boolean closed)
  {
    this.isClosed = closed;
    this.isNogo = true;
    this.name = "";
  }

  public final void addVertex(int lon, int lat)
  {
    points.add(new Point(lon, lat));
  }

  private final static double coslat(double lat)
  {
    final double l = (lat - 90000000) * 0.00000001234134; // 0.01234134 = Pi/(sqrt(2)*180)
    final double l2 = l*l;
    final double l4 = l2*l2;
//    final double l6 = l4*l2;
    return 1.- l2 + l4 / 6.; // - l6 / 90;
  }

 /**
  * calcBoundingCircle is inspired by the algorithm described on
  * http://geomalgorithms.com/a08-_containers.html
  * (fast computation of bounding circly in c). It is not as fast (the original
  * algorithm runs in linear time), as it may do more iterations but it takes
  * into account the coslat-factor being used for the linear approximation that
  * is also used in other places of brouter does change when moving the centerpoint
  * with each iteration.
  * This is done to ensure the calculated radius being used
  * in RoutingContext.calcDistance will actually contain the whole polygon.
  * 
  * For reasonable distributed vertices the implemented algorithm runs in O(n*ln(n)).
  * As this is only run once on initialization of OsmNogoPolygon this methods
  * overall usage of cpu is neglegible in comparism to the cpu-usage of the
  * actual routing algoritm.
  */
  public void calcBoundingCircle()
  {
    int cxmin, cxmax, cymin, cymax;
    cxmin = cymin = Integer.MAX_VALUE;
    cxmax = cymax = Integer.MIN_VALUE;
    
    // first calculate a starting center point as center of boundingbox
    for (int i = 0; i < points.size(); i++)
    {
      final Point p = points.get(i);
      if (p.x < cxmin)
      {
        cxmin = p.x;
      }
      else if (p.x > cxmax)
      {
        cxmax = p.x;
      }
      if (p.y < cymin)
      {
        cymin = p.y;
      }
      else if (p.y > cymax)
      {
        cymax = p.y;
      }
    }

    double cx = (cxmax+cxmin) / 2.0; // center of circle
    double cy = (cymax+cymin) / 2.0;
    double ccoslat = coslat(cy); // cosin at latitude of center
    double rad = 0;  // radius
    double rad2 = 0; // radius squared;

    double dpx = 0; // x-xomponent of vector from center to point
    double dpy = 0; // y-component
    double dmax2 = 0; // squared lenght of vector from center to point
    int i_max = -1;

    do
    { // now identify the point outside of the circle that has the greatest distance 
      for (int i = 0; i < points.size();i++)
      {
        final Point p = points.get(i);
        final double dpix = (p.x - cx) * ccoslat;
        final double dpiy = p.y-cy;
        final double dist2 = dpix * dpix + dpiy * dpiy;
        if (dist2 <= rad2)
        {
          continue;
        }
        if (dist2 > dmax2)
        {
          dmax2 = dist2; // new maximum distance found
          dpx = dpix;
          dpy = dpiy;
          i_max = i;
        }
      }
      if (i_max < 0)
      {
    	  break; // leave loop when no point outside the circle is found any more.
      }
      final double dist = Math.sqrt(dmax2);
      final double dd = 0.5 * (dist - rad) / dist;
  
      cx = cx + dd * dpx; // shift center toward point
      cy = cy + dd * dpy;
      ccoslat = coslat(cy);

      final Point p = points.get(i_max); // calculate new radius to just include this point
      final double dpix = (p.x - cx) * ccoslat;
      final double dpiy = p.y-cy;
      dmax2 = rad2 = dpix * dpix + dpiy * dpiy;
      rad = Math.sqrt(rad2);
      i_max = -1;
    }
    while (true);
    
    ilon = (int) Math.round(cx);
    ilat = (int) Math.round(cy);
    dpx = cx - ilon; // rounding error
    dpy = cy - ilat;
    // compensate rounding error of center-point
    radius = (rad + Math.sqrt(dpx * dpx + dpy * dpy)) * 0.000001;
    return;
  }

 /**
  * tests whether a segment defined by lon and lat of two points does either
  * intersect the polygon or any of the endpoints (or both) are enclosed by
  * the polygon. For this test the winding-number algorithm is
  * being used. That means a point being within an overlapping region of the
  * polygon is also taken as being 'inside' the polygon.
  * 
  * @param lon0 longitude of start point
  * @param lat0 latitude of start point
  * @param lon1 longitude of end point
  * @param lat1 latitude of start point
  * @return true if segment or any of it's points are 'inside' of polygon
  */
  public boolean intersects(int lon0, int lat0, int lon1, int lat1)
  {
    final Point p0 = new Point (lon0,lat0);
    final Point p1 = new Point (lon1,lat1);
    int i_last = points.size()-1;
    Point p2 = points.get(isClosed ? i_last : 0 );
    for (int i = isClosed ? 0 : 1 ; i <= i_last; i++)
    {
      Point p3 = points.get(i);
      // does it intersect with at least one of the polygon's segments?
      if (intersect2D_2Segments(p0,p1,p2,p3) > 0)
      {
        return true;
      }
      p2 = p3;
    }
    return false;
  }

  public boolean isOnPolyline( long px, long py )
  {
    int i_last = points.size()-1;
    Point p1 = points.get(0);
    for (int i = 1 ; i <= i_last; i++)
    {
      final Point p2 = points.get(i);
      if (OsmNogoPolygon.isOnLine(px,py,p1.x,p1.y,p2.x,p2.y))
      {
        return true;
      }
      p1 = p2;
    }
    return false;
  }
  
  public static boolean isOnLine( long px, long py, long p0x, long p0y, long p1x, long p1y )
  {
    final double v10x = px-p0x;
    final double v10y = py-p0y;
    final double v12x = p1x-p0x;
    final double v12y = p1y-p0y;
    
    if ( v10x == 0 ) // P0->P1 vertical?
    {
      if ( v10y == 0 ) // P0 == P1?
      {
        return true;
      }
      if ( v12x != 0 ) // P1->P2 not vertical?
      {
        return false; 
      }
      return ( v12y / v10y ) >= 1; // P1->P2 at least as long as P1->P0?
    }
    if ( v10y == 0 ) // P0->P1 horizontal?
    {
      if ( v12y != 0 ) // P1->P2 not horizontal?
      {
        return false; 
      }
      // if ( P10x == 0 ) // P0 == P1? already tested
      return ( v12x / v10x ) >= 1; // P1->P2 at least as long as P1->P0?
    }
    final double kx = v12x / v10x;
    if ( kx < 1 )
    {
      return false;
    }
    return kx == v12y / v10y;
  }

/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */
 /**
  * winding number test for a point in a polygon
  * 
  * @param p a point
  * @param v list of vertex points forming a polygon. This polygon
  *          is implicitly closed connecting the last and first point.
  * @return the winding number (=0 only when P is outside)
  */
  public boolean isWithin(final long px, final long py)
  {
    int wn = 0; // the winding number counter

    // loop through all edges of the polygon
    final int i_last = points.size()-1;
    final Point p0 = points.get(isClosed ? i_last : 0);
    long p0x = p0.x; // need to use long to avoid overflow in products
    long p0y = p0.y;
    
    for (int i = isClosed ? 0 : 1; i <= i_last; i++) // edge from v[i] to v[i+1]
    {
      final Point p1 = points.get(i);

      final long p1x = p1.x;
      final long p1y = p1.y;

      if (OsmNogoPolygon.isOnLine(px, py, p0x, p0y, p1x, p1y))
      {
        return true;
      }

      if (p0y <= py)  // start y <= p.y
      {
        if (p1y > py) // an upward crossing
        {             // p left of edge
          if (((p1x - p0x) * (py - p0y) - (px - p0x) * (p1y - p0y)) > 0)
          {
            ++wn;     // have a valid up intersect
          }
        }
      }
      else // start y > p.y (no test needed)
      {         
        if (p1y <= py) // a downward crossing
        {              // p right of edge
          if (((p1x - p0x) * (py - p0y) - (px - p0x) * (p1y - p0y)) < 0)
          {
            --wn;      // have a valid down intersect
          }
        }
      }
      p0x = p1x;
      p0y = p1y;
    }
    return wn != 0;
  }

/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */
 /**
  * inSegment(): determine if a point is inside a segment
  * 
  * @param p a point
  * @param seg_p0 starting point of segment
  * @param seg_p1 ending point of segment
  * @return 1 = P is inside S
  *         0 = P is not inside S
  */
  private static boolean inSegment( final Point p, final Point seg_p0, final Point seg_p1)
  {
    final int sp0x = seg_p0.x;
    final int sp1x = seg_p1.x;
    
    if (sp0x != sp1x) // S is not vertical
    {
      final int px = p.x;
      if (sp0x <= px && px <= sp1x)
      {
        return true;
      }
      if (sp0x >= px && px >= sp1x)
      {
        return true;
      }
    }
    else // S is vertical, so test y coordinate
    {
      final int sp0y = seg_p0.y;
      final int sp1y = seg_p1.y;
      final int py = p.y;
      
      if (sp0y <= py && py <= sp1y)
      {
        return true;
      }
      if (sp0y >= py && py >= sp1y)
      {
        return true;
      }
    }
    return false;
  }
  
/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */
 /**
  * intersect2D_2Segments(): find the 2D intersection of 2 finite segments 
  * @param s1p0 start point of segment 1
  * @param s1p1 end point of segment 1
  * @param s2p0 start point of segment 2
  * @param s2p1 end point of segment 2
  * @return 0=disjoint (no intersect)
  *         1=intersect in unique point I0
  *         2=overlap in segment from I0 to I1
  */
  private static int intersect2D_2Segments( final Point s1p0, final Point s1p1, final Point s2p0, final Point s2p1 )
  {     
    final long ux = s1p1.x - s1p0.x; // vector u = S1P1-S1P0 (segment 1)
    final long uy = s1p1.y - s1p0.y;
    final long vx = s2p1.x - s2p0.x; // vector v = S2P1-S2P0 (segment 2)
    final long vy = s2p1.y - s2p0.y;
    final long wx = s1p0.x - s2p0.x; // vector w = S1P0-S2P0 (from start of segment 2 to start of segment 1
    final long wy = s1p0.y - s2p0.y;
    
    final double d = ux * vy - uy * vx;

    // test if  they are parallel (includes either being a point)
    if (d == 0)           // S1 and S2 are parallel
    {
      if ((ux * wy - uy * wx) != 0 || (vx * wy - vy * wx) != 0)
      {
        return 0; // they are NOT collinear
      }

      // they are collinear or degenerate
      // check if they are degenerate  points
      final boolean du = ((ux == 0) && (uy == 0));
      final boolean dv = ((vx == 0) && (vy == 0));
      if (du && dv)            // both segments are points
      {
        return (wx == 0 && wy == 0) ? 0 : 1; // return 0 if they are distinct points
      }
      if (du)                     // S1 is a single point
      {
        return inSegment(s1p0, s2p0, s2p1) ? 1 : 0; // is it part of S2?
      }
      if (dv)                     // S2 a single point
      {
        return inSegment(s2p0, s1p0, s1p1) ? 1 : 0;  // is it part of S1?
      }
      // they are collinear segments - get  overlap (or not)
      double t0, t1;                    // endpoints of S1 in eqn for S2
      final int w2x = s1p1.x - s2p0.x; // vector w2 = S1P1-S2P0 (from start of segment 2 to end of segment 1)
      final int w2y = s1p1.y - s2p0.y;
      if (vx != 0)
      {
        t0 = wx / vx;
        t1 = w2x / vx;
      }
      else
      {
        t0 = wy / vy;
        t1 = w2y / vy;
      }
      if (t0 > t1)                   // must have t0 smaller than t1
      {
        final double t=t0;     // swap if not
        t0=t1;
        t1=t;
      }
      if (t0 > 1 || t1 < 0)
      {
        return 0;      // NO overlap
      }
      t0 = t0<0? 0 : t0;               // clip to min 0
      t1 = t1>1? 1 : t1;               // clip to max 1
    
      return (t0 == t1) ? 1 : 2;        // return 1 if intersect is a point
    }

    // the segments are skew and may intersect in a point
    // get the intersect parameter for S1
    
    final double sI = (vx * wy - vy * wx) / d;
    if (sI < 0 || sI > 1)               // no intersect with S1
    {
      return 0;
    }

    // get the intersect parameter for S2
    final double tI = (ux * wy - uy * wx) / d;
    return (tI < 0 || tI > 1) ? 0 : 1; // return 0 if no intersect with S2
  }
}
