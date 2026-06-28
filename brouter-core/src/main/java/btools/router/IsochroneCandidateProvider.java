package btools.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.util.CheapRuler;

/**
 * Road-native candidate provider for the ISO_GREEDY planner. Takes a pool of
 * {@link IsoCandidate}s captured during a single start-centered isochrone expansion and
 * returns the candidates that fall within the planner's target sub-leg air-distance window
 * at each step. In production this provider is not used on its own: it is the iso half of a
 * {@link BlendedCandidateProvider}, which appends per-step graph-native candidates.
 *
 * <p>The pool is filtered once at construction (drop too-close, dedupe identical positions,
 * limit to a diverse 12-24 candidates per spec) and then queried unchanged for each step.
 * Construct it via {@link #fromPool}; {@code RoutingEngine.buildCandidateProvider} first
 * checks {@link #poolSize()} and {@link #isDiverse()} and drops to plain graph-native when
 * the pool is too small or corridor-only.
 *
 * <p>The planner's window filter naturally avoids picking the same candidate twice (a just-
 * picked candidate has air-distance ≈ 0 from current, falling outside the window). Because
 * the pool is start-centered, the stored paths are start-to-candidate only and must not be
 * adopted as a sub-leg from a later step's current node — the planner re-routes instead.
 */
final class IsochroneCandidateProvider implements RoundTripCandidateProvider {

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
  /** Minimum distinct angular buckets the filtered pool must span. */
  private static final int MIN_DISTINCT_BUCKETS = 4;
  /** Minimum angular span of the filtered pool in degrees (avoid corridor-only pools). */
  private static final double MIN_ANGULAR_SPAN_DEG = 180.0;

  private final List<IsoCandidate> pool;
  private final boolean diverse;

  private IsochroneCandidateProvider(List<IsoCandidate> filteredPool) {
    this.pool = filteredPool;
    this.diverse = isAngularlyDiverse(filteredPool);
  }

  /**
   * The pool is "diverse" if it spans ≥{@value #MIN_DISTINCT_BUCKETS} distinct angular
   * buckets AND covers ≥{@value #MIN_ANGULAR_SPAN_DEG}° of arc. Pools that fail either
   * test are usually corridor-only (sparse rural lozere, or a single accessible
   * valley) and lead to false-success loops. In that case the caller
   * ({@code RoutingEngine.buildCandidateProvider}) falls back to the per-step
   * {@link GraphNativeCandidateProvider} instead of the blend — see {@link #isDiverse()}.
   */
  private static boolean isAngularlyDiverse(List<IsoCandidate> filteredPool) {
    if (filteredPool.size() < MIN_DISTINCT_BUCKETS) return false;
    java.util.BitSet bucketsPresent = new java.util.BitSet();
    for (IsoCandidate c : filteredPool) bucketsPresent.set(c.bucket);
    int distinctBuckets = bucketsPresent.cardinality();
    if (distinctBuckets < MIN_DISTINCT_BUCKETS) return false;
    // Largest angular gap between consecutive occupied buckets (with wraparound).
    // Span = 360 - largestGap.
    int totalBuckets = 36; // matches runIsochroneExpansion bucketCount
    int largestGap = 0;
    int firstOccupied = bucketsPresent.nextSetBit(0);
    int prev = firstOccupied;
    for (int b = bucketsPresent.nextSetBit(prev + 1); b >= 0; b = bucketsPresent.nextSetBit(b + 1)) {
      largestGap = Math.max(largestGap, b - prev);
      prev = b;
    }
    // Wraparound gap between the last and the first occupied bucket.
    largestGap = Math.max(largestGap, totalBuckets - prev + firstOccupied);
    double spanDeg = (totalBuckets - largestGap) * (360.0 / totalBuckets);
    return spanDeg >= MIN_ANGULAR_SPAN_DEG;
  }

  /** Total angular buckets used by {@link RoutingEngine#runIsochroneExpansion}. */
  private static final int TOTAL_BUCKETS = 36;

  /**
   * Construct a provider from a raw candidate pool. Applies spec-mandated filters:
   * drop too-close-to-start, drop sparse buckets when alternatives exist, dedupe
   * near-identical positions, ensure angular diversity, cap at {@link #POOL_CAP}.
   *
   * @param startDirection start bearing in degrees used to anchor the
   *                       angular-stride pick so the pool spans the full circle
   *                       starting at the user's direction. Need not be
   *                       normalized: any value (including a negative
   *                       no-preference sentinel such as {@code -1}) is floored
   *                       into its containing bucket modulo {@link #TOTAL_BUCKETS}
   *                       (negative results wrap), so {@code -1} anchors at
   *                       bucket 0 (North).
   */
  static IsochroneCandidateProvider fromPool(double searchRadius, double startDirection,
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
    java.util.BitSet strongBuckets = new java.util.BitSet(TOTAL_BUCKETS);
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
        entries.sort((a, b) -> Integer.compare(b.sourceContour, a.sourceContour));
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
   * Visit buckets in an order that starts at {@code startBucket} and spreads
   * outward, alternating sides — for 4 buckets starting at 0: [0, 1, 3, 2].
   * This guarantees the first {@code k} entries cover the full circle as evenly
   * as possible no matter what k is (subject to k ≤ total).
   */
  static int[] startAnchoredStrideOrder(int startBucket, int totalBuckets) {
    int[] order = new int[totalBuckets];
    order[0] = startBucket;
    int idx = 1;
    for (int offset = 1; offset < totalBuckets && idx < totalBuckets; offset++) {
      int rightBucket = (startBucket + offset) % totalBuckets;
      int leftBucket = (startBucket - offset + totalBuckets) % totalBuckets;
      if (rightBucket == leftBucket) {
        // Diametrically opposite — emit only once.
        order[idx++] = rightBucket;
      } else {
        // Right side first so the user-direction quadrant is favoured slightly.
        if (idx < totalBuckets) order[idx++] = rightBucket;
        if (idx < totalBuckets) order[idx++] = leftBucket;
      }
    }
    return order;
  }

  /** Size of the filtered candidate pool. Used by callers to decide whether to fall back. */
  int poolSize() {
    return pool.size();
  }

  /**
   * Whether the filtered pool spans enough distinct directions to plan a loop.
   * Returns {@code false} for corridor-only or single-direction pools (e.g.,
   * sparse rural terrain where every candidate clumps in a few buckets) — the
   * caller ({@code RoutingEngine.buildCandidateProvider}) then falls back to the
   * per-step {@link GraphNativeCandidateProvider} in that case.
   */
  boolean isDiverse() {
    return diverse;
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
      results.add(cp);
    }

    results.sort(Comparator.comparingDouble(p -> p.score));
    return results;
  }
}
