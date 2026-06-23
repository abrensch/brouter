package btools.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CheapRulerTest {

  static int toOsmLon(double lon) {
    return (int) ((lon + 180.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  static int toOsmLat(double lat) {
    return (int) ((lat + 90.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  // Cardinal directions are independent of longitude scaling, so the scaled
  // bearing must hit the exact compass values (0=N, 90=E, 180=S, 270=W).
  @Test
  public void scaledBearingHitsCardinalsExactly() {
    int lon = toOsmLon(7.5);
    int lat = toOsmLat(47.5);
    int d = 10000; // ~1.1km north / ~0.75km east at this latitude
    assertEquals("due north", 0.0, CheapRuler.getScaledBearing(lon, lat, lon, lat + d), 1e-6);
    assertEquals("due east", 90.0, CheapRuler.getScaledBearing(lon, lat, lon + d, lat), 1e-6);
    assertEquals("due south", 180.0, CheapRuler.getScaledBearing(lon, lat, lon, lat - d), 1e-6);
    assertEquals("due west", 270.0, CheapRuler.getScaledBearing(lon, lat, lon - d, lat), 1e-6);
  }

  // Off the equator a leg with equal integer dLon and dLat is NOT a 45° bearing:
  // longitude degrees cover less ground, so getScaledBearing tilts the bearing
  // toward north. The raw CheapAngleMeter.getDirection ignores this and reports
  // ~45°. This divergence is exactly why the round-trip heading-persistence term
  // must compare two scaled bearings, not one scaled and one raw.
  @Test
  public void scaledBearingAppliesLongitudeCompressionOffEquator() {
    int lon = toOsmLon(7.5);
    int lat = toOsmLat(47.5);
    int d = 10000;
    double scaled = CheapRuler.getScaledBearing(lon, lat, lon + d, lat + d);
    double raw = CheapAngleMeter.getDirection(lon, lat, lon + d, lat + d);

    assertEquals("raw bearing ignores latitude compression", 45.0, raw, 0.5);
    assertTrue("scaled bearing tilts toward north off the equator (got " + scaled + ")",
      scaled > 30.0 && scaled < 38.0);
    assertTrue("scaled and raw diverge by >8° at 47.5N (got " + (raw - scaled) + ")",
      raw - scaled > 8.0);
  }

  // The scaling factor is symmetric: reversing the leg flips the bearing by 180°.
  @Test
  public void scaledBearingIsAntisymmetric() {
    int lon = toOsmLon(7.5);
    int lat = toOsmLat(47.5);
    int d = 10000;
    double ab = CheapRuler.getScaledBearing(lon, lat, lon + d, lat + d);
    double ba = CheapRuler.getScaledBearing(lon + d, lat + d, lon, lat);
    assertEquals("reverse leg is 180° opposite", 180.0,
      Math.abs(CheapAngleMeter.normalizeRelative(ba - ab)), 1e-3);
  }
}
