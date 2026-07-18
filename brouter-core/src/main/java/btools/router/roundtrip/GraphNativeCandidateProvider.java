package btools.router.roundtrip;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.util.CheapRuler;

import java.util.*;

/**
 * Per-step graph-native candidate provider for greedy round-trip planning.
 * Instead of inventing coordinates on a geometric ring, each greedy step runs a
 * bounded Dijkstra expansion from the current graph position and returns real
 * reached nodes near the requested sub-leg air distance.
 *
 * <p>When the expansion can compile the exact graph path to a candidate, it is
 * attached so the planner adopts that leg directly (only a metadata retrack
 * before committing); otherwise the planner falls back to normal
 * point-to-point routing for that candidate.
 */
public final class GraphNativeCandidateProvider implements RoundTripCandidateProvider {

  private static final double STEP_WINDOW_LOW = 0.50;
  private static final double STEP_WINDOW_HIGH = 1.65;
  private static final int CANDIDATE_CAP = 36;
  private static final int MIN_EXPANSION_RADIUS_M = 250;
  private static final int CACHE_RADIUS_GRANULARITY_M = 50;
  /** Dedupe granularity in ilon/ilat units, roughly 7-11m in typical regions. */
  private static final int DEDUPE_GRANULARITY = 100;

  /**
   * Template ranking: distanceError ascending, then more bucketHits first, then
   * higher sourceContour first. Pure and shared static (no per-call allocation).
   * {@code List.sort} stability preserves insertion order for fully-equal
   * templates.
   */
  private static final Comparator<Template> BY_TEMPLATE_RANK = new Comparator<>() {
    // Explicit compare instead of Comparator.comparingDouble/thenComparing:
    // those are API 24+ and crash class-init on Android minSdk 23.
    @Override
    public int compare(Template a, Template b) {
      int c = Double.compare(a.distanceError, b.distanceError);
      if (c != 0) {
        return c;
      }
      c = Integer.compare(b.bucketHits, a.bucketHits);
      if (c != 0) {
        return c;
      }
      return Integer.compare(b.sourceContour, a.sourceContour);
    }
  };

  private final LegRouter legRouter;
  private final EngineIO io;
  /**
   * Caches the <em>unfiltered</em> Dijkstra expansion per (position,
   * expansionRadius), reusing both the candidate pool and the reachability
   * cloud. The airRadius-specific window/sort runs per call in
   * {@link #buildTemplates}, so callers whose airRadius rounds to the same
   * expansionRadius share the expansion without poisoning each other's window.
   */
  private final Map<CacheKey, IsochroneExpansionResult> expansionCache = new HashMap<>();

  /**
   * Single-slot reuse cache for WITH-refTrack expansions (the no-ref cache above
   * cannot serve them because poisoning depends on the committed route). Keyed
   * by position + refTrack INSTANCE identity + radius: the planner builds one
   * refTrack per step and reuses it across all backoff attempts, whose radii
   * only shrink, so the step's first (largest) expansion serves every later
   * attempt. A new step or plan builds a new refTrack instance, naturally
   * invalidating the slot.
   */
  private RefExpansionCacheEntry refExpansionCache;

  private static final class RefExpansionCacheEntry {
    final int fromIlon;
    final int fromIlat;
    final OsmTrack refTrack;
    final int radius;
    final IsochroneExpansionResult expansion;

    RefExpansionCacheEntry(int fromIlon, int fromIlat, OsmTrack refTrack,
                           int radius, IsochroneExpansionResult expansion) {
      this.fromIlon = fromIlon;
      this.fromIlat = fromIlat;
      this.refTrack = refTrack;
      this.radius = radius;
      this.expansion = expansion;
    }
  }

  public GraphNativeCandidateProvider(LegRouter legRouter, EngineIO io) {
    this.legRouter = legRouter;
    this.io = io;
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack) {

    if (legRouter == null || airRadius <= 0) return new ArrayList<>();

    int expansionRadius = roundedExpansionRadius(airRadius);
    IsochroneExpansionResult expansion;
    // Non-null only when already built on the ref-cache-hit path (avoids a
    // second window/dedupe/sort pass below).
    List<Template> templates = null;
    if (refTrack == null || refTrack.nodes == null || refTrack.nodes.isEmpty()) {
      CacheKey key = new CacheKey(fromIlon, fromIlat, expansionRadius);
      expansion = expansionCache.get(key);
      if (expansion == null) {
        expansion = runExpansion(fromIlon, fromIlat, expansionRadius, null);
        // Cache only non-empty expansions. Caching an empty/failed result would
        // silently serve "no candidates" to every later attempt at the same
        // radius without re-running the expansion (a transient failure becomes
        // permanent for that step).
        if (expansion != null && !expansion.candidates.isEmpty()) {
          // Bound the never-evicting map: each entry holds a visited-cell set
          // plus up to ~144 compiled candidate tracks, and the provider lives
          // for the whole subRouteCount ladder. 16 entries comfortably covers
          // one plan's step-1 radii; beyond that, old radii are stale anyway.
          if (expansionCache.size() >= 16) {
            expansionCache.clear();
          }
          expansionCache.put(key, expansion);
        }
      }
    } else {
      // Poisoning depends on the already-accepted route, so do not reuse the
      // no-ref cache when a reference track is present. But WITHIN a step the
      // refTrack is one constant instance across all backoff attempts (built
      // once at the step top), while the attempt radius only ever SHRINKS —
      // so a completed expansion at this position with the same refTrack and
      // a radius >= the requested one already contains every node the smaller
      // window can select. Reusing it saves up to maxAttempts-1 full bounded
      // Dijkstras (up to 1.5M pops each) per retry-heavy step. Safety valve
      // below: if the reused pool yields no template in the smaller window,
      // fall through to a fresh expansion at the requested radius.
      expansion = null;
      if (refExpansionCache != null
          && refExpansionCache.fromIlon == fromIlon && refExpansionCache.fromIlat == fromIlat
          && refExpansionCache.refTrack == refTrack
          && refExpansionCache.radius >= expansionRadius) {
        expansion = refExpansionCache.expansion;
      }
      if (expansion != null) {
        // Build the window/sort once and reuse it below — the emptiness probe
        // that validates the reused pool IS the template build, so on the
        // cache-hit path (the path this optimization targets) we must not
        // repeat it after the if/else.
        templates = buildTemplates(expansion.candidates, fromIlon, fromIlat, airRadius);
        if (templates.isEmpty()) {
          expansion = null; // reused pool too sparse at this radius — re-expand
          templates = null;
        }
      }
      if (expansion == null) {
        expansion = runExpansion(fromIlon, fromIlat, expansionRadius, refTrack);
        if (expansion != null && !expansion.candidates.isEmpty()) {
          refExpansionCache = new RefExpansionCacheEntry(
            fromIlon, fromIlat, refTrack, expansionRadius, expansion);
        }
      }
    }
    if (expansion == null) return new ArrayList<>();
    // Window/sort is airRadius-specific and must run per call, not be cached:
    // distinct airRadius values that round to the same expansionRadius share
    // the expansion above but need their own window [LOW, HIGH] and
    // distance-error sort. Already computed on the ref-cache-hit path above.
    if (templates == null) {
      templates = buildTemplates(expansion.candidates, fromIlon, fromIlat, airRadius);
    }

    List<CandidatePoint> result = new ArrayList<>(templates.size());
    for (Template t : templates) {
      CandidatePoint cp = new CandidatePoint();
      cp.ilon = t.ilon;
      cp.ilat = t.ilat;
      cp.bearing = t.bearing;
      cp.bucketHits = t.bucketHits;
      cp.routedTrack = t.routedTrack;
      // Pocket signal: reachable-cell density of the candidate's neighborhood
      // from this expansion's visited cloud. The planner penalizes low values
      // so vias stop landing on small roads in residual areas.
      cp.reachableCells = expansion.reachableCellsAround(t.ilon, t.ilat);
      // The scorer's costFromStart and sourceContour fields are start-centered
      // (cost/contour-depth measured from the loop start). These candidates are
      // expanded from the current step position, so both are inapplicable here;
      // leave them at the sentinels so the scorer's isoValidatedBonus AND
      // isoContourDepthMismatch both correctly treat these as non-iso-anchored.
      // The routed scorer will have the true leg data.
      cp.costFromStart = NO_ISO_COST;
      cp.sourceContour = NO_ISO_CONTOUR;
      result.add(cp);
    }
    return result;
  }

  private static int roundedExpansionRadius(double airRadius) {
    int r = (int) Math.max(MIN_EXPANSION_RADIUS_M, Math.ceil(airRadius));
    int g = CACHE_RADIUS_GRANULARITY_M;
    return ((r + g - 1) / g) * g;
  }

  /** Run (and return) the unfiltered Dijkstra expansion. Cached per radius. */
  private IsochroneExpansionResult runExpansion(int fromIlon, int fromIlat,
                                                int expansionRadius, OsmTrack refTrack) {
    OsmNodeNamed current = new OsmNodeNamed();
    current.ilon = fromIlon;
    current.ilat = fromIlat;
    current.name = "graph_native_step";

    IsochroneExpansionResult expansion = legRouter.runIsochroneExpansion(
      current, expansionRadius, refTrack, true);
    if (expansion == null || expansion.candidates == null || expansion.candidates.isEmpty()) {
      io.logInfo("graph-native candidates: no expansion result at radius "
        + expansionRadius + "m");
      return null;
    }
    return expansion;
  }

  /** Window-filter, dedupe, score and cap the expansion pool for one airRadius. */
  private List<Template> buildTemplates(List<IsoCandidate> pool, int fromIlon,
                                        int fromIlat, double targetAirRadius) {
    double minWindow = targetAirRadius * STEP_WINDOW_LOW;
    double maxWindow = targetAirRadius * STEP_WINDOW_HIGH;
    List<Template> raw = new ArrayList<>();
    Set<Long> seenCells = new HashSet<>();

    for (IsoCandidate c : pool) {
      double airDist = CheapRuler.distance(fromIlon, fromIlat, c.ilon, c.ilat);
      if (airDist < minWindow || airDist > maxWindow) continue;

      long cell = (((long) (c.ilon / DEDUPE_GRANULARITY)) << 32)
        | ((c.ilat / DEDUPE_GRANULARITY) & 0xFFFFFFFFL);
      if (!seenCells.add(cell)) continue;

      raw.add(new Template(
        c.ilon,
        c.ilat,
        CheapRuler.getScaledBearing(fromIlon, fromIlat, c.ilon, c.ilat),
        Math.abs(airDist - targetAirRadius),
        c.bucketHits,
        c.sourceContour,
        c.routedTrack));
    }

    Collections.sort(raw, BY_TEMPLATE_RANK);

    if (raw.size() > CANDIDATE_CAP) {
      return new ArrayList<>(raw.subList(0, CANDIDATE_CAP));
    }
    return raw;
  }

  private static final class Template {
    final int ilon;
    final int ilat;
    final double bearing;
    final double distanceError;
    final int bucketHits;
    final int sourceContour;
    final OsmTrack routedTrack;

    Template(int ilon, int ilat, double bearing, double distanceError,
             int bucketHits, int sourceContour, OsmTrack routedTrack) {
      this.ilon = ilon;
      this.ilat = ilat;
      this.bearing = bearing;
      this.distanceError = distanceError;
      this.bucketHits = bucketHits;
      this.sourceContour = sourceContour;
      this.routedTrack = routedTrack;
    }
  }

  private static final class CacheKey {
    final int ilon;
    final int ilat;
    final int radius;

    CacheKey(int ilon, int ilat, int radius) {
      this.ilon = ilon;
      this.ilat = ilat;
      this.radius = radius;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof CacheKey)) return false;
      CacheKey other = (CacheKey) obj;
      return ilon == other.ilon && ilat == other.ilat && radius == other.radius;
    }

    @Override
    public int hashCode() {
      int h = ilon;
      h = 31 * h + ilat;
      h = 31 * h + radius;
      return h;
    }
  }
}
