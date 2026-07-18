package btools.router.roundtrip;

import btools.router.OsmTrack;
import btools.util.CheapRuler;

import java.util.*;

/**
 * Road-native candidate provider for the ISO_GREEDY planner: the iso half of a
 * {@link BlendedCandidateProvider} (which appends per-step graph-native
 * candidates). Holds {@link IsoCandidate}s from a single start-centered
 * isochrone expansion and returns those within the planner's target sub-leg
 * air-distance window at each step.
 *
 * <p>The pool is filtered once at construction (drop too-close, dedupe identical
 * positions, limit to a diverse 12-24 candidates) then queried unchanged.
 * Construct via {@link #fromPool}; {@code RoutingEngine.buildCandidateProvider}
 * checks {@link #poolSize()} and {@link #isDiverse()} first and drops to plain
 * graph-native when the pool is too small or corridor-only.
 *
 * <p>Because the pool is start-centered, the stored paths are start-to-candidate
 * only and must not be adopted as a sub-leg from a later step's current node —
 * the planner re-routes instead. (The window filter also avoids re-picking a
 * just-picked candidate, whose air-distance ≈ 0 falls outside the window.)
 */
public final class IsochroneCandidateProvider implements RoundTripCandidateProvider {

  /** Drop pool candidates closer than this fraction of searchRadius to start. */
  private static final double MIN_AIR_DIST_FRAC = 0.15;
  /** Final pool cap per spec ("12-24"). */
  private static final int POOL_CAP = 24;
  /** Window around the requested airRadius when filtering for a step. */
  private static final double STEP_WINDOW_LOW = 0.5;
  private static final double STEP_WINDOW_HIGH = 1.6;
  /** Dedupe granularity in ilon units (~7m at lat 50°N — within snap range). */
  private static final int DEDUPE_GRANULARITY = 100;
  /** Below this filtered-pool size, we relax the hits<3 filter (matches buildCandidateProvider gate). */
  private static final int MIN_DIVERSITY_BEFORE_RELAX = 6;
  /** Minimum distinct angular buckets the filtered pool must span. Referenced by
   *  {@link IsoPoolHealth}'s 0.50 calibration anchor, so relaxing the blend
   *  admission cannot silently invalidate the anchor. */
  static final int MIN_DISTINCT_BUCKETS = 4;
  /** Minimum angular span of the filtered pool in degrees (avoid corridor-only pools). */
  static final double MIN_ANGULAR_SPAN_DEG = 180.0;

  private final List<IsoCandidate> pool;
  private final boolean diverse;
  /** Distinct angular buckets occupied by the filtered pool (0-36). */
  private final int distinctSectors;
  /** Angular span of the filtered pool in degrees (360 − largest bucket gap). */
  private final double angularSpanDeg;
  /** Distinct source contours (25/50/75/100) sampled by the filtered pool. */
  private final int contourLevels;

  private IsochroneCandidateProvider(List<IsoCandidate> filteredPool) {
    this.pool = filteredPool;
    BitSet bucketsPresent = new BitSet();
    BitSet contoursPresent = new BitSet();
    for (IsoCandidate c : filteredPool) {
      bucketsPresent.set(c.bucket);
      if (c.sourceContour >= 0) contoursPresent.set(c.sourceContour);
    }
    this.distinctSectors = bucketsPresent.cardinality();
    this.angularSpanDeg = angularSpanDegrees(bucketsPresent);
    this.contourLevels = contoursPresent.cardinality();
    // The pool is "diverse" if it spans ≥ MIN_DISTINCT_BUCKETS distinct angular
    // buckets AND covers ≥ MIN_ANGULAR_SPAN_DEG° of arc. Pools that fail either
    // test are usually corridor-only (sparse rural lozere, or a single
    // accessible valley) and lead to false-success loops. In that case the
    // caller (RoutingEngine.buildCandidateProvider) falls back to the per-step
    // GraphNativeCandidateProvider instead of the blend — see isDiverse().
    this.diverse = filteredPool.size() >= MIN_DISTINCT_BUCKETS
      && distinctSectors >= MIN_DISTINCT_BUCKETS
      && angularSpanDeg >= MIN_ANGULAR_SPAN_DEG;
  }

  /**
   * Angular span covered by the occupied buckets: 360° minus the largest gap
   * between consecutive occupied buckets (with wraparound). 0 for an empty or
   * single-bucket set.
   */
  private static double angularSpanDegrees(BitSet bucketsPresent) {
    int firstOccupied = bucketsPresent.nextSetBit(0);
    if (firstOccupied < 0) return 0;
    int largestGap = 0;
    int prev = firstOccupied;
    for (int b = bucketsPresent.nextSetBit(prev + 1); b >= 0; b = bucketsPresent.nextSetBit(b + 1)) {
      largestGap = Math.max(largestGap, b - prev);
      prev = b;
    }
    // Wraparound gap between the last and the first occupied bucket.
    largestGap = Math.max(largestGap, TOTAL_BUCKETS - prev + firstOccupied);
    return (TOTAL_BUCKETS - largestGap) * (360.0 / TOTAL_BUCKETS);
  }

  /** Total angular buckets used by {@code RoutingEngine#runIsochroneExpansion}. */
  private static final int TOTAL_BUCKETS = 36;

  /**
   * Construct a provider from a raw candidate pool. Applies filters: drop
   * too-close-to-start, drop sparse buckets when alternatives exist, dedupe
   * near-identical positions, ensure angular diversity, cap at {@link #POOL_CAP}.
   *
   * @param startDirection start bearing (degrees) anchoring the angular-stride
   *                       pick so the pool spans the full circle from the user's
   *                       direction. Need not be normalized: any value is floored
   *                       into its bucket modulo {@link #TOTAL_BUCKETS} (negatives
   *                       wrap), so the {@code -1} no-preference sentinel anchors
   *                       at bucket 0 (North).
   */
  public static IsochroneCandidateProvider fromPool(double searchRadius, double startDirection,
                                             List<IsoCandidate> rawPool) {
    if (rawPool == null || rawPool.isEmpty()) {
      return new IsochroneCandidateProvider(new ArrayList<>());
    }

    // 1) Drop candidates too close to start.
    double minAirDist = searchRadius * MIN_AIR_DIST_FRAC;
    List<IsoCandidate> step1 = new ArrayList<>(rawPool.size());
    for (IsoCandidate c : rawPool) {
      if (c.airDistanceFromStart >= minAirDist) step1.add(c);
    }

    // 2) Drop low-population buckets (hits < 3) IF alternatives exist. A
    //    one-shot Dijkstra hit in a sparse bucket is usually a dead-end road
    //    sliver (rural_lozere noise pattern).
    // Count distinct strong *buckets*, not candidates: runIsochroneExpansion
    // emits up to (contourCount + 1) candidates per populated bucket, all
    // carrying that bucket's hit count, so a per-candidate tally inflates the
    // diversity estimate (~4x) and prunes sparse buckets far too eagerly.
    BitSet strongBuckets = new BitSet(TOTAL_BUCKETS);
    for (IsoCandidate c : step1) {
      if (c.bucketHits >= 3 && c.bucket >= 0 && c.bucket < TOTAL_BUCKETS) {
        strongBuckets.set(c.bucket);
      }
    }
    boolean dropLowPop = strongBuckets.cardinality() >= 12;
    List<IsoCandidate> step2 = new ArrayList<>(step1.size());
    for (IsoCandidate c : step1) {
      if (dropLowPop && c.bucketHits < 3) continue;
      step2.add(c);
    }
    // If aggressive filtering left less than the downstream-gate minimum, fall
    // back to step1 — keeps fragile cases from collapsing to RadialCandidate
    // fallback unnecessarily.
    List<IsoCandidate> usable = step2.size() >= MIN_DIVERSITY_BEFORE_RELAX ? step2 : step1;

    // 3) Dedupe near-identical positions (the same physical node may be the
    //    farthest in multiple contour bands when Dijkstra dwells locally).
    //    Pack the cell coordinates 32:32 (matches OsmNode.getIdFromPos style).
    List<IsoCandidate> deduped = new ArrayList<>();
    Set<Long> seenCells = new HashSet<>();
    for (IsoCandidate c : usable) {
      long cell = ((long) (c.ilon / DEDUPE_GRANULARITY) << 32)
        | ((c.ilat / DEDUPE_GRANULARITY) & 0xFFFFFFFFL);
      if (seenCells.add(cell)) deduped.add(c);
    }

    // 4) Group candidates by bucket, ordered by source contour DESC within each
    //    bucket (frontier-max preferred over inner contours).
    @SuppressWarnings("unchecked")
    List<IsoCandidate>[] byBucket = (List<IsoCandidate>[]) new List<?>[TOTAL_BUCKETS];
    for (IsoCandidate c : deduped) {
      int b = c.bucket;
      if (b < 0 || b >= TOTAL_BUCKETS) continue;
      if (byBucket[b] == null) byBucket[b] = new ArrayList<>(4);
      byBucket[b].add(c);
    }
    for (List<IsoCandidate> entries : byBucket) {
      if (entries != null) {
        Collections.sort(entries, (a, b) -> Integer.compare(b.sourceContour, a.sourceContour));
      }
    }

    // 5) Start-anchored angular stride: emit buckets in an order that starts at
    //    the user's direction and spreads around the full circle, so picking
    //    up to POOL_CAP from this sequence (skipping empties) yields an evenly-
    //    distributed pool — not the leading-buckets-clustered set the
    //    contour-then-bucket sort used to produce.
    // Floor (not round) into the containing bucket, matching how
    // runIsochroneExpansion buckets bearings; round-to-nearest would anchor a
    // direction in the outer half of a bucket (or near the 360° wrap) one
    // bucket off.
    int startBucket = ((int) (startDirection / (360.0 / TOTAL_BUCKETS))) % TOTAL_BUCKETS;
    if (startBucket < 0) startBucket += TOTAL_BUCKETS;
    int[] visitOrder = startAnchoredStrideOrder(startBucket, TOTAL_BUCKETS);

    List<IsoCandidate> selected = new ArrayList<>(POOL_CAP);
    // Round 1: take the best (highest-contour) candidate from each occupied
    // bucket in the strided order — gives every visited direction one shot
    // before any bucket gets a second pick.
    for (int b : visitOrder) {
      if (selected.size() >= POOL_CAP) break;
      List<IsoCandidate> entries = byBucket[b];
      if (entries != null && !entries.isEmpty()) {
        selected.add(entries.remove(0));
      }
    }
    // Round 2: a second candidate per bucket if there's still room, same order.
    for (int b : visitOrder) {
      if (selected.size() >= POOL_CAP) break;
      List<IsoCandidate> entries = byBucket[b];
      if (entries != null && !entries.isEmpty()) {
        selected.add(entries.remove(0));
      }
    }

    return new IsochroneCandidateProvider(selected);
  }

  /**
   * Visit buckets starting at {@code startBucket} in a coprime-stride order,
   * so EVERY prefix of the sequence is a near-uniform spread over the full
   * circle. That is what makes the pool cap safe: with 36 occupied buckets
   * and a 24-candidate cap, the former adjacent-alternating order
   * ([s, s+1, s-1, s+2, ...]) filled the cap with the contiguous band
   * s-11..s+12 and silently dropped one contiguous 120° wedge opposite the
   * start — exactly the far-side directions a loop's apex needs. A stride
   * coprime to the bucket count visits all buckets and spreads any prefix.
   */
  static int[] startAnchoredStrideOrder(int startBucket, int totalBuckets) {
    // Near totalBuckets/φ² and coprime to the bucket count (13 for the
    // production 36) — the golden-ratio choice that keeps every prefix
    // low-discrepancy. Fall back to 1 only for degenerate bucket counts.
    int stride = Math.max(1, (int) Math.round(totalBuckets * 0.382));
    while (stride > 1 && gcd(stride, totalBuckets) != 1) {
      stride--;
    }
    int[] order = new int[totalBuckets];
    for (int i = 0; i < totalBuckets; i++) {
      order[i] = (startBucket + i * stride) % totalBuckets;
    }
    return order;
  }

  private static int gcd(int a, int b) {
    while (b != 0) {
      int t = a % b;
      a = b;
      b = t;
    }
    return a;
  }

  /** Size of the filtered candidate pool. Used by callers to decide whether to fall back. */
  public int poolSize() {
    return pool.size();
  }

  /**
   * Whether the filtered pool spans enough distinct directions to plan a loop.
   * {@code false} for corridor-only or single-direction pools (sparse rural
   * terrain), on which the caller falls back to the per-step
   * {@link GraphNativeCandidateProvider}.
   */
  public boolean isDiverse() {
    return diverse;
  }

  /** Distinct angular buckets occupied by the filtered pool ({@link IsoPoolHealth} shape input). */
  public int distinctSectorCount() {
    return distinctSectors;
  }

  /** Angular span of the filtered pool in degrees ({@link IsoPoolHealth} shape input). */
  public double angularSpanDegrees() {
    return angularSpanDeg;
  }

  /** Distinct source contours sampled by the filtered pool ({@link IsoPoolHealth} shape input). */
  public int contourLevelCount() {
    return contourLevels;
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack) {

    double minWindow = airRadius * STEP_WINDOW_LOW;
    double maxWindow = airRadius * STEP_WINDOW_HIGH;
    double target = airRadius;

    List<CandidatePoint> results = new ArrayList<>();
    for (IsoCandidate c : pool) {
      double airDistFromCurrent = CheapRuler.distance(fromIlon, fromIlat, c.ilon, c.ilat);
      if (airDistFromCurrent < minWindow || airDistFromCurrent > maxWindow) continue;

      CandidatePoint cp = new CandidatePoint();
      cp.ilon = c.ilon;
      cp.ilat = c.ilat;
      cp.bearing = CheapRuler.getScaledBearing(fromIlon, fromIlat, c.ilon, c.ilat);
      // Stash the sort key on score to avoid recomputing distance per compare.
      // The planner overwrites score during its own ranking pass, so this is a
      // safe scratch slot.
      cp.score = Math.abs(airDistFromCurrent - target);
      // Forward iso-only metadata so the planner's scorer can prefer iso-
      // validated, dense-road candidates over geometric ring picks at the
      // same air-distance (option A: ISO-aware scoring).
      cp.costFromStart = c.costFromStart;
      cp.bucketHits = c.bucketHits;
      cp.sourceContour = c.sourceContour;
      // Start-centered ISO candidates are placement hints, not adoptable
      // sub-legs: their stored path, if any, runs from the original loop
      // start to the candidate. On later greedy steps the current node is
      // different, so the planner must route from the current node instead.
      // (Adopting the stored leg at step 1 — where the anchor matches — was
      // tried and measured quality-negative; see the note at the ISO_GREEDY
      // expansion call site in RoutingEngine.doGreedyRoundTrip.)
      results.add(cp);
    }

    Collections.sort(results, (a, b) -> Double.compare(a.score, b.score));
    return results;
  }
}
