package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import btools.util.CheapRulerSingleton;

public class RoutingContextTest {
  static int toOsmLon(double lon) {
    return (int)( ( lon + 180. ) / CheapRulerSingleton.ILATLNG_TO_LATLNG + 0.5);
  }

  static int toOsmLat(double lat) {
    return (int)( ( lat +  90. ) / CheapRulerSingleton.ILATLNG_TO_LATLNG + 0.5);
  }

  @Test
  public void testCalcAngle() {
    RoutingContext rc = new RoutingContext();
    // Segment ends
    int lon0, lat0, lon1, lat1, lon2, lat2;

    lon0 = toOsmLon(2.317126);
    lat0 = toOsmLat(48.817927);
    lon1 = toOsmLon(2.317316);
    lat1 = toOsmLat(48.817978);
    lon2 = toOsmLon(2.317471);
    lat2 = toOsmLat(48.818043);
    assertEquals(
      "Works for an angle between -pi/4 and pi/4",
      10.,
      rc.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2),
      0.05 * 10.
    );

    lon0 = toOsmLon(2.317020662874013);
    lat0 = toOsmLat(48.81799440182911);
    lon1 = toOsmLon(2.3169460585876327);
    lat1 = toOsmLat(48.817812421536644);
    lon2 = lon0;
    lat2 = lat0;
    assertEquals(
      "Works for an angle between 3*pi/4 and 5*pi/4",
      180.,
      rc.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2),
      0.05 * 180.
    );

    lon0 = toOsmLon(2.317112);
    lat0 = toOsmLat(48.817802);
    lon1 = toOsmLon(2.317632);
    lat1 = toOsmLat(48.817944);
    lon2 = toOsmLon(2.317673);
    lat2 = toOsmLat(48.817799);
    assertEquals(
      "Works for an angle between -3*pi/4 and -pi/4",
      -100.,
      rc.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2),
      0.1 * 100.
    );

    lon0 = toOsmLon(2.317128);
    lat0 = toOsmLat(48.818072);
    lon1 = toOsmLon(2.317532);
    lat1 = toOsmLat(48.818108);
    lon2 = toOsmLon(2.317497);
    lat2 = toOsmLat(48.818264);
    assertEquals(
      "Works for an angle between pi/4 and 3*pi/4",
      100.,
      rc.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2),
      0.1 * 100.
    );
  }
}
