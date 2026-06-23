package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Semantic reuse classifier for round-trip routes.
 *
 * <p>The old quality gate used a single number — total reuse ratio — and
 * rejected anything above 50%. That number cannot tell apart:
 * <ul>
 *   <li>A 30% retraced lollipop where the cyclist gets a beautiful 70% loop
 *       plus a short retraced stick to a hub — perfectly good route.</li>
 *   <li>A 30% retraced loop where the planner accidentally u-turned in the
 *       middle, walking a back-and-forth that the cyclist would notice
 *       and complain about — bad route.</li>
 * </ul>
 *
 * <p>This classifier walks the track once, identifies <em>contiguous</em>
 * stretches of reused edges (not just total counts), and classifies each
 * stretch by where it sits in the route's cumulative-distance timeline:
 * <ul>
 *   <li><b>Short shared stem</b> at start/end — acceptable. The hotel
 *       driveway, the only road out of the valley, the bridge crossing.</li>
 *   <li><b>Terminal scenic spur</b> at start/end — acceptable when
 *       disclosed as a lollipop or out-and-back. The road to the cape,
 *       the climb up to the pass.</li>
 *   <li><b>Mid-route retrace</b> — suspect. If long, this is the planner
 *       u-turning for no semantic reason; reject.</li>
 * </ul>
 *
 * <p>Classification uses graph/routing topology only: where in the route
 * the reuse appears, how long the stretch is, whether the first-visit
 * edges of a stretch lie at the matching opposite end of the route (the
 * "out" of an out-and-back). No POI/elevation/name data is required —
 * those signals could strengthen the verdict later but must not be
 * mandatory (we don't always have them, and graph evidence is more
 * reliable anyway).
 */
public final class ReuseClassifier {

  // ---- Thresholds (spec-defined; expressible as constants for tests) -------

  /**
   * Absolute upper bound on what counts as a short unavoidable shared stem
   * near start/end (hotel driveway, only road out of valley). A stem longer
   * than this is treated as either a scenic spur (if structural evidence
   * supports it) or as suspect.
   */
  public static final int MAX_STEM_REUSE_METERS_ABS = 1500;
  /** Stem reuse cap as fraction of requested distance. */
  public static final double MAX_STEM_REUSE_FRAC = 0.05;

  /**
   * A boundary-touching <em>parallel-corridor</em> stretch (the return running
   * alongside the outbound on a different way, detected spatially rather than
   * by edge identity) longer than this is NOT forgiven as a short unavoidable
   * stem — it downgrades the route to OUT_AND_BACK. Below it, a short forced
   * parallel bit (the only metres of road out of a pinched start) is still
   * tolerated as a stem.
   */
  public static final int PARALLEL_CORRIDOR_MIN_METERS = 300;

  /**
   * Legacy fallback cap on mid-route retrace, used <em>only</em> when
   * {@code requestedDistance ≤ 0} (degenerate test fixtures). Production
   * code paths always have a positive requested distance and use
   * {@link #MAX_UNCLASSIFIED_CONTIGUOUS_REUSE_FRAC} × distance instead.
   *
   * <p>The previous {@code Math.min(2000, 0.08 × distance)} clamp was
   * removed: on long loops the absolute clamp was the binding constraint
   * for 132/143 of {@code ≥ 50 km} retrace rejections, all of which sat
   * below the 8% fraction rule and represented forced terrain rather
   * than accidental backtracking.
   */
  public static final int MAX_UNCLASSIFIED_CONTIGUOUS_REUSE_METERS_ABS = 2000;
  /**
   * Mid-route retrace cap as a fraction of requested distance. The
   * dominant production threshold; resolves to e.g. 2 km on a 25 km loop,
   * 4 km on a 50 km loop, 8 km on a 100 km loop.
   */
  public static final double MAX_UNCLASSIFIED_CONTIGUOUS_REUSE_FRAC = 0.08;

  /**
   * Soft band above the retrace cap: between cap and cap × this factor the
   * route is ACCEPTED with a disclosure instead of rejected. Root-caused on
   * freiburg_100km_fastbike_N (2026-06-11): the planner's natural closure was
   * 147m (1.8%) over the 7,992m cap — a hard reject there forced a zigzag
   * via-hop back across Waldkirch that bought three at-grade town crossings.
   * Retrace is co-linear riding the cyclist barely notices; a marginal
   * overage must not outweigh structural crossings. Above the band the hard
   * reject stands — that is genuine accidental backtracking.
   */
  public static final double UNCLASSIFIED_REUSE_SOFT_BAND = 1.25;

  /**
   * For LOLLIPOP acceptance: the non-retraced "loop body" must be at least
   * this fraction of the requested distance. Below this, the route is
   * really a OUT_AND_BACK in disguise (trivial loop, mostly the
   * stick) and should be treated as such.
   */
  public static final double MIN_LOLLIPOP_LOOP_FRACTION = 0.35;

  /**
   * Above this reuse ratio the route is essentially out-and-back: any "loop"
   * is trivial, and the cyclist will perceive it as same-way-back. Routes
   * above this threshold are classified as OUT_AND_BACK regardless
   * of structural detail.
   */
  public static final double OUT_AND_BACK_REUSE_RATIO = 0.85;

  /**
   * Per-stretch "near boundary" threshold: a stretch touches a boundary if
   * its start cumDist is within this fraction of total OR its end cumDist
   * is within this fraction of total from the end. 8% is generous enough
   * that a hotel-spur on a 50km loop (4km from the start) still counts as
   * "near the boundary".
   */
  public static final double BOUNDARY_PROXIMITY_FRAC = 0.08;

  private ReuseClassifier() { /* static-only */ }

  // ---- Public API ----------------------------------------------------------

  /** Resolve the stem cap for a route of the given requested distance. */
  public static int stemReuseCap(double requestedDistance) {
    if (requestedDistance <= 0) return MAX_STEM_REUSE_METERS_ABS;
    return (int) Math.min(MAX_STEM_REUSE_METERS_ABS,
      MAX_STEM_REUSE_FRAC * requestedDistance);
  }

  /**
   * Resolve the mid-route retrace rejection cap for a route of the given
   * requested distance. Production callers always pass a positive
   * {@code requestedDistance} and get the fraction-based cap; the legacy
   * absolute fallback is only used by degenerate test fixtures.
   */
  public static int unclassifiedContiguousCap(double requestedDistance) {
    if (requestedDistance <= 0) return MAX_UNCLASSIFIED_CONTIGUOUS_REUSE_METERS_ABS;
    return (int) (MAX_UNCLASSIFIED_CONTIGUOUS_REUSE_FRAC * requestedDistance);
  }

  /**
   * Walk the track and produce the contiguous reuse stretches plus per-edge
   * geometry needed for classification. Public so callers (planner scoring)
   * can reuse the analysis without re-walking the track. O(n) over track
   * edges; O(unique edges) memory.
   */
  public static TrackReuseProfile analyzeTrack(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) {
      return TrackReuseProfile.empty();
    }
    int n = track.nodes.size() - 1; // edge count
    int[] segLens = new int[n];
    boolean[] isReuse = new boolean[n];
    boolean[] spatialOnly = new boolean[n]; // spatial-overlap edge that is NOT an identity retrace
    double[] firstVisitCumStart = new double[n];
    int[] visitOrdinal = new int[n]; // 1, 2, 3, ... — visit number for this edge
    double cum = 0;
    // Per-edge tracking: [firstVisitCumStart, firstVisitCumEnd, visitCount].
    Map<Long, double[]> edgeState = new HashMap<>();

    // Spatial corridor overlap (a parallel return on a different way). Unioned
    // with edge-identity reuse below so the classifier sees same-corridor-back
    // that edge identity is blind to. visitOrdinal stays identity-only — a
    // parallel corridor is a 2-visit phenomenon, never a same-road zigzag.
    boolean[] spatialOverlap = CorridorOverlapIndex.computeEdgeOverlap(track);

    for (int i = 0; i < n; i++) {
      OsmPathElement a = track.nodes.get(i);
      OsmPathElement b = track.nodes.get(i + 1);
      int segLen = a.calcDistance(b);
      segLens[i] = segLen;

      long key = edgeKey(a, b);
      double[] state = edgeState.get(key);
      boolean identityReuse;
      if (state == null) {
        edgeState.put(key, new double[]{cum, cum + segLen, 1});
        identityReuse = false;
        firstVisitCumStart[i] = cum;
        visitOrdinal[i] = 1;
      } else {
        state[2] += 1;
        identityReuse = true;
        firstVisitCumStart[i] = state[0];
        visitOrdinal[i] = (int) state[2];
      }
      boolean spatial = i < spatialOverlap.length && spatialOverlap[i];
      isReuse[i] = identityReuse || spatial;
      spatialOnly[i] = spatial && !identityReuse;
      cum += segLen;
    }
    double totalDist = cum;

    // Walk reuse-flags to extract contiguous stretches.
    List<ReuseStretch> stretches = new ArrayList<>();
    int i = 0;
    double cumPrefix = 0;
    while (i < n) {
      if (!isReuse[i]) { cumPrefix += segLens[i]; i++; continue; }
      // Start of a contiguous reuse stretch.
      double startCum = cumPrefix;
      double firstVisitMin = Double.POSITIVE_INFINITY;
      double firstVisitMax = Double.NEGATIVE_INFINITY;
      double stretchLen = 0;
      int startEdgeIdx = i;
      int maxVisitOrdinal = 0;
      double spatialOnlyLen = 0;
      while (i < n && isReuse[i]) {
        stretchLen += segLens[i];
        double fvStart = firstVisitCumStart[i];
        if (fvStart < firstVisitMin) firstVisitMin = fvStart;
        // The first-visit end is firstVisitCumStart[i] + edge segLen; we
        // approximate using the same segLen (same edge).
        double fvEnd = fvStart + segLens[i];
        if (fvEnd > firstVisitMax) firstVisitMax = fvEnd;
        if (visitOrdinal[i] > maxVisitOrdinal) maxVisitOrdinal = visitOrdinal[i];
        if (spatialOnly[i]) spatialOnlyLen += segLens[i];
        cumPrefix += segLens[i];
        i++;
      }
      double endCum = cumPrefix;
      // A real parallel corridor is a mix: mostly a parallel return on a
      // different way, punctuated by the odd shared pinch (an identity retrace).
      // Classify by the majority of the stretch's length, not by requiring
      // every edge to be spatial — one shared bridge must not demote a 1 km
      // parallel corridor back to a benign stem.
      boolean stretchSpatialOnly = stretchLen > 0 && spatialOnlyLen * 2 >= stretchLen;
      stretches.add(new ReuseStretch(startEdgeIdx, i - 1,
        startCum, endCum, stretchLen, firstVisitMin, firstVisitMax, maxVisitOrdinal,
        stretchSpatialOnly));
    }

    return new TrackReuseProfile(totalDist, stretches);
  }

  /**
   * Classify a track and produce a structured quality verdict against the
   * given hard-gate parameters. This is the central decision: given the
   * track's topology (already validated for closure, distance, beelines
   * elsewhere), produce the RouteShape and accept/reject decision.
   *
   * <p>The classifier focuses exclusively on the reuse semantics. Hard
   * pre-checks (closure, distance ratio, beelines, profile-hostility) must
   * already have been applied — see {@link RoundTripQualityGate#evaluate}.
   */
  public static RoundTripQualityResult classify(OsmTrack track,
                                                double requestedDistance,
                                                boolean allowSamewayback) {
    TrackReuseProfile profile = analyzeTrack(track);
    return classifyFromProfile(profile, track, requestedDistance, allowSamewayback);
  }

  static RoundTripQualityResult classifyFromProfile(TrackReuseProfile profile,
                                                    OsmTrack track,
                                                    double requestedDistance,
                                                    boolean allowSamewayback) {
    RoundTripQualityResult.Builder b = RoundTripQualityResult.builder();

    if (profile.totalDistance <= 0) {
      return b.shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL, "empty track").build();
    }

    double totalReuse = 0;
    int maxContiguous = 0;
    for (ReuseStretch s : profile.stretches) {
      totalReuse += s.lengthMeters;
      if (s.lengthMeters > maxContiguous) maxContiguous = (int) Math.round(s.lengthMeters);
    }
    double reuseRatio = totalReuse / profile.totalDistance;
    b.totalReuseRatio(reuseRatio).maxContiguousReuseMeters(maxContiguous);

    int stemCap = stemReuseCap(requestedDistance);
    int midCap = unclassifiedContiguousCap(requestedDistance);

    // Accumulate as double (stretch lengths are fractional); round once where
    // surfaced, rather than truncating each stretch into an int.
    double stemMeters = 0;
    double spurMeters = 0;
    double parallelCorridorMeters = 0;
    int midRouteUnclassifiedMaxMeters = 0;
    int midRouteUnclassifiedTotalMeters = 0;
    boolean hasLongTerminalReuse = false;

    // Per-stretch classification. We use only graph/topology evidence.
    for (ReuseStretch s : profile.stretches) {
      StretchKind kind = classifyStretch(s, profile, stemCap);
      switch (kind) {
        case STEM:
          stemMeters += s.lengthMeters;
          break;
        case TERMINAL_SPUR:
          spurMeters += s.lengthMeters;
          hasLongTerminalReuse = true;
          break;
        case PARALLEL_CORRIDOR:
          parallelCorridorMeters += s.lengthMeters;
          break;
        case MID_ROUTE:
          int len = (int) Math.round(s.lengthMeters);
          midRouteUnclassifiedTotalMeters += len;
          if (len > midRouteUnclassifiedMaxMeters) midRouteUnclassifiedMaxMeters = len;
          break;
      }
    }
    b.terminalStemReuseMeters((int) Math.round(stemMeters))
      .scenicSpurReuseMeters((int) Math.round(spurMeters));

    // 1. Reject on long unclassified mid-route retrace (single stretch).
    //    Marginal overages (within UNCLASSIFIED_REUSE_SOFT_BAND) are accepted
    //    with a disclosure further below — a hard reject at the exact cap
    //    boundary forces the planner into worse alternatives (measured:
    //    a 147m overage bought a three-crossing town zigzag).
    int hardCap = (int) (midCap * UNCLASSIFIED_REUSE_SOFT_BAND);
    if (midRouteUnclassifiedMaxMeters > hardCap) {
      return b.shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.QUALITY, String.format(Locale.US,
          "mid-route retrace %dm exceeds %dm — accidental backtracking",
          midRouteUnclassifiedMaxMeters, hardCap))
        .build();
    }

    // 2. Reject on excessive total mid-route reuse (death-by-a-thousand-cuts:
    //    many small mid-route stretches that each pass the per-stretch cap
    //    but together turn the route into a zig-zag). Same cap as the
    //    single-stretch one.
    if (midRouteUnclassifiedTotalMeters > hardCap) {
      return b.shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.QUALITY, String.format(Locale.US,
          "cumulative mid-route retrace %dm exceeds %dm — route zig-zags",
          midRouteUnclassifiedTotalMeters, hardCap))
        .build();
    }

    // Soft band: over the calibrated cap but within the band — accept and
    // disclose, never silently.
    if (midRouteUnclassifiedTotalMeters > midCap || midRouteUnclassifiedMaxMeters > midCap) {
      b.addDisclosure(String.format(Locale.US,
        "contains %dm of mid-route retrace (within the %dm tolerance band)",
        Math.max(midRouteUnclassifiedTotalMeters, midRouteUnclassifiedMaxMeters), hardCap));
    }

    // 2b. Parallel return corridor: the route runs back alongside its outbound
    //    on a different way (detected spatially, invisible to edge identity).
    //    This is not a clean loop — downgrade to OUT_AND_BACK. Rejected under
    //    the internal gate (allowSamewayback=false) so the planner retries;
    //    surfaced as a disclosure by the lenient request gate.
    if (parallelCorridorMeters > 0) {
      String msg = String.format(Locale.US,
        "parallel return corridor: %dm alongside outbound",
        (int) Math.round(parallelCorridorMeters));
      if (!allowSamewayback) {
        return b.shape(RouteShape.OUT_AND_BACK)
          .reject(RoundTripQualityResult.RejectionTier.QUALITY, msg)
          .build();
      }
      return b.accepted(true).shape(RouteShape.OUT_AND_BACK)
        .addDisclosure(msg)
        .build();
    }

    // 3. Decide the route shape from total reuse + structure.
    //    Very high reuse → OUT_AND_BACK regardless of structural
    //    detail; accepted only if explicitly allowed.
    if (reuseRatio >= OUT_AND_BACK_REUSE_RATIO) {
      RouteShape shape = RouteShape.OUT_AND_BACK;
      if (!allowSamewayback) {
        return b.shape(shape)
          .reject(RoundTripQualityResult.RejectionTier.QUALITY, String.format(Locale.US,
            "route is %.0f%% retraced — out-and-back not allowed (allowSamewayback=0)",
            reuseRatio * 100))
          .build();
      }
      return b.accepted(true).shape(shape)
        .addDisclosure(String.format(Locale.US,
          "out-and-back: %.0f%% same-way-back, total %dm",
          reuseRatio * 100, (int) profile.totalDistance))
        .build();
    }

    // 4. Lollipop vs out-and-back: a substantial terminal spur exists.
    //    The non-retraced portion's TOPOLOGY decides which shape this is:
    //
    //    LOLLIPOP — the non-retraced edges form a "stem out + closed loop":
    //      A → … → H → loop → H. The hub H is visited multiple times in
    //      the non-retraced prefix.
    //
    //    OUT_AND_BACK — the non-retraced edges form a one-way path:
    //      A → … → Z (apex). Z is visited exactly once.
    //
    //    Distance-share alone cannot tell these apart (a pure out-and-back
    //    has 50% reuse / 50% non-retraced; that ratio is well above the
    //    35% lollipop threshold). The structural test below is what
    //    actually prevents a same-way-back from sneaking through as a
    //    lollipop.
    if (hasLongTerminalReuse) {
      double nonRetracedDist = profile.totalDistance - totalReuse;
      double loopFraction = (requestedDistance > 0)
        ? nonRetracedDist / requestedDistance
        : 0.0;
      boolean structuralLollipop = isStructuralLollipop(profile, track);

      if (structuralLollipop && loopFraction >= MIN_LOLLIPOP_LOOP_FRACTION) {
        return b.accepted(true).shape(RouteShape.LOLLIPOP)
          .addDisclosure(String.format(Locale.US,
            "contains retraced scenic spur: %.1fkm (loop body %.1fkm)",
            spurMeters / 1000.0, nonRetracedDist / 1000.0))
          .build();
      }
      // Either the topology is one-way (out-and-back) OR the loop body is
      // too small to call a real lollipop. Both cases are
      // OUT_AND_BACK: cyclist returns the same way along the spur.
      if (!allowSamewayback) {
        String why = structuralLollipop
          ? String.format(Locale.US,
              "loop body only %.0f%% of requested — effectively out-and-back, allowSamewayback=0",
              loopFraction * 100)
          : String.format(Locale.US,
              "route is %.0f%% retraced same-way-back to extremity — allowSamewayback=0",
              reuseRatio * 100);
        return b.shape(RouteShape.OUT_AND_BACK)
          .reject(RoundTripQualityResult.RejectionTier.QUALITY, why)
          .build();
      }
      String disclosure = structuralLollipop
        ? String.format(Locale.US,
            "out-and-back with %.0f%% loop body, spur %.1fkm",
            loopFraction * 100, spurMeters / 1000.0)
        : String.format(Locale.US,
            "out-and-back to extremity: %.1fkm retraced same way",
            spurMeters / 1000.0);
      return b.accepted(true).shape(RouteShape.OUT_AND_BACK)
        .addDisclosure(disclosure)
        .build();
    }

    // 5. Otherwise: no long terminal reuse, no disqualifying mid-route
    //    retrace, not in the very-high-reuse band. This is a STRICT_LOOP.
    //    Surface the stem/minor-overlap in a disclosure so callers don't
    //    claim the route is 100% unique when it isn't.
    RoundTripQualityResult.Builder out = b.accepted(true).shape(RouteShape.STRICT_LOOP);
    if (stemMeters > 0) {
      out.addDisclosure(String.format(Locale.US,
        "short shared stem near start/end: %dm", (int) Math.round(stemMeters)));
    }
    if (midRouteUnclassifiedTotalMeters > 0) {
      out.addDisclosure(String.format(Locale.US,
        "minor mid-route overlap: %dm (below %dm rejection cap)",
        midRouteUnclassifiedTotalMeters, midCap));
    }
    return out.build();
  }

  // ---- Internals -----------------------------------------------------------

  /**
   * Classify a single contiguous reuse stretch.
   *
   * <p>Stems and terminal spurs both touch a route boundary; they differ in
   * length. A stretch is judged terminal if its first-visit edges form the
   * outbound half of an out-and-back: i.e. the first visits sit at the
   * opposite end of the route from the current stretch, and together they
   * lead to/from the route's farthest-from-start point.
   */
  static StretchKind classifyStretch(ReuseStretch s, TrackReuseProfile profile, int stemCap) {
    double totalDist = profile.totalDistance;
    double boundaryProxAbs = BOUNDARY_PROXIMITY_FRAC * totalDist;

    boolean stretchTouchesStart = s.startCumDist <= boundaryProxAbs;
    boolean stretchTouchesEnd = s.endCumDist >= totalDist - boundaryProxAbs;
    boolean touchesBoundary = stretchTouchesStart || stretchTouchesEnd;

    // Where the first-visit edges of this stretch sit in the route timeline.
    boolean firstVisitsAtStart = s.firstVisitCumMin <= boundaryProxAbs;
    boolean firstVisitsAtEnd = s.firstVisitCumMax >= totalDist - boundaryProxAbs;

    // Zigzag check: any edge visited 3+ times within this stretch is a
    // structural signal of accidental backtracking. A clean out-and-back
    // or lollipop traverses each edge at most twice (out, then back).
    // Three or more visits means the planner U-turned multiple times on
    // the same road — not a scenic spur, even if the stretch happens to
    // touch a boundary.
    boolean zigzag = s.maxVisitOrdinal > 2;

    if (!touchesBoundary || zigzag) {
      // Mid-route reuse OR zigzagged boundary reuse: never a stem or spur.
      return StretchKind.MID_ROUTE;
    }

    // Parallel-corridor reuse (spatial overlap, NOT an edge-identity retrace):
    // a return running alongside the outbound on a different way. Above the
    // min length this is not a forgivable stem — it downgrades the route.
    // Below it, a short forced parallel bit out of a pinched start is tolerated
    // as a stem (handled by the stem branch below).
    if (s.spatialOnly && s.lengthMeters > PARALLEL_CORRIDOR_MIN_METERS) {
      return StretchKind.PARALLEL_CORRIDOR;
    }

    // Short boundary-touching reuse: stem.
    if (s.lengthMeters <= stemCap) {
      return StretchKind.STEM;
    }

    // Long boundary-touching reuse. For this to be a terminal spur, the
    // first-visit edges must be at the OPPOSITE end of the route from the
    // current stretch — i.e. the stretch is the inbound half of an
    // out-and-back pattern (or the outbound half of a same-stem-back).
    //
    // The "farthest-point inside the spur" check that the spec mentions as
    // an additional signal is intentionally NOT a hard gate here: in a real
    // lollipop with a small stem and a large loop body, the route's
    // farthest-from-start node sits in the loop, not on the stem. Requiring
    // the farthest point to lie inside the spur would mis-reject the
    // common "long stem + bigger loop" lollipop. The higher-level
    // classifier still decides LOLLIPOP vs OUT_AND_BACK via the
    // unique-portion topology (closed loop vs one-way), so this stretch
    // classification can safely be permissive.
    boolean outBackTopology =
      (stretchTouchesEnd && firstVisitsAtStart)
        || (stretchTouchesStart && firstVisitsAtEnd);

    if (!outBackTopology) {
      // Long boundary-touching reuse that ISN'T a stem-back pattern:
      // e.g. the engine zig-zagged twice through the same intersection
      // right at the start. Treat as mid-route — the cyclist will see
      // it as an accidental retrace.
      return StretchKind.MID_ROUTE;
    }

    return StretchKind.TERMINAL_SPUR;
  }

  /**
   * Decide whether the non-retraced portion of the track forms a "stem +
   * closed loop" (true lollipop) or a one-way "A → apex" path (out-and-back
   * disguised). The test: identify the node where the dominant reuse
   * stretch begins (the "hub" candidate) and count its occurrences in the
   * track prefix preceding the stretch. A true lollipop visits the hub at
   * least twice in the prefix (once arriving from the stem, once returning
   * from the loop body); an out-and-back visits the apex exactly once.
   *
   * <p>If no terminal reuse stretch is present, returns true (no out-and-
   * back to disqualify). If the prefix is too short to form a loop, returns
   * false (degenerate).
   */
  static boolean isStructuralLollipop(TrackReuseProfile profile, OsmTrack track) {
    if (track == null || track.nodes == null || profile.stretches.isEmpty()) {
      return false;
    }
    // Find the longest reuse stretch that touches a boundary — the
    // dominant terminal spur if any. Prefer end-touching stretches (the
    // common case) but also consider start-touching ones.
    ReuseStretch dominant = null;
    double dominantLen = -1;
    double totalDist = profile.totalDistance;
    double boundaryProxAbs = BOUNDARY_PROXIMITY_FRAC * totalDist;
    for (ReuseStretch s : profile.stretches) {
      boolean touchesEnd = s.endCumDist >= totalDist - boundaryProxAbs;
      boolean touchesStart = s.startCumDist <= boundaryProxAbs;
      if (!(touchesEnd || touchesStart)) continue;
      if (s.lengthMeters > dominantLen) {
        dominantLen = s.lengthMeters;
        dominant = s;
      }
    }
    if (dominant == null) return false;

    // The "hub candidate" is the route node at the start of the dominant
    // stretch (when the stretch touches the end) or the end of it (when
    // it touches the start). For both cases, the cyclist transitions from
    // the unique portion into the retraced portion at this node — it is
    // the hub of a lollipop or the apex of an out-and-back.
    boolean touchesEnd = dominant.endCumDist >= totalDist - boundaryProxAbs;
    int boundaryNodeIdx = touchesEnd
      ? dominant.firstEdgeIndex       // edge[firstEdgeIndex] starts at this node
      : dominant.lastEdgeIndex + 1;   // edge[lastEdgeIndex] ends at this node
    if (boundaryNodeIdx < 0 || boundaryNodeIdx >= track.nodes.size()) return false;

    OsmPathElement hubCandidate = track.nodes.get(boundaryNodeIdx);
    int prefixEnd = touchesEnd ? boundaryNodeIdx : track.nodes.size();
    // Both branches count the hub INCLUSIVELY: touchesEnd scans the prefix
    // [0, boundaryNodeIdx] (i <= prefixEnd), so touchesStart must scan the
    // suffix [boundaryNodeIdx, end] — starting at boundaryNodeIdx, not +1.
    // Skipping the hub here undercounts a start-touching lollipop's hub by one
    // and wrongly rejects it as OUT_AND_BACK.
    int prefixStart = touchesEnd ? 0 : boundaryNodeIdx;

    int occurrences = 0;
    for (int i = prefixStart; i <= prefixEnd && i < track.nodes.size(); i++) {
      OsmPathElement n = track.nodes.get(i);
      if (n.getILon() == hubCandidate.getILon()
          && n.getILat() == hubCandidate.getILat()) {
        occurrences++;
      }
    }
    // ≥ 2 occurrences in the unique-portion prefix ⇒ the hub is revisited
    // ⇒ the non-retraced edges form a closed loop ⇒ true LOLLIPOP.
    return occurrences >= 2;
  }

  private static long edgeKey(OsmPathElement a, OsmPathElement b) {
    long idA = a.getIdFromPos();
    long idB = b.getIdFromPos();
    long lo = Math.min(idA, idB);
    long hi = Math.max(idA, idB);
    return lo ^ (hi * 0x9E3779B97F4A7C15L);
  }

  // ---- Per-stretch types ---------------------------------------------------

  enum StretchKind { STEM, TERMINAL_SPUR, MID_ROUTE, PARALLEL_CORRIDOR }

  /** One contiguous run of reused edges in the track. */
  static final class ReuseStretch {
    final int firstEdgeIndex;
    final int lastEdgeIndex;
    final double startCumDist;
    final double endCumDist;
    final double lengthMeters;
    /** Earliest first-visit cumDist among edges in this stretch. */
    final double firstVisitCumMin;
    /** Latest first-visit cumDist+segLen among edges in this stretch. */
    final double firstVisitCumMax;
    /**
     * Maximum visit ordinal of any edge in this stretch. In a clean
     * out-and-back or lollipop every edge is visited at most twice
     * (once outbound, once inbound). A value &gt; 2 means at least one
     * edge was traversed a third time — structural evidence of zigzag /
     * accidental backtracking that we never accept as a scenic spur.
     */
    final int maxVisitOrdinal;
    /**
     * True when every edge in this stretch is a spatial corridor overlap that
     * is NOT also an edge-identity retrace — i.e. a parallel return on a
     * different way. Such a stretch is classified {@link StretchKind#PARALLEL_CORRIDOR}
     * (above the min length) rather than forgiven as a stem.
     */
    final boolean spatialOnly;

    ReuseStretch(int firstEdgeIndex, int lastEdgeIndex,
                 double startCumDist, double endCumDist, double lengthMeters,
                 double firstVisitCumMin, double firstVisitCumMax,
                 int maxVisitOrdinal, boolean spatialOnly) {
      this.firstEdgeIndex = firstEdgeIndex;
      this.lastEdgeIndex = lastEdgeIndex;
      this.startCumDist = startCumDist;
      this.endCumDist = endCumDist;
      this.lengthMeters = lengthMeters;
      this.firstVisitCumMin = firstVisitCumMin;
      this.firstVisitCumMax = firstVisitCumMax;
      this.maxVisitOrdinal = maxVisitOrdinal;
      this.spatialOnly = spatialOnly;
    }
  }

  /** Pre-computed reuse summary for a track. Exposed for tests and planner reuse. */
  static final class TrackReuseProfile {
    final double totalDistance;
    final List<ReuseStretch> stretches;

    TrackReuseProfile(double totalDistance, List<ReuseStretch> stretches) {
      this.totalDistance = totalDistance;
      this.stretches = Collections.unmodifiableList(stretches);
    }

    static TrackReuseProfile empty() {
      return new TrackReuseProfile(0, Collections.emptyList());
    }
  }
}
