package btools.router;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class OsmNogoPolygonTest {

  OsmNogoPolygon p;
  
  @Before
  public void setUp() throws Exception {
    p = new OsmNogoPolygon();
    p.addVertex(1000, 1000);
    p.addVertex(2001, 1000);
    p.addVertex(2001, 1250);
    p.addVertex(1750, 1250);
    p.addVertex(1750, 1750);
    p.addVertex(2001, 1750);
    p.addVertex(2001, 2001);
    p.addVertex(1000, 2001);
  }

  @Test
  public void testCalcBoundingCircle() {
    p.calcBoundingCircle();
    assertEquals(1501,p.ilat);
    assertEquals(1501,p.ilon);
    assertEquals(707.813887968,p.radius,0.5);
  }

  @Test
  public void testIntersectsOrIsWithin() {
    assertFalse(p.intersectsOrIsWithin(0,0, 0,0));
    assertFalse(p.intersectsOrIsWithin(1800,1500, 1800,1500));
    assertFalse(p.intersectsOrIsWithin(1500,2002, 1500,2002));
    assertTrue(p.intersectsOrIsWithin(1750, 1500, 1800,1500));
    assertTrue(p.intersectsOrIsWithin(1500, 2001, 1500,2002));
    assertTrue(p.intersectsOrIsWithin(1100, 1000, 1900, 1000));
    assertTrue(p.intersectsOrIsWithin(0, 0, 1500,1500));
    assertTrue(p.intersectsOrIsWithin(500, 1500, 1500, 1500));
    assertTrue(p.intersectsOrIsWithin(500, 1500, 2000, 1500));
    assertTrue(p.intersectsOrIsWithin(1400, 1500, 1500, 1500));
  }

}
