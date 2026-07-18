package btools.router.roundtrip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.RoundTripFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mirrored tests for {@link GeometricWaypointPlacer} — the isochrone/envelope
 * placement, direction-merge, frontier-decode, and iso-asymmetry tests moved
 * out of RoutingEngineTest and IsochroneCostContourTest with the extraction.
 */
public class GeometricWaypointPlacerTest {
  private static final int START_ILON = 188720000; // ~8.72E
  private static final int START_ILAT = 140000000; // ~50.0N


  private GeometricWaypointPlacer placer(double searchRadius) {
    return new GeometricWaypointPlacer(
      RoundTripFixture.dummyRoundTripEngine("trekking", searchRadius).roundTripOps());
  }

  @Test
  public void mergeIsochroneWithProbeNoOverlap() {
    // Isochrone has N and S, probe adds E and W
    double[][] frontier = {{0, 500}, {180, 600}};
    double[] probe = {90, 270};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, probe, 1000);

    assertEquals("should have 4 entries", 4, merged.length);
    // Verify isochrone entries keep their distances
    boolean foundNorth = false, foundEast = false;
    for (double[] entry : merged) {
      if (Math.abs(entry[0] - 0) < 1) { foundNorth = true; assertEquals(500, entry[1], 0.1); }
      if (Math.abs(entry[0] - 90) < 1) { foundEast = true; assertEquals(1000, entry[1], 0.1); } // probe uses searchRadius
    }
    assertTrue("should contain north (isochrone)", foundNorth);
    assertTrue("should contain east (probe fill)", foundEast);
  }

  @Test
  public void mergeIsochroneWithProbeOverlap() {
    // Isochrone and probe both have direction 90 — isochrone distance should win
    double[][] frontier = {{90, 750}};
    double[] probe = {90, 180};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, probe, 1000);

    assertEquals("should have 2 entries", 2, merged.length);
    // Direction 90 should keep isochrone distance (750), not probe (1000)
    for (double[] entry : merged) {
      if (Math.abs(entry[0] - 90) < 1) {
        assertEquals("overlapping direction should keep isochrone distance", 750, entry[1], 0.1);
      }
    }
  }

  @Test
  public void mergeIsochroneWithProbeNullIsochrone() {
    // Isochrone failed — merge should use probe directions at searchRadius
    double[] probe = {0, 90, 180, 270};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(null, probe, 2000);

    assertEquals("should have 4 entries from probe", 4, merged.length);
    for (double[] entry : merged) {
      assertEquals("probe-only entries should use searchRadius", 2000, entry[1], 0.1);
    }
  }

  @Test
  public void mergeIsochroneWithProbeNullProbe() {
    // Probe failed — merge should return isochrone data unchanged
    double[][] frontier = {{45, 800}, {135, 600}, {225, 900}};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, null, 1000);

    assertEquals("should have 3 entries from isochrone", 3, merged.length);
    assertEquals(800, merged[0][1], 0.1);
  }

  @Test
  public void mergeIsochroneWithProbeBothNull() {
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(null, null, 1000);
    assertNull("both null should return null", merged);
  }

  @Test
  public void mergeIsochroneWithProbeCloseDirections() {
    // Isochrone has 90°, probe has 93° — should NOT add probe (within 5° threshold)
    double[][] frontier = {{90, 750}};
    double[] probe = {93};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, probe, 1000);

    assertEquals("close direction should not be added", 1, merged.length);
    assertEquals(750, merged[0][1], 0.1);
  }

  @Test
  public void mergeIsochroneWithProbeSorted() {
    // Result should be sorted by direction
    double[][] frontier = {{270, 500}, {90, 600}};
    double[] probe = {0, 180};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, probe, 1000);

    for (int i = 1; i < merged.length; i++) {
      assertTrue("result should be sorted by direction",
        merged[i][0] >= merged[i - 1][0]);
    }
  }

  @Test
  public void mergeIsochroneWithProbePreservesSixElementEntries() {
    // Isochrone-sourced entries are 6-element [dir, dist, cost, hits, ilon, ilat]
    // — the merge must pass these through unchanged so direct-ISOCHRONE
    // placement can find the road-native coord downstream.
    double[][] frontier = {{0, 2000, 2600, 5, 188_720_111, 140_000_222}};
    double[] probe = {180};
    double[][] merged = GeometricWaypointPlacer.mergeIsochroneWithProbe(frontier, probe, 1000);

    assertEquals(2, merged.length);
    for (double[] entry : merged) {
      if (Math.abs(entry[0]) < 1) {
        assertEquals("iso entry preserves 6 elements", 6, entry.length);
        assertEquals(188_720_111, (int) entry[4]);
        assertEquals(140_000_222, (int) entry[5]);
      } else {
        // Probe-only injection — 4 elements, no road-native data.
        assertEquals("probe-only entry stays 4 elements", 4, entry.length);
      }
    }
  }

  /**
   * Fallback path: when no candidate pool is supplied, placement uses the
   * frontier entry's road-native coord (entry[4]/entry[5]) rather than
   * synthesizing a position. Production passes the full candidate pool to get
   * airDist-aware selection — see
   * {@link #placeWaypointsFromIsochronePicksCandidateMatchingPlacementRadius}.
   */
  @Test
  public void placeWaypointsFromIsochroneUsesRoadNativeCoordsWhenAvailable() {
    GeometricWaypointPlacer placer = placer(0);

    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = START_ILON;
    start.ilat = START_ILAT;
    wps.add(start);

    // Frontier with 6-element entries, each at a distinct road-native coord
    // far enough from start (airDist > 0.4 * searchRadius) and with hits >= 3
    // so they pass the in-method usability filter.
    double searchRadius = 2000;
    int[][] expectedPositions = {
      {START_ILON + 30_000, START_ILAT},
      {START_ILON, START_ILAT + 25_000},
      {START_ILON - 28_000, START_ILAT + 2_000},
      {START_ILON - 5_000, START_ILAT - 27_000},
    };
    double[][] frontier = {
      {  0, 1500, 1950, 5, expectedPositions[0][0], expectedPositions[0][1]},
      { 90, 1600, 2080, 5, expectedPositions[1][0], expectedPositions[1][1]},
      {180, 1550, 2015, 5, expectedPositions[2][0], expectedPositions[2][1]},
      {270, 1500, 1950, 5, expectedPositions[3][0], expectedPositions[3][1]},
    };

    placer.placeWaypointsFromIsochrone(wps, frontier, null, searchRadius, 0, 5);

    // 1 start + 4 intermediates + 1 closing == 6
    assertEquals("expected start + 4 rt + closing", 6, wps.size());
    assertEquals(START_ILON, wps.get(0).ilon);
    assertEquals(START_ILAT, wps.get(0).ilat);
    assertEquals("closing waypoint must be a copy of start",
      START_ILON, wps.get(wps.size() - 1).ilon);
    assertEquals(START_ILAT, wps.get(wps.size() - 1).ilat);

    // Each intermediate waypoint must land on one of the road-native coords —
    // not at a CheapRuler.destination-synthesized position.
    java.util.Set<Long> expectedKeys = new java.util.HashSet<>();
    for (int[] pos : expectedPositions) {
      expectedKeys.add(new OsmNode(pos[0], pos[1]).getIdFromPos());
    }
    for (int i = 1; i < wps.size() - 1; i++) {
      OsmNodeNamed wp = wps.get(i);
      assertTrue("waypoint " + wp.name + " (" + wp.ilon + "," + wp.ilat
        + ") should match a road-native frontier coord",
        expectedKeys.contains(wp.getIdFromPos()));
    }
  }

  /**
   * Mixed frontier: 6-element entries reuse their road-native coord while
   * probe-only 4-element entries fall back to {@code CheapRuler.destination}
   * at the indirectness-compensated air-distance. Note that probe-only
   * (hits=0) entries enter the placement only via the relaxed-fallback branch
   * in placeWaypointsFromIsochrone (usable.size() < 4).
   */
  @Test
  public void placeWaypointsFromIsochroneFallsBackToSyntheticForProbeOnly() {
    GeometricWaypointPlacer placer = placer(0);

    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = START_ILON;
    start.ilat = START_ILAT;
    wps.add(start);

    double searchRadius = 2000;
    // Two iso entries (6-element) at 0° and 180°, two probe-only at 90° and 270°.
    int[] isoNorth = {START_ILON, START_ILAT + 24_000};
    int[] isoSouth = {START_ILON, START_ILAT - 24_000};
    double[][] frontier = {
      {  0, 1500, 1950, 5, isoNorth[0], isoNorth[1]},
      { 90, 2000, 2600, 0},  // probe-only, no coord
      {180, 1500, 1950, 5, isoSouth[0], isoSouth[1]},
      {270, 2000, 2600, 0},  // probe-only, no coord
    };

    placer.placeWaypointsFromIsochrone(wps, frontier, null, searchRadius, 0, 5);

    assertEquals(6, wps.size());

    long northKey = new OsmNode(isoNorth[0], isoNorth[1]).getIdFromPos();
    long southKey = new OsmNode(isoSouth[0], isoSouth[1]).getIdFromPos();
    long startKey = new OsmNode(START_ILON, START_ILAT).getIdFromPos();
    boolean sawNorth = false, sawSouth = false;
    int syntheticCount = 0;
    for (int i = 1; i < wps.size() - 1; i++) {
      OsmNodeNamed wp = wps.get(i);
      long key = wp.getIdFromPos();
      if (key == northKey) sawNorth = true;
      else if (key == southKey) sawSouth = true;
      else {
        assertNotEquals("synthetic waypoint must not coincide with start",
          startKey, key);
        syntheticCount++;
      }
    }
    assertTrue("road-native north entry should appear", sawNorth);
    assertTrue("road-native south entry should appear", sawSouth);
    assertEquals("two probe-only directions placed synthetically", 2, syntheticCount);
  }

  /**
   * When a candidate pool is passed, placement must pick the candidate whose
   * air-distance best matches the indirectness-compensated target — not the
   * frontier-max coord (which sits at the cost-budget envelope and would
   * overshoot the requested loop size for small radii).
   */
  @Test
  public void placeWaypointsFromIsochronePicksCandidateMatchingPlacementRadius() {
    GeometricWaypointPlacer placer = placer(0);
    // This test pins the base airDist->candidate selection; neutralise the
    // directional bulge so the per-direction target radius stays at
    // searchRadius regardless of startDirection (the bulge is covered by
    // placeWaypointsFromIsochroneBulgesTowardStartDirection).
    GeometricWaypointPlacer.isochroneDirBulgeAlpha = 0;
    try {
      List<OsmNodeNamed> wps = new ArrayList<>();
      OsmNodeNamed start = new OsmNodeNamed();
      start.name = "from";
      start.ilon = START_ILON;
      start.ilat = START_ILAT;
      wps.add(start);

      // 4 buckets at 0/90/180/270. For each bucket, give the candidate pool a
      // frontier-max far out (at 3000m) plus a 25%-contour candidate at ~1000m.
      // With searchRadius=2000 the placement target is roughly searchRadius, so
      // the 25%-contour candidate (near 1000m) should be picked over the
      // frontier-max (at 3000m).
      double searchRadius = 2000;
      int[][] frontierCoords = {
        {START_ILON + 60_000, START_ILAT},
        {START_ILON, START_ILAT + 50_000},
        {START_ILON - 60_000, START_ILAT},
        {START_ILON, START_ILAT - 50_000},
      };
      int[][] contourCoords = {
        {START_ILON + 20_000, START_ILAT},
        {START_ILON, START_ILAT + 16_000},
        {START_ILON - 20_000, START_ILAT},
        {START_ILON, START_ILAT - 16_000},
      };
      double[][] frontier = new double[4][];
      List<IsoCandidate> candidates = new ArrayList<>();
      int[] buckets = {0, 9, 18, 27};
      double[] bearings = {0, 90, 180, 270};
      for (int i = 0; i < 4; i++) {
        frontier[i] = new double[]{bearings[i], 3000, 3900, 5, frontierCoords[i][0], frontierCoords[i][1]};
        candidates.add(new IsoCandidate(frontierCoords[i][0], frontierCoords[i][1],
          bearings[i], 3000, 3900, buckets[i], 5, 100));
        candidates.add(new IsoCandidate(contourCoords[i][0], contourCoords[i][1],
          bearings[i], 1000, 1300, buckets[i], 5, 25));
      }

      placer.placeWaypointsFromIsochrone(wps, frontier, candidates, searchRadius, 0, 5);

      assertEquals(6, wps.size());

      // Every intermediate waypoint should land on a contour-coord, not on the
      // far-out frontier-max coord.
      java.util.Set<Long> contourKeys = new java.util.HashSet<>();
      java.util.Set<Long> frontierKeys = new java.util.HashSet<>();
      for (int i = 0; i < 4; i++) {
        contourKeys.add(new OsmNode(contourCoords[i][0], contourCoords[i][1]).getIdFromPos());
        frontierKeys.add(new OsmNode(frontierCoords[i][0], frontierCoords[i][1]).getIdFromPos());
      }
      int contourHits = 0, frontierHits = 0;
      for (int i = 1; i < wps.size() - 1; i++) {
        long key = wps.get(i).getIdFromPos();
        if (contourKeys.contains(key)) contourHits++;
        else if (frontierKeys.contains(key)) frontierHits++;
      }
      assertEquals("airDist-aware selection should prefer the 1000m contour over the 3000m frontier",
        4, contourHits);
      assertEquals(0, frontierHits);
    } finally {
      GeometricWaypointPlacer.isochroneDirBulgeAlpha = 0.35;
    }
  }

  @Test
  public void placeWaypointsFromIsochroneBulgesTowardStartDirection() {
    // Same synthetic frontier as the test above (per bucket: a far frontier-max
    // at 3000m and a near 25%-contour at 1000m), but with the directional bulge
    // ON and startDirection = 0. The bulge must push the placement radius OUT in
    // the heading direction (so the aligned bucket picks the far 3000m candidate)
    // and pull it IN on the opposite side (so the anti-heading bucket picks the
    // near 1000m candidate) — i.e. the loop bulges toward the requested heading.
    GeometricWaypointPlacer placer = placer(0);
    GeometricWaypointPlacer.isochroneDirBulgeAlpha = 0.5;
    try {
      List<OsmNodeNamed> wps = new ArrayList<>();
      OsmNodeNamed start = new OsmNodeNamed();
      start.name = "from";
      start.ilon = START_ILON;
      start.ilat = START_ILAT;
      wps.add(start);

      double searchRadius = 2000;
      // bearing 0 = +ilat (the heading), bearing 180 = -ilat (opposite).
      int[][] frontierCoords = {
        {START_ILON, START_ILAT + 60_000},
        {START_ILON + 50_000, START_ILAT},
        {START_ILON, START_ILAT - 60_000},
        {START_ILON - 50_000, START_ILAT},
      };
      int[][] contourCoords = {
        {START_ILON, START_ILAT + 20_000},
        {START_ILON + 16_000, START_ILAT},
        {START_ILON, START_ILAT - 20_000},
        {START_ILON - 16_000, START_ILAT},
      };
      double[][] frontier = new double[4][];
      List<IsoCandidate> candidates = new ArrayList<>();
      int[] buckets = {0, 9, 18, 27};
      double[] bearings = {0, 90, 180, 270};
      for (int i = 0; i < 4; i++) {
        frontier[i] = new double[]{bearings[i], 3000, 3900, 5, frontierCoords[i][0], frontierCoords[i][1]};
        candidates.add(new IsoCandidate(frontierCoords[i][0], frontierCoords[i][1],
          bearings[i], 3000, 3900, buckets[i], 5, 100));
        candidates.add(new IsoCandidate(contourCoords[i][0], contourCoords[i][1],
          bearings[i], 1000, 1300, buckets[i], 5, 25));
      }

      placer.placeWaypointsFromIsochrone(wps, frontier, candidates, searchRadius, 0, 5);

      // North (heading) waypoint should be the far frontier-max; south (opposite)
      // should be the near contour. Measure each by air-distance from start.
      double northDist = -1, southDist = -1;
      for (int i = 1; i < wps.size() - 1; i++) {
        OsmNodeNamed w = wps.get(i);
        double dLat = (w.ilat - START_ILAT) / 1e6 * 111320.0;
        double dLon = (w.ilon - START_ILON) / 1e6 * 111320.0;
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);
        if (Math.abs(dLon) < Math.abs(dLat)) { // a north/south waypoint
          if (dLat > 0) northDist = dist;
          else southDist = dist;
        }
      }
      assertTrue("a north (heading) and a south waypoint should both be placed, got north="
        + northDist + " south=" + southDist, northDist > 0 && southDist > 0);
      assertTrue("bulge must place the heading-direction waypoint farther out than the opposite "
        + "(north=" + northDist + "m vs south=" + southDist + "m)", northDist > southDist + 500);
    } finally {
      GeometricWaypointPlacer.isochroneDirBulgeAlpha = 0.35;
    }
  }

  @Test
  public void isoAsymmetry_symmetricFrontier_picksLowestBucketIndex() {
    // All buckets identical → tie-break by lowest bucket index → bucket 0 = 0°.
    double[][] f = uniformFrontier(8000.0, 10000.0, 5);
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertTrue("bias should fire when all buckets pass thresholds", bias.applied);
    assertEquals("tie → bucket 0 (bearing 0°)", 0.0, bias.bearingDegrees, 0.01);
  }

  @Test
  public void isoAsymmetry_asymmetricFrontier_picksLowestIndirectness() {
    // 5 buckets reach far at low cost (best reach); 31 reach moderately.
    double[] a = new double[36];
    double[] c = new double[36];
    int[] h = new int[36];
    for (int i = 0; i < 36; i++) {
      a[i] = 7000.0;
      c[i] = 12000.0; // indirectness ≈ 1.71
      h[i] = 5;
    }
    // The east sector (buckets 8-12, bearings 80°-120°) is the "valley":
    // long reach for less cost.
    for (int i = 8; i <= 12; i++) {
      a[i] = 9500.0;
      c[i] = 11000.0; // indirectness ≈ 1.16
      h[i] = 8;
    }
    double[][] f = frontier36(a, c, h);
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertTrue("bias should fire", bias.applied);
    assertTrue("bearing should be in the east sector (80°-120°)",
      bias.bearingDegrees >= 80.0 && bias.bearingDegrees <= 120.0);
    assertEquals("hits from the winning bucket", 8, bias.hits);
  }

  @Test
  public void isoAsymmetry_sparseBuckets_noBiasApplied() {
    // All buckets reach far enough but hit count is below the minHits=3 floor.
    double[][] f = uniformFrontier(8000.0, 10000.0, 1);
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertFalse("hits < 3 disqualifies all buckets", bias.applied);
  }

  @Test
  public void isoAsymmetry_reachFloorNotMet_noBiasApplied() {
    // hits OK but airDist below 0.6 * searchRadius (= 6000m).
    double[][] f = uniformFrontier(4000.0, 10000.0, 5);
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertFalse("airDist < 0.6 * searchRadius disqualifies all buckets", bias.applied);
  }

  @Test
  public void isoAsymmetry_emptyFrontier_noBiasApplied() {
    assertFalse(GeometricWaypointPlacer.computeIsoAsymmetryBearing(new double[0][], 10000.0).applied);
    assertFalse(GeometricWaypointPlacer.computeIsoAsymmetryBearing(null, 10000.0).applied);
  }

  @Test
  public void isoAsymmetry_probeOnlyEntriesIgnored() {
    // Mix of 6-element road-native entries and 4-element probe-only entries
    // (from IsochroneExpansionResult docs). All should be considered uniformly
    // for the bias since indices 0-3 are populated on both forms.
    double[][] f = new double[36][];
    for (int i = 0; i < 36; i++) {
      if (i % 2 == 0) {
        f[i] = new double[]{i * 10.0, 8000.0, 10000.0, 5, 0, 0}; // 6-element
      } else {
        f[i] = new double[]{i * 10.0, 8000.0, 10000.0, 5};       // 4-element
      }
    }
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertTrue("4-element probe entries must still be considered", bias.applied);
  }

  @Test
  public void isoAsymmetry_resultCarriesAllTelemetry() {
    double[][] f = uniformFrontier(8000.0, 10000.0, 5);
    IsoAsymmetryBias bias = GeometricWaypointPlacer.computeIsoAsymmetryBearing(f, 10000.0);
    assertTrue(bias.applied);
    assertEquals(0.0, bias.bearingDegrees, 0.01);
    assertEquals(10000.0 / 8000.0, bias.indirectness, 0.001);
    assertEquals(5, bias.hits);
    assertEquals(8000, bias.airDistMeters);
  }

  /** Build a synthetic 36-bucket frontier table. Bucket i is at bearing
   *  i*10°. Each bucket = [direction_deg, airDist_m, cost, hits, ilon, ilat].
   *  ilon/ilat are zeroed (not used by the bias computation). */
  private static double[][] frontier36(double[] airDist, double[] cost, int[] hits) {
    double[][] f = new double[36][];
    for (int i = 0; i < 36; i++) {
      f[i] = new double[]{i * 10.0, airDist[i], cost[i], hits[i], 0, 0};
    }
    return f;
  }

  /** Uniform-reach helper: every bucket has the same airDist/cost/hits. */
  private static double[][] uniformFrontier(double airDist, double cost, int hits) {
    double[] a = new double[36];
    double[] c = new double[36];
    int[] h = new int[36];
    for (int i = 0; i < 36; i++) {
      a[i] = airDist;
      c[i] = cost;
      h[i] = hits;
    }
    return frontier36(a, c, h);
  }

  @Test
  public void roadNativeCoordReturnsCoordsForIsoEntry() {
    // 6-element entry has road-native coords at [4], [5].
    double[] entry = {45.0, 2000.0, 2600.0, 5.0, 188_720_123.0, 140_000_456.0};
    int[] coord = GeometricWaypointPlacer.frontierRoadNativeCoord(entry);
    assertNotNull(coord);
    assertEquals(188_720_123, coord[0]);
    assertEquals(140_000_456, coord[1]);
  }

  @Test
  public void roadNativeCoordReturnsNullForProbeOnlyEntry() {
    // 4-element entry (probe-only injected by mergeIsochroneWithProbe).
    double[] entry = {45.0, 2000.0, 2600.0, 0.0};
    assertNull(GeometricWaypointPlacer.frontierRoadNativeCoord(entry));
  }

  @Test
  public void roadNativeCoordReturnsNullForShortEntry() {
    double[] entry = {45.0, 2000.0};
    assertNull(GeometricWaypointPlacer.frontierRoadNativeCoord(entry));
  }

  @Test
  public void roadNativeCoordReturnsNullForNull() {
    assertNull(GeometricWaypointPlacer.frontierRoadNativeCoord(null));
  }

  @Test
  public void nearestCandidatePicksClosestAirDist() {
    // Four candidates per bucket (frontier-max + 25/50/75 contours) at
    // increasing air-distances. The target sits between two of them; the closer
    // one wins.
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0,  500, 25),
      cand(0, 1000, 50),
      cand(0, 1500, 75),
      cand(0, 2000, 100));
    IsoCandidate best = GeometricWaypointPlacer.nearestCandidateByAirDist(bucket, 1100);
    assertNotNull(best);
    assertEquals(50, best.sourceContour); // 1000 is closer to 1100 than 1500
  }

  @Test
  public void nearestCandidateSelectsExactMatch() {
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0, 1000, 50),
      cand(0, 2000, 100));
    IsoCandidate best = GeometricWaypointPlacer.nearestCandidateByAirDist(bucket, 2000);
    assertNotNull(best);
    assertEquals(100, best.sourceContour);
  }

  @Test
  public void nearestCandidateHandlesEmptyAndNull() {
    assertNull(GeometricWaypointPlacer.nearestCandidateByAirDist(null, 1000));
    assertNull(GeometricWaypointPlacer.nearestCandidateByAirDist(new ArrayList<>(), 1000));
  }

  @Test
  public void nearestCandidatePicksFrontierMaxWhenTargetIsLarge() {
    // Target larger than any candidate — pick the farthest (frontier-max).
    List<IsoCandidate> bucket = Arrays.asList(
      cand(0,  500, 25),
      cand(0, 1500, 75),
      cand(0, 2000, 100));
    IsoCandidate best = GeometricWaypointPlacer.nearestCandidateByAirDist(bucket, 5000);
    assertNotNull(best);
    assertEquals(100, best.sourceContour);
  }

  /**
   * Build a candidate with the given bucket/airDist; other fields are
   * irrelevant for {@link RoutingEngine#nearestCandidateByAirDist}.
   */
  private static IsoCandidate cand(int bucket, double airDist, int sourceContour) {
    return new IsoCandidate(0, 0, bucket * 10 + 5, airDist,
      (int) (airDist * 1.3), bucket, 5, sourceContour);
  }

  @Test
  public void isoAsymmetryNone_carriesSentinels() {
    IsoAsymmetryBias none = IsoAsymmetryBias.NONE;
    assertFalse(none.applied);
    assertTrue(Double.isNaN(none.bearingDegrees));
    assertTrue(Double.isNaN(none.indirectness));
    assertEquals(-1, none.hits);
    assertEquals(-1, none.airDistMeters);
  }

  @Test
  public void frontierAxis_symmetricFrontier_noStrongAxis() {
    // Uniform reach → eigenvalues nearly equal → no strong axis.
    double[][] f = uniformFrontier(8000.0, 10000.0, 5);
    FrontierAxis axis = GeometricWaypointPlacer.computeFrontierAxis(f, 10000.0);
    assertFalse("uniform reach should not register as strong axis", axis.hasStrongAxis);
    assertTrue("strength should be near 1.0", axis.strength < 1.5);
  }

  @Test
  public void frontierAxis_elongatedEastWest_detectsHorizontalAxis() {
    // Inn Valley analog: only buckets near E (90°) or W (270°) reach far
    // enough to pass the airDist quality threshold — mountains block the
    // perpendicular sectors entirely. PCA operates only on the surviving
    // axis-aligned buckets, producing a strongly anisotropic covariance.
    double[] a = new double[36];
    double[] c = new double[36];
    int[] h = new int[36];
    for (int i = 0; i < 36; i++) {
      double bearing = i * 10.0;
      double angleFromAxis = Math.min(GeometricWaypointPlacer.angularDiff(bearing, 90), GeometricWaypointPlacer.angularDiff(bearing, 270));
      // searchRadius=10000 → minAirDist threshold = 6000m.
      a[i] = angleFromAxis < 30 ? 8000.0 : 2000.0; // off-axis below threshold
      c[i] = 10000.0;
      h[i] = 5;
    }
    double[][] f = frontier36(a, c, h);
    FrontierAxis axis = GeometricWaypointPlacer.computeFrontierAxis(f, 10000.0);
    assertTrue("east-west elongation should register as strong axis", axis.hasStrongAxis);
    // Canonical [0, 180) → axis bearing should be ~90° (E-W).
    assertEquals("axis bearing ~90°", 90.0, axis.axisBearingDegrees, 10.0);
    assertTrue("strength should be substantial", axis.strength >= 3.0);
  }

  @Test
  public void frontierAxis_tooFewGoodBuckets_returnsNone() {
    // Only 3 buckets pass the quality thresholds; PCA requires ≥4.
    double[] a = new double[36];
    double[] c = new double[36];
    int[] h = new int[36];
    for (int i = 0; i < 36; i++) {
      a[i] = 2000.0; // below reach floor for searchRadius=10000
      c[i] = 5000.0;
      h[i] = 5;
    }
    for (int i = 0; i < 3; i++) {
      a[i] = 8000.0; // these 3 pass the floor
    }
    double[][] f = frontier36(a, c, h);
    FrontierAxis axis = GeometricWaypointPlacer.computeFrontierAxis(f, 10000.0);
    assertFalse(axis.hasStrongAxis);
  }

  @Test
  public void isPerpendicularToAxis_northVsEastWest() {
    // User asks N (0°), axis is E-W (90°) → perpendicular.
    assertTrue(GeometricWaypointPlacer.isPerpendicularToAxis(0, 90));
    assertTrue(GeometricWaypointPlacer.isPerpendicularToAxis(180, 90));
    // User asks E (90°), axis is E-W (90°) → colinear.
    assertFalse(GeometricWaypointPlacer.isPerpendicularToAxis(90, 90));
    assertFalse(GeometricWaypointPlacer.isPerpendicularToAxis(270, 90));
    // User asks NE (45°), axis E-W → 45° off perpendicular → false at 30° tol.
    assertFalse(GeometricWaypointPlacer.isPerpendicularToAxis(45, 90));
  }

  @Test
  public void chooseAxisBearing_picksHalfPlaneClosestToUser() {
    // Axis E-W (90°), user asks NE (45°) → closer to E (90°) than W (270°).
    assertEquals(90.0, GeometricWaypointPlacer.chooseAxisBearing(90, 45), 0.01);
    // Axis E-W, user asks NW (315°) → closer to W (270°) than E (90°).
    assertEquals(270.0, GeometricWaypointPlacer.chooseAxisBearing(90, 315), 0.01);
    // Axis E-W, user asks N (0°) → equidistant; tie-break prefers lower → 90°.
    assertEquals(90.0, GeometricWaypointPlacer.chooseAxisBearing(90, 0), 0.01);
  }

}
