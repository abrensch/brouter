package btools.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import btools.util.CheapRuler;

/**
 * Per-step graph-native candidate provider for greedy round-trip planning.
 *
 * <p>Unlike {@link RoundTripCandidateProvider.RadialCandidateProvider}, this
 * provider does not invent coordinates on a geometric ring. For every greedy
 * step it runs a bounded Dijkstra expansion from the current graph position and
 * returns real reached graph nodes near the requested sub-leg air distance.
 *
 * <p>When the expansion can compile the exact graph path to a candidate, the
 * provider attaches it so the planner can adopt that leg directly and only run
 * a metadata retrack before committing. If no exact path is available, the
 * planner falls back to normal point-to-point routing for that candidate.
 */
final class GraphNativeCandidateProvider implements RoundTripCandidateProvider {

  private static final double STEP_WINDOW_LOW = 0.50;
  private static final double STEP_WINDOW_HIGH = 1.65;
  private static final int CANDIDATE_CAP = 36;
  private static final int MIN_EXPANSION_RADIUS_M = 250;
  private static final int CACHE_RADIUS_GRANULARITY_M = 50;
  /** Dedupe granularity in ilon/ilat units, roughly 7-11m in typical regions. */
  private static final int DEDUPE_GRANULARITY = 100;

  /**
   * Hoisted template ranking comparator: distanceError ascending, then more
   * bucketHits first, then higher sourceContour first. Pure (captures no
   * state), so a shared static instance ranks byte-identically to the former
   * per-call allocation while removing comparator + 3 lambda allocations from
   * every {@code buildTemplates} call. {@code List.sort} stability preserves
   * the pre-sort insertion order for fully-equal templates.
   */
  private static final Comparator<Template> BY_TEMPLATE_RANK = Comparator
    .comparingDouble((Template t) -> t.distanceError)
    .thenComparing((Template t) -> -t.bucketHits)
    .thenComparing((Template t) -> -t.sourceContour);

  private final RoutingEngine engine;
  /**
   * Caches the <em>unfiltered</em> Dijkstra expansion per
   * (position, expansionRadius) — the full result, so both the candidate pool
   * and the reachability cloud are reused. The expensive expansion genuinely
   * is the same for a given rounded radius; the airRadius-specific window/sort
   * is applied per call in {@link #buildTemplates}, so callers whose airRadius
   * rounds to the same expansionRadius share the expansion without poisoning
   * each other's window.
   */
  private final Map<CacheKey, IsochroneExpansionResult> expansionCache = new HashMap<>();

  GraphNativeCandidateProvider(RoutingEngine engine) {
    this.engine = engine;
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack) {

    if (engine == null || airRadius <= 0) return new ArrayList<>();

    int expansionRadius = roundedExpansionRadius(airRadius);
    IsochroneExpansionResult expansion;
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
          expansionCache.put(key, expansion);
        }
      }
    } else {
      // Poisoning depends on the already-accepted route, so do not reuse the
      // no-ref cache when a reference track is present.
      expansion = runExpansion(fromIlon, fromIlat, expansionRadius, refTrack);
    }
    if (expansion == null) return new ArrayList<>();
    // Window/sort is airRadius-specific and must run per call, not be cached:
    // distinct airRadius values that round to the same expansionRadius share
    // the expansion above but need their own window [LOW, HIGH] and
    // distance-error sort.
    List<Template> templates = buildTemplates(expansion.candidates, fromIlon, fromIlat, airRadius);

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

    IsochroneExpansionResult expansion = engine.runIsochroneExpansion(
      current, expansionRadius, refTrack, true);
    if (expansion == null || expansion.candidates == null || expansion.candidates.isEmpty()) {
      engine.logInfo("graph-native candidates: no expansion result at radius "
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

    raw.sort(BY_TEMPLATE_RANK);

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
