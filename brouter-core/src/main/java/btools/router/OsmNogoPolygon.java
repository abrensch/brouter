/*
 * Copyright 2018 Norbert Truchsess <norbert.truchsess@t-online.de>
 * 
 * this code is based on work of Dan Sunday published at:
 * http://geomalgorithms.com/a03-_inclusion.html
 * (implementation of winding number algorithm in c)
 * http://geomalgorithms.com/a08-_containers.html
 * (fast computation of bounding circly in c)
 * 
 * Copyright 2001 softSurfer, 2012 Dan Sunday
 * This code may be freely used and modified for any purpose providing that
 * this copyright notice is included with it. SoftSurfer makes no warranty for
 * this code, and cannot be held liable for any real or imagined damage
 * resulting from its use. Users of this code must verify correctness for
 * their application.
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class OsmNogoPolygon extends OsmNodeNamed
{
  public final class Point {
    /**
     * The latitude
     */
    public final int y;

    /**
     * The longitude
     */
    public final int x;

    public Point(final int lon, final int lat)
    {
      x = lon;
      y = lat;
    }
  }

  public List<Point> P = new ArrayList<Point>();

  public void addVertex(int lon, int lat)
  {
    P.add(new Point(lon, lat));
  }

  public void calcBoundingCircle()
  {
    double Cx, Cy;    // Center of ball
    double rad, rad2; // radius and radius squared
    double xmin, xmax, ymin, ymax; // bounding box extremes
    int i_xmin, i_xmax, i_ymin, i_ymax; // index of P[] at box extreme

    // find a large diameter to start with
    // first get the bounding box and P[] extreme points for it
    xmin = xmax = P.get(0).x;
    ymin = ymax = P.get(0).y;
    i_xmin = i_xmax = i_ymin = i_ymax = 0;
    for (int i = 1; i < P.size(); i++)
    {
      Point Pi = P.get(i);
      if (Pi.x < xmin)
      {
        xmin = Pi.x;
        i_xmin = i;
      }
      else if (Pi.x > xmax)
      {
        xmax = Pi.x;
        i_xmax = i;
      }
      if (Pi.y < ymin)
      {
        ymin = Pi.y;
        i_ymin = i;
      }
      else if (Pi.y > ymax)
      {
        ymax = Pi.y;
        i_ymax = i;
      }
    }
    // select the largest extent as an initial diameter for the ball
    Point Pi_xmax = P.get(i_xmax);
    Point Pi_xmin = P.get(i_xmin);
    Point Pi_ymax = P.get(i_ymax);
    Point Pi_ymin = P.get(i_ymin);
    
    int dPx_x = (Pi_xmax.x - Pi_xmin.x); // diff of Px max and min
    int dPx_y = Pi_xmax.y - Pi_xmin.y;
    
    int dPy_x = Pi_ymax.x - Pi_ymin.x; // diff of Py max and min
    int dPy_y = Pi_ymax.y - Pi_ymin.y;

    int dx2 = dPx_x * dPx_x + dPx_y * dPx_y; // Px diff squared
    int dy2 = dPy_x * dPy_x + dPy_y * dPy_y; // Py diff squared
    
    if (dx2 >= dy2) // x direction is largest extent
    {
      Cx = Pi_xmin.x + dPx_x / 2.0; // Center = midpoint of extremes
      Cy = Pi_xmin.y + dPx_x / 2.0;
      
      double dPC_x = Pi_xmax.x - Cx;
      double dPC_y = Pi_xmax.y - Cy;
      
      rad2 = dPC_x * dPC_x + dPC_y * dPC_y; // radius squared
      
    }
    else // y direction is largest extent
    {
      Cx = Pi_ymin.x + dPy_x / 2.0; // Center = midpoint of extremes
      Cy = Pi_ymin.y + dPy_y / 2.0;
      
      double dPC_x = Pi_ymax.x - Cx;
      double dPC_y = Pi_ymax.y - Cy;
      
      rad2 = dPC_x * dPC_x + dPC_y * dPC_y; // radius squared
    }
    rad = Math.sqrt(rad2);

    // now check that all points P[i] are in the ball
    // and if not, expand the ball just enough to include them
    double dist, dist2;
    for (int i = 0; i < P.size(); i++)
    {  
      Point Pi = P.get(i);
      
      double dPC_x = Pi.x - Cx;
      double dPC_y = Pi.y - Cy;
      
      dist2 = dPC_x * dPC_x + dPC_y * dPC_y;

      if (dist2 <= rad2) // P[i] is inside the ball already
      {
        continue;
      }
      // P[i] not in ball, so expand ball to include it
      dist = Math.sqrt(dist2);
      rad = (rad + dist) / 2.0; // enlarge radius just enough
      rad2 = rad * rad;
      
      double dd = (dist - rad) / dist;
      
      Cx = Cx + dd * dPC_x; // shift Center toward
      Cy = Cy + dd * dPC_y;
    }
    ilon = (int) Math.round(Cx);
    ilat = (int) Math.round(Cy);
    // compensate rounding error of center-point
    radius = rad + Math.max(Math.abs(Cx - ilon), Math.abs(Cy - ilat));
    return;
  }

  public boolean intersectsOrIsWithin(int lon0, int lat0, int lon1, int lat1)
  {
    Point P0 = new Point (lon0,lat0);
    Point P1 = new Point (lon1,lat1);
    // is start or endpoint within polygon?
    if ((wn_PnPoly(P0, P) > 0) || (wn_PnPoly(P1, P) > 0))
    {
      return true;
    }
    Point P2 = P.get(0);
    for (int i = 1; i < P.size(); i++)
    {
      Point P3 = P.get(i);
      // does it intersect with at least one of the polygon's segments?
      if (intersect2D_2Segments(P0,P1,P2,P3) > 0)
      {
        return true;
      }
      P2 = P3;
    }
    return false;
  }

  /**
   * isLeft(): tests if a point is Left|On|Right of an infinite line. Input:
   * three points P0, P1, and P2 Return: >0 for P2 left of the line through P0
   * and P1 =0 for P2 on the line <0 for P2 right of the line See: Algorithm 1
   * "Area of Triangles and Polygons"
   */

  private static int isLeft(Point P0, Point P1, Point P2) {
    return ((P1.x - P0.x) * (P2.y - P0.y) - (P2.x - P0.x) * (P1.y - P0.y));
  }

  /**
   * cn_PnPoly(): crossing number test for a point in a polygon Input: P = a
   * point, V[] = vertex points of a polygon V[n+1] with V[n]=V[0] Return: 0 =
   * outside, 1 = inside This code is patterned after [Franklin, 2000]
   */

  private static boolean cn_PnPoly(Point P, List<Point> V)
  {
    int cn = 0; // the crossing number counter

    // loop through all edges of the polygon
    int last = V.size()-1;
    Point Vi = V.get(last);
    for (int i = 0; i <= last; i++)            // edge from V[i] to V[i+1]
    {
      Point Vi1 = V.get(i);

      if (((Vi.y <= P.y) && (Vi1.y > P.y))     // an upward crossing
          || ((Vi.y > P.y) && (Vi1.y <= P.y))) // a downward crossing
      {
        // compute the actual edge-ray intersect x-coordinate
        float vt = (float) (P.y - Vi.y) / (Vi1.y - Vi.y);

        if (P.x < Vi.x + vt * (Vi1.x - Vi.x))  // P.x < intersect
        {
          ++cn;                                // a valid crossing of y=P.y right of P.x
        }
      }
      Vi = Vi1;
    }
    return ((cn & 1) > 0);                     // 0 if even (out), and 1 if odd (in)
  }

  /**
   * wn_PnPoly(): winding number test for a point in a polygon Input: P = a
   * point, V = vertex points of a polygon V[n+1] with V[n]=V[0] Return: wn =
   * the winding number (=0 only when P is outside)
   */

  private static int wn_PnPoly(Point P, List<Point> V) {
    int wn = 0; // the winding number counter

    // loop through all edges of the polygon
    int last = V.size()-1;
    Point Vi = V.get(last);
    for (int i = 0; i <= last; i++)      // edge from V[i] to V[i+1]
    {
      Point Vi1 = V.get(i);

      if (Vi.y <= P.y) {                 // start y <= P.y
        if (Vi1.y > P.y) {               // an upward crossing
          if (isLeft(Vi, Vi1, P) > 0) {  // P left of edge
            ++wn;                        // have a valid up intersect
          }
        }
      } else {                           // start y > P.y (no test needed)
        if (Vi1.y <= P.y) {              // a downward crossing
          if (isLeft(Vi, Vi1, P) < 0) {  // P right of edge
            --wn;                        // have a valid down intersect
          }
        }
      }
      Vi = Vi1;
    }
    return wn;
  }

  /**
   * inSegment(): determine if a point is inside a segment
   * Input:  a point P, and a collinear segment S
   * Return: 1 = P is inside S
   *         0 = P is not inside S
   */
  
  private static boolean inSegment( Point P, Point SP0, Point SP1)
  {
    if (SP0.x != SP1.x)    // S is not  vertical
    {
      if (SP0.x <= P.x && P.x <= SP1.x)
      {
        return true;
      }
      if (SP0.x >= P.x && P.x >= SP1.x)
      {
        return true;
      }
    }
    else    // S is vertical, so test y  coordinate
    {
      if (SP0.y <= P.y && P.y <= SP1.y)
      {
        return true;
      }
      if (SP0.y >= P.y && P.y >= SP1.y)
      {
        return true;
      }
    }
    return false;
  }
  
  /**
   * intersect2D_2Segments(): find the 2D intersection of 2 finite segments 
   * Input:  two finite segments S1 and S2
   * Return: 0=disjoint (no intersect)
   *         1=intersect in unique point I0
   *         2=overlap in segment from I0 to I1
   */
  private static int intersect2D_2Segments( Point S1P0, Point S1P1, Point S2P0, Point S2P1 )
  {     
    int ux = S1P1.x - S1P0.x; // vector u = S1P1-S1P0 (segment 1)
    int uy = S1P1.y - S1P0.y;
    int vx = S2P1.x - S2P0.x; // vector v = S2P1-S2P0 (segment 2)
    int vy = S2P1.y - S2P0.y;
    int wx = S1P0.x - S2P0.x; // vector w = S1P0-S2P0 (from start of segment 2 to start of segment 1
    int wy = S1P0.y - S2P0.y;
    
    int D = ux * vy - uy * vx;

    // test if  they are parallel (includes either being a point)
    if (D == 0)           // S1 and S2 are parallel
    {
      if ((ux * wy - uy * wx) != 0 || (vx * wy - vy * wx) != 0)
      {
        return 0; // they are NOT collinear
      }

      // they are collinear or degenerate
      // check if they are degenerate  points
      boolean du = ux == 0 && uy == 0;
      boolean dv = vx == 0 && vy == 0;
      if (du && dv)            // both segments are points
      {
        return (wx == 0 && wy == 0) ? 0 : 1; // return 0 if they are distinct points
      }
      if (du)                     // S1 is a single point
      {
        return inSegment(S1P0, S2P0, S2P1) ? 1 : 0; // is it part of S2?
      }
      if (dv)                     // S2 a single point
      {
        return inSegment(S2P0, S1P0, S1P1) ? 1 : 0;  // is it part of S1?
      }
      // they are collinear segments - get  overlap (or not)
      float t0, t1;                    // endpoints of S1 in eqn for S2
      int w2x = S1P1.x - S2P0.x; // vector w2 = S1P1-S2P0 (from start of segment 2 to end of segment 1)
      int w2y = S1P1.y - S2P0.y;
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
        float t=t0;     // swap if not
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
    
    double sI = (vx * wy - vy * wx) / D;
    if (sI < 0 || sI > 1)               // no intersect with S1
    {
      return 0;
    }

    // get the intersect parameter for S2
    double tI = (ux * wy - uy * wx) / D;
    return (tI < 0 || tI > 1) ? 0 : 1; // return 0 if no intersect with S2
  }
}
