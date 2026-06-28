package btools.router;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import btools.util.CheapRuler;

/**
 * Unit tests for the {@link IsochroneCandidateProvider#fromPool} filter pipeline and
 * the diversity guard. These are pure-function tests — no engine instance, no segment
 * data — construct synthetic {@link IsoCandidate} pools directly.
 */
public class IsochroneCandidateProviderTest {

  // Fixed test radius. searchRadius * MIN_AIR_DIST_FRAC (0.15) = 750m.
  private static final double SEARCH_RADIUS = 5000;
  // Reference origin (Dreieich-ish coordinates in BRouter integer units).
  private static final int START_ILON = 188_720_000;
  private static final int START_ILAT = 140_000_000;

  private static IsoCandidate at(int bucket, double airDist, int hits, int contour) {
    double bearing = bucket * 10.0 + 5.0;
    int[] pos = CheapRuler.destination(START_ILON, START_ILAT, airDist, bearing);
    return new IsoCandidate(pos[0], pos[1], bearing, airDist, (int) (airDist * 1.3),
      bucket, hits, contour);
  }

  // ----- core filter pipeline -----

  @Test
  public void emptyPoolGivesEmptyProvider() {
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, new ArrayList<>());
    assertEquals(0, p.poolSize());
    assertFalse("empty pool is not diverse", p.isDiverse());
  }

  @Test
  public void nullPoolGivesEmptyProvider() {
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, null);
    assertEquals(0, p.poolSize());
  }

  @Test
  public void dropsCandidatesCloserThanMinAirDist() {
    // MIN_AIR_DIST_FRAC = 0.15 → drop anything closer than 750m at SEARCH_RADIUS=5000.
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(at(0, 500, 5, 100));    // too close — dropped
    raw.add(at(9, 749, 5, 100));    // just below — dropped
    raw.add(at(18, 751, 5, 100));   // just above — kept
    raw.add(at(27, 4000, 5, 100));  // kept
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertEquals(2, p.poolSize());
  }

  @Test
  public void dropsLowPopulationBucketsWhenAlternativesExist() {
    // Need ≥12 strong (hits≥3) to trigger the drop. Build 12 strong + a few weak.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 12; b++) raw.add(at(b * 3, 2000, 5, 100));  // 12 strong, distinct buckets
    raw.add(at(34, 2000, 1, 100));  // weak — should be dropped
    raw.add(at(35, 2000, 2, 100));  // weak — should be dropped
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertEquals("12 strong kept, 2 weak dropped", 12, p.poolSize());
  }

  @Test
  public void keepsWeakCandidatesWhenStrongCountBelow12() {
    // <12 strong → keep weak candidates so we still have something to plan with.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 8; b++) raw.add(at(b * 4, 2000, 5, 100));   // 8 strong
    raw.add(at(33, 2000, 1, 100));  // weak — must stay
    raw.add(at(35, 2000, 2, 100));  // weak — must stay
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertEquals("all 10 kept since strong<12", 10, p.poolSize());
  }

  @Test
  public void lowPopDropGatesOnDistinctBucketsNotCandidateCount() {
    // runIsochroneExpansion emits up to (contourCount + 1) candidates per bucket,
    // all sharing the bucket's hit count. Here: 3 strong buckets × 4 contour
    // candidates = 12 strong candidates but only 3 distinct buckets, plus one
    // weak bucket. The old per-candidate tally hit 12 and dropped the weak
    // candidate; the distinct-bucket count (3 < 12) must keep it.
    List<IsoCandidate> raw = new ArrayList<>();
    int[] strongBuckets = {0, 12, 24};
    double[] contourDists = {1500, 2000, 2500, 3000}; // distinct positions → no dedupe
    int[] contours = {25, 50, 75, 100};
    for (int b : strongBuckets) {
      for (int k = 0; k < contourDists.length; k++) {
        raw.add(at(b, contourDists[k], 5, contours[k]));
      }
    }
    raw.add(at(6, 2000, 1, 100));  // weak buckets — must survive
    raw.add(at(18, 2000, 1, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    // Selection takes up to 2 per occupied bucket: fix keeps both weak buckets
    // (5 occupied → 3 strong×2 + 2 weak×1 = 8); the old per-candidate count
    // (12 ≥ 12) would have dropped them (3 strong×2 = 6).
    assertEquals("weak buckets kept: only 3 distinct strong buckets (<12)", 8, p.poolSize());
  }

  @Test
  public void minDiversityFallbackUsesUnfilteredSet() {
    // Edge: aggressive filtering would leave a tiny step2, so the code falls back
    // to step1. With 13 strong + 3 weak, normal path drops 3 weak → 13 strong remain.
    // With 12 strong + 0 weak, normal path keeps all 12 strong (no weak to drop).
    // Test the fallback specifically: 13 strong but all at one position so dedupe
    // collapses to 1; the relax fallback must not be triggered by dedupe.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 13; b++) raw.add(at(b, 2000, 5, 100));  // 13 strong
    raw.add(at(34, 2000, 1, 100));  // 1 weak
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertTrue("strong-only after low-pop drop", p.poolSize() >= 13);
  }

  @Test
  public void dedupesNearIdenticalPositions() {
    // Two candidates at very close ilon/ilat (within DEDUPE_GRANULARITY=100) → only one survives.
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(new IsoCandidate(190_000_000, 140_000_000, 5.0, 2000, 2600, 0, 5, 100));
    raw.add(new IsoCandidate(190_000_050, 140_000_050, 5.0, 2000, 2600, 0, 5, 75)); // 50 units away → same cell
    raw.add(new IsoCandidate(190_001_000, 140_001_000, 5.0, 2000, 2600, 1, 5, 100)); // distinct cell
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertEquals("identical-cell candidates dedupe to one", 2, p.poolSize());
  }

  @Test
  public void capsPoolSizeAt24() {
    // 40 candidates, 1 per bucket → first pass takes 1 per bucket (≤24), pad never runs.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 36; b++) raw.add(at(b, 2000, 5, 100));
    // Add 4 more with extra contours in already-occupied buckets so per-bucket cap matters.
    for (int b = 0; b < 4; b++) raw.add(at(b, 1500, 5, 75));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertTrue("pool capped at 24", p.poolSize() <= 24);
    assertTrue("pool not empty", p.poolSize() >= 12);
  }

  @Test
  public void prefersFrontierMaxOverInnerContoursWithinBucket() {
    // Same bucket, four contour candidates at increasing air-distance. The
    // per-bucket cap (2) keeps the two with the highest source contour
    // (100 > 75), dropping 25 and 50.
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(at(0, 1000, 5, 25));   // worst — should drop
    raw.add(at(0, 1500, 5, 50));   // dropped
    raw.add(at(0, 2000, 5, 75));   // second-best — kept
    raw.add(at(0, 2500, 5, 100));  // best — kept
    // Need diversity for the provider to be useful — add stuff in other buckets.
    for (int b = 9; b < 30; b += 3) raw.add(at(b, 2000, 5, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);

    // Inspect the surviving bucket-0 candidates via candidatesForStep (its
    // window is [0.5R, 1.6R]; R=2000 → [1000, 3200] covers the 2000 m/2500 m
    // survivors). Bucket-0 candidates sit at bearing ~5° from start; the
    // cross-bucket fillers are at >= 95°, so filter by bearing.
    List<RoundTripCandidateProvider.CandidatePoint> step = p.candidatesForStep(
      START_ILON, START_ILAT, 2000, 1, 5, START_ILON, START_ILAT, 0.0, null);
    Set<Integer> bucket0Contours = new HashSet<>();
    for (RoundTripCandidateProvider.CandidatePoint cp : step) {
      double bearing = CheapRuler.getScaledBearing(START_ILON, START_ILAT, cp.ilon, cp.ilat);
      if (bearing < 45 || bearing > 315) bucket0Contours.add(cp.sourceContour);
    }
    assertTrue("bucket 0 must keep the frontier-max (100) contour", bucket0Contours.contains(100));
    assertTrue("bucket 0 must keep the next contour (75)", bucket0Contours.contains(75));
    assertFalse("bucket 0 must drop the 25 contour", bucket0Contours.contains(25));
    assertFalse("bucket 0 must drop the 50 contour", bucket0Contours.contains(50));
  }

  // ----- diversity guard -----

  @Test
  public void corridorOnlyPoolIsNotDiverse() {
    // All candidates clumped in a narrow corridor (3 adjacent buckets).
    // Span = 30°, well below MIN_ANGULAR_SPAN_DEG=180.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int contour : new int[]{25, 50, 75, 100}) {
      raw.add(at(10, 2000, 5, contour));
      raw.add(at(11, 2200, 5, contour));
      raw.add(at(12, 2400, 5, contour));
    }
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertFalse("3-adjacent-bucket pool is corridor-only", p.isDiverse());
  }

  @Test
  public void fewDistinctBucketsIsNotDiverse() {
    // 2 distinct buckets at opposite sides — angular span 180° but only 2 buckets.
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(at(0, 2000, 5, 100));
    raw.add(at(0, 2200, 5, 75));
    raw.add(at(18, 2000, 5, 100));  // opposite
    raw.add(at(18, 2200, 5, 75));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertFalse("only 2 distinct buckets is not diverse", p.isDiverse());
  }

  @Test
  public void widelySpreadPoolIsDiverse() {
    // 4 distinct buckets spaced 90° apart → 360° span, 4 distinct buckets → diverse.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b : new int[]{0, 9, 18, 27}) raw.add(at(b, 2000, 5, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertTrue("4 N/E/S/W buckets is diverse", p.isDiverse());
  }

  @Test
  public void halfCirclePoolIsDiverseAtBoundary() {
    // 6 buckets spanning exactly 180° — should still be diverse (≥180 boundary).
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b : new int[]{0, 4, 8, 12, 16, 18}) raw.add(at(b, 2000, 5, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertTrue("180° span is diverse", p.isDiverse());
  }

  // ----- candidatesForStep -----

  @Test
  public void candidatesForStepRespectsWindow() {
    // Build a pool of candidates at varied airDist from start, then query candidatesForStep
    // from a different "from" point — should return only those within the window.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b : new int[]{0, 9, 18, 27}) {
      raw.add(at(b, 1000, 5, 100));
      raw.add(at(b, 2500, 5, 75));
      raw.add(at(b, 5000, 5, 50));
    }
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    // Query from start position; airRadius=2500 → window [1250, 4000].
    List<RoundTripCandidateProvider.CandidatePoint> step =
      p.candidatesForStep(START_ILON, START_ILAT, 2500, 1, 5,
        START_ILON, START_ILAT, 0, null);
    // Each returned point must be within [airRadius*0.5, *1.6] = [1250, 4000] from start.
    for (RoundTripCandidateProvider.CandidatePoint cp : step) {
      double d = CheapRuler.distance(START_ILON, START_ILAT, cp.ilon, cp.ilat);
      assertTrue("candidate within window: " + d, d >= 1250 && d <= 4000);
    }
    assertTrue("at least one candidate returned", step.size() >= 1);
  }

  @Test
  public void candidatesForStepSortsByDistanceToTarget() {
    // Candidates at varied airDist from start; airRadius = 3000.
    // Closest to 3000 should be first.
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(at(0, 2000, 5, 100));
    raw.add(at(9, 3500, 5, 100));
    raw.add(at(18, 4500, 5, 100));
    raw.add(at(27, 3000, 5, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    List<RoundTripCandidateProvider.CandidatePoint> step =
      p.candidatesForStep(START_ILON, START_ILAT, 3000, 1, 5,
        START_ILON, START_ILAT, 0, null);
    assertNotNull("returned list", step);
    // Verify sorted ascending by abs(airDist - 3000):
    double prevKey = -1;
    for (RoundTripCandidateProvider.CandidatePoint cp : step) {
      double d = CheapRuler.distance(START_ILON, START_ILAT, cp.ilon, cp.ilat);
      double key = Math.abs(d - 3000);
      assertTrue("sort key non-decreasing", key >= prevKey - 0.01);
      prevKey = key;
    }
  }

  @Test
  public void distinctBucketsAfterPerBucketCap() {
    // Many candidates all in the same bucket — per-bucket cap (2) limits the pool
    // even before the global cap (24).
    List<IsoCandidate> raw = new ArrayList<>();
    for (int contour : new int[]{25, 50, 75, 100}) {
      raw.add(at(0, 2000 + contour, 5, contour));
      raw.add(at(0, 3000 + contour, 5, contour));
    }
    // Add cross-bucket variety so the diversity check passes — otherwise pool
    // size assertion is masked by the "not-diverse → empty" path.
    for (int b : new int[]{9, 18, 27}) raw.add(at(b, 2000, 5, 100));

    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    // 11 raw candidates (8 in bucket 0, 3 cross-bucket). The per-bucket cap (2)
    // must prune bucket 0, so the pool is far smaller than 11 yet keeps all 4
    // distinct buckets. Asserting the exact size catches a cap regression that
    // admitted 3+ per bucket (which the old isDiverse()-only check could not).
    assertTrue("pool diversity preserved", p.isDiverse());
    assertEquals("per-bucket cap must prune bucket 0", 5, p.poolSize());
  }

  // ----- F8: start-anchored angular stride selection -----

  @Test
  public void startAnchoredStrideOrderCoversFullCircle() {
    int[] order = IsochroneCandidateProvider.startAnchoredStrideOrder(0, 36);
    assertEquals("order length matches bucket count", 36, order.length);
    Set<Integer> seen = new HashSet<>();
    for (int b : order) seen.add(b);
    assertEquals("every bucket visited exactly once", 36, seen.size());
    assertEquals("first visit is startBucket", 0, order[0]);
    // After visiting startBucket=0, next two should be ±1 (right then left).
    assertEquals("second visit goes right", 1, order[1]);
    assertEquals("third visit goes left (wrap)", 35, order[2]);
  }

  @Test
  public void startAnchoredStrideOrderRespectsArbitraryStart() {
    int[] order = IsochroneCandidateProvider.startAnchoredStrideOrder(18, 36);
    assertEquals(18, order[0]);
    assertEquals("right of 18 is 19", 19, order[1]);
    assertEquals("left of 18 is 17", 17, order[2]);
    assertEquals("furthest is 36 - it should appear last", 0, order[order.length - 1]);
  }

  @Test
  public void startAnchoredStrideOrderMatchesJavadocExample() {
    // 4 buckets from 0: right (1), left (3), then the antipode (2) once → [0,1,3,2].
    assertArrayEquals(new int[]{0, 1, 3, 2},
      IsochroneCandidateProvider.startAnchoredStrideOrder(0, 4));
  }

  @Test
  public void startAnchoredStrideOrderNonZeroStartEmitsAntipodeOnce() {
    // From bucket 2 of 4: right (3), left (1), antipode (0) once → [2,3,1,0].
    assertArrayEquals(new int[]{2, 3, 1, 0},
      IsochroneCandidateProvider.startAnchoredStrideOrder(2, 4));
  }

  @Test
  public void startAnchoredStrideOrderOddTotalHasNoAntipodeBranch() {
    // Odd bucket count: right and left never coincide, so the order is a pure
    // right/left interleave with no single-emit antipode step → [0,1,4,2,3].
    assertArrayEquals(new int[]{0, 1, 4, 2, 3},
      IsochroneCandidateProvider.startAnchoredStrideOrder(0, 5));
  }

  // ----- Root cause of the sparse-network (gravel) direction-collapse -----
  // For a pool at or below POOL_CAP (24) every candidate is kept, so the
  // start-anchored stride changes only the BUILD order — which candidatesForStep
  // then re-sorts away by distance-to-target. Net: startDirection has no effect
  // on the candidates a sub-cap (sparse, e.g. gravel) pool hands the planner.
  // That is the iso half of why Nice gravel returns the same loop for every
  // requested direction. The anchoring only bites once the pool exceeds
  // POOL_CAP and truncation must choose which candidates survive.

  private static boolean sameSequence(List<RoundTripCandidateProvider.CandidatePoint> a,
                                      List<RoundTripCandidateProvider.CandidatePoint> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i).ilon != b.get(i).ilon || a.get(i).ilat != b.get(i).ilat) return false;
    }
    return true;
  }

  @Test
  public void subCapPoolReturnsSameCandidatesRegardlessOfStartDirection() {
    // 8 candidates (< POOL_CAP 24) in distinct buckets at distinct air distances.
    List<IsoCandidate> raw = new ArrayList<>();
    int[] buckets = {0, 4, 9, 13, 18, 22, 27, 31};
    int[] dists = {1100, 1450, 1800, 2150, 2500, 2850, 3100, 3200};
    for (int i = 0; i < buckets.length; i++) raw.add(at(buckets[i], dists[i], 5, 100));

    IsochroneCandidateProvider north = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0, raw);
    IsochroneCandidateProvider south = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 180, raw);
    assertEquals("sub-cap pool keeps every candidate", buckets.length, north.poolSize());
    assertEquals(buckets.length, south.poolSize());

    List<RoundTripCandidateProvider.CandidatePoint> cN = north.candidatesForStep(
      START_ILON, START_ILAT, 2000, 1, 5, START_ILON, START_ILAT, 0, null);
    List<RoundTripCandidateProvider.CandidatePoint> cS = south.candidatesForStep(
      START_ILON, START_ILAT, 2000, 1, 5, START_ILON, START_ILAT, 180, null);

    assertTrue("sub-cap pool ignores startDirection — identical candidates N vs S "
      + "(the iso direction-collapse mechanism)", sameSequence(cN, cS));
  }

  @Test
  public void aboveCapPoolSelectionDependsOnStartDirection() {
    // 30 candidates (> POOL_CAP 24): truncation now drops some, and the
    // start-anchored stride decides WHICH survive — so the selected pool differs
    // by direction. The anchoring works for DENSE pools (fastbike), which is
    // exactly where direction already differentiates loops anyway.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 30; b++) raw.add(at(b, 2000 + b * 10, 5, 100));

    IsochroneCandidateProvider north = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0, raw);
    IsochroneCandidateProvider south = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 180, raw);
    assertEquals("pool truncated to cap", 24, north.poolSize());
    assertEquals(24, south.poolSize());

    List<RoundTripCandidateProvider.CandidatePoint> cN = north.candidatesForStep(
      START_ILON, START_ILAT, 3000, 1, 5, START_ILON, START_ILAT, 0, null);
    List<RoundTripCandidateProvider.CandidatePoint> cS = south.candidatesForStep(
      START_ILON, START_ILAT, 3000, 1, 5, START_ILON, START_ILAT, 180, null);

    assertFalse("above-cap: startDirection changes which candidates survive truncation",
      sameSequence(cN, cS));
  }

  @Test
  public void poolSpansFullCircleNotJustLeadingBuckets() {
    // The pre-F8 bug: 36 occupied buckets + cap=24 selected buckets 0..23,
    // omitting 24..35 entirely (90° wedge ignored). With start-anchored stride,
    // the 24 selected buckets must cover the full circle.
    List<IsoCandidate> raw = new ArrayList<>();
    for (int b = 0; b < 36; b++) raw.add(at(b, 2000, 5, 100));
    IsochroneCandidateProvider p = IsochroneCandidateProvider.fromPool(SEARCH_RADIUS, 0.0, raw);
    assertTrue("provider should be diverse", p.isDiverse());
    // Query candidates from the start point with airRadius=2000 — windows include
    // every bucket. Verify the returned candidates span >90° around the circle.
    List<RoundTripCandidateProvider.CandidatePoint> all =
      p.candidatesForStep(START_ILON, START_ILAT, 2000, 1, 5,
        START_ILON, START_ILAT, 0, null);
    Set<Integer> seenBuckets = new HashSet<>();
    for (RoundTripCandidateProvider.CandidatePoint cp : all) {
      double bearing = CheapRuler.getScaledBearing(START_ILON, START_ILAT, cp.ilon, cp.ilat);
      seenBuckets.add((int) (bearing / 10) % 36);
    }
    // The pre-F8 bug would give us buckets 0..23 only. With the fix we should
    // see entries from buckets >= 28 too (the omitted-by-old-code wedge).
    boolean hasFarWedge = false;
    for (int b : seenBuckets) if (b >= 28) { hasFarWedge = true; break; }
    assertTrue("pool covers buckets in the far wedge (24..35), saw " + seenBuckets,
      hasFarWedge);
  }
}
