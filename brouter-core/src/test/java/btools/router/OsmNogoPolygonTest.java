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

**********************************************************************************************/
package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class OsmNogoPolygonTest {

  static final int offset_x = 11000000;
  static final int offset_y = 50000000;
  
  static OsmNogoPolygon polygon;

  static final double[] lons = {  1.0,  1.0,  0.5, 0.5, 1.0, 1.0, -1.1, -1.0 };
  static final double[] lats = { -1.0, -0.1, -0.1, 0.1, 0.1, 1.0,  1.1, -1.0 };

  static int toOsmLon(double lon) {
    return (int)( ( lon + 180. ) *1000000. + 0.5)+offset_x; // see ServerHandler.readPosition()
  }
  
  static int toOsmLat(double lat) {
    return (int)( ( lat +  90. ) *1000000. + 0.5)+offset_y;
  }
  
  static double coslat(int lat) // see RoutingContext.calcDistance()
  {
    final double l = (lat - 90000000) * 0.00000001234134; // 0.01234134 = Pi/(sqrt(2)*180)
    final double l2 = l*l;
    final double l4 = l2*l2;
//    final double l6 = l4*l2;
    return 1.- l2 + l4 / 6.; // - l6 / 90;
  }
  
  @BeforeClass
  public static void setUp() throws Exception {
    polygon = new OsmNogoPolygon();
    for (int i = 0; i<lons.length; i++) {
      polygon.addVertex(toOsmLon(lons[i]),toOsmLat(lats[i]));
    }
  }
  
  @AfterClass
  public static void tearDown() throws Exception {
  }

  @Test
  public void testCalcBoundingCircle() {
    polygon.calcBoundingCircle();
    double r = polygon.radius;
    for (int i=0; i<lons.length; i++) {
      double py = toOsmLat(lats[i]);
      double dpx = (toOsmLon(lons[i]) - polygon.ilon) * coslat(polygon.ilat);
      double dpy = py - polygon.ilat;
      double r1 = Math.sqrt(dpx * dpx + dpy * dpy) * 0.000001;
      double diff = r-r1;
      assertTrue("i: "+i+" r("+r+") >= r1("+r1+")", diff >= 0);
    }
  }

  @Test
  public void testIsWithin() {
	  // for points exactly on the edge of the polygon the result is not the same for all directions.
	  // that doesn't have a major impact on routing though.
    double[] plons   = {  0.0,   0.5,   1.0,  -1.5,  -0.5, }; //  1.0,   1.0,   0.5,   0.5,   0.5, 
    double[] plats   = {  0.0,   1.5,   0.0,   0.5,  -1.5, }; // -1.0,  -0.1,  -0.1,   0.0,   0.1,
    boolean[] within = { true, false, false, false, false, }; // false, false, false, false, false, 

    for (int i=0; i<plons.length; i++) {
      assertEquals("("+plons[i]+","+plats[i]+")",within[i],polygon.isWithin(toOsmLon(plons[i]), toOsmLat(plats[i])));
    }
  }

  @Test
  public void testIntersectsOrIsWithin() {
    double[] p0lons  = {  0.0,   1.0, -0.5,  0.5,  0.7,  0.7,  0.7,  -1.5, -1.5, };
    double[] p0lats  = {  0.0,   0.0,  0.5,  0.5,  0.5,  0.05, 0.05, -1.5,  0.2, };
    double[] p1lons  = {  0.0,   1.0,  0.5,  1.0,  0.7,  0.7,  0.7,  -0.5, -0.2, };
    double[] p1lats  = {  0.0,   0.0,  0.5,  0.5, -0.5, -0.5, -0.05, -0.5,  1.5, };
    boolean[] within = { true, false, true, true, true, true, false, true, true, };

    for (int i=0; i<p0lons.length; i++) {
      assertEquals("("+p0lons[i]+","+p0lats[i]+")-("+p1lons[i]+","+p1lats[i]+")",within[i],polygon.intersectsOrIsWithin(toOsmLon(p0lons[i]), toOsmLat(p0lats[i]), toOsmLon(p1lons[i]), toOsmLat(p1lats[i])));
    }
  }

}
