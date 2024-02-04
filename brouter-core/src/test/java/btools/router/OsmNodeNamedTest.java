package btools.router;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import btools.util.CheapRuler;

public class OsmNodeNamedTest {
  static int toOsmLon(double lon) {
    return (int) ((lon + 180.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  static int toOsmLat(double lat) {
    return (int) ((lat + 90.) / CheapRuler.ILATLNG_TO_LATLNG + 0.5);
  }

  @Test
  public void testDistanceWithinRadius() {
    // Segment ends
    int lon1, lat1, lon2, lat2;
    // Circle definition
    OsmNodeNamed node = new OsmNodeNamed();
    // Center
    node.ilon = toOsmLon(2.334243);
    node.ilat = toOsmLat(48.824017);
    // Radius
    node.radius = 30;

    // Check distance within radius is correctly computed if the segment passes through the center
    lon1 = toOsmLon(2.332559);
    lat1 = toOsmLat(48.823822);
    lon2 = toOsmLon(2.335018);
    lat2 = toOsmLat(48.824105);
    double totalSegmentLength = CheapRuler.distance(lon1, lat1, lon2, lat2);
    assertEquals(
      "Works for segment aligned with the nogo center",
      2 * node.radius,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.01 * (2 * node.radius)
    );

    // Check distance within radius is correctly computed for a given circle
    node.ilon = toOsmLon(2.33438);
    node.ilat = toOsmLat(48.824275);
    assertEquals(
      "Works for a segment with no particular properties",
      27.5,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.1 * 27.5
    );

    // Check distance within radius is the same if we reverse start and end point
    assertEquals(
      "Works if we switch firs and last point",
      node.distanceWithinRadius(lon2, lat2, lon1, lat1, totalSegmentLength),
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.01
    );

    // Check distance within radius is correctly computed if a point is inside the circle
    lon2 = toOsmLon(2.334495);
    lat2 = toOsmLat(48.824045);
    totalSegmentLength = CheapRuler.distance(lon1, lat1, lon2, lat2);
    assertEquals(
      "Works if last point is within the circle",
      17,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.1 * 17
    );

    lon1 = toOsmLon(2.334495);
    lat1 = toOsmLat(48.824045);
    lon2 = toOsmLon(2.335018);
    lat2 = toOsmLat(48.824105);
    totalSegmentLength = CheapRuler.distance(lon1, lat1, lon2, lat2);
    assertEquals(
      "Works if first point is within the circle",
      9,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.1 * 9
    );

    lon1 = toOsmLon(2.33427);
    lat1 = toOsmLat(48.82402);
    lon2 = toOsmLon(2.334587);
    lat2 = toOsmLat(48.824061);
    totalSegmentLength = CheapRuler.distance(lon1, lat1, lon2, lat2);
    assertEquals(
      "Works if both points are within the circle",
      25,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.1 * 25
    );

    // Check distance within radius is correctly computed if both points are on
    // the same side of the center.
    // Note: the only such case possible is with one point outside and one
    // point within the circle, as we expect the segment to have a non-empty
    // intersection with the circle.
    lon1 = toOsmLon(2.332559);
    lat1 = toOsmLat(48.823822);
    lon2 = toOsmLon(2.33431);
    lat2 = toOsmLat(48.824027);
    totalSegmentLength = CheapRuler.distance(lon1, lat1, lon2, lat2);
    assertEquals(
      "Works if both points are on the same side of the circle center",
      5,
      node.distanceWithinRadius(lon1, lat1, lon2, lat2, totalSegmentLength),
      0.1 * 5
    );
  }
}
