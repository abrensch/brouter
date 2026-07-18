package btools.router.roundtrip;

import btools.expressions.BExpressionContextWay;
import btools.mapaccess.MatchedWaypoint;
import btools.router.MessageData;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.util.CheapAngleMeter;

import java.util.*;

/**
 * Uniform hard acceptance gate for round-trip routes, applied to every
 * algorithm (WAYPOINT, ISOCHRONE, GREEDY, ISO_GREEDY). A failing hard check
 * sets {@code foundTrack=null} and surfaces a rejection reason; the algorithm's
 * "best effort" must NOT silently downgrade to a surprising route.
 *
 * <p>Hard rejects: synthetic beeline segment; ferry unless opted in; chaotic
 * geometry (self-crossings/hairpins); closure gap over {@link #MAX_CLOSURE_METERS};
 * distance ratio outside {@code [MIN_DISTANCE_RATIO, MAX_DISTANCE_RATIO]}; too
 * few nodes; and, for a paved-only profile, too much distance on
 * path/track/footway/unpaved surface (see {@link #checkHostileSegmentsPaved}).
 *
 * <p>On top of the hard checks a {@link ReuseClassifier semantic reuse
 * classifier} labels the loop: STRICT_LOOP (accept), LOLLIPOP (accept with
 * disclosure — a retraced terminal spur to a cape/pass/dead-end valley),
 * OUT_AND_BACK (accept only when {@code allowSamewayback=true}), INVALID_RETRACE
 * (reject — accidental mid-loop backtracking).
 *
 * <p>Thresholds err toward rejection: a clearly-bad route is worse than "no
 * route, try a different start or distance". The classifier deliberately
 * preserves iconic forced-spur routes by their structural signature rather than
 * treating all retracing as failure.
 */
public final class RoundTripQualityGate {

  /** Minimum acceptable {@code actualDistance / desiredDistance}. */
  static final double MIN_DISTANCE_RATIO = 0.5;
  /** Maximum acceptable {@code actualDistance / desiredDistance}. */
  static final double MAX_DISTANCE_RATIO = 1.8;
  /** Maximum acceptable gap between the route's start and end points. */
  static final int MAX_CLOSURE_METERS = 400;
  /** Minimum acceptable node count for a real loop (start + intermediate + close ≥ 4). */
  static final int MIN_NODES = 4;
  /**
   * Max self-crossings before a route is chaotic. Calibrated against the
   * mallorca-75km probe baseline (18 crossings, chaotic) vs iso_greedy (3,
   * clean); shape-aware variants produce 0-3, and routes over 5 are the
   * spaghetti planner outputs the gate must keep rejecting. A relaxation to 15
   * (Phase 2 v3) was reverted on user direction as far too loose.
   */
  static final int MAX_SELF_INTERSECTIONS = 5;
  /** Maximum hairpin-like turns allowed before a route is considered chaotic. */
  static final int MAX_HAIRPIN_TURNS = 20;
  /** Ignore tiny digitization jitter when counting U-turns. */
  private static final int MIN_HAIRPIN_SEGMENT_METERS = 25;

  /**
   * Max fraction of distance on profile-hostile edges (paved profile:
   * {@code highway=path|track|footway|...} or a high-cost spike). 10% is
   * intentionally tight — 10% of a 50km loop is 5km of unexpected dirt.
   * Complemented by {@link #MAX_CONTIGUOUS_HOSTILE_METERS}, which bounds the
   * longest unbroken hostile stretch (the physical "sent down 2km of farm track"
   * complaint surface).
   *
   * <p>Kept at 0.10 by deliberate Phase-3 calibration, not inheritance:
   * slackening it in favour of the contiguous check alone would admit the
   * "death by a thousand cuts" routes — hostile mileage in many small bursts on
   * an otherwise paved loop — that the contiguous cap cannot see.
   */
  static final double MAX_HOSTILE_FRACTION = 0.10;

  /**
   * Max combined share of distance that is confirmed-hostile OR unverifiable
   * (missing metadata). Aggregate backstop above each bucket's own
   * {@link #MAX_HOSTILE_FRACTION} ceiling, so a route just under both (e.g. 9%
   * hostile + 9% suspect) still fails; set higher so the single-bucket messages
   * fire first.
   */
  static final double MAX_QUESTIONABLE_FRACTION = 0.15;

  /**
   * Max length of a single unbroken hostile stretch on a paved profile,
   * regardless of total share — a 1.5km farm-track detour mid-loop is the
   * surprise cyclists complain about even when the rest of the loop is asphalt.
   */
  static final int MAX_CONTIGUOUS_HOSTILE_METERS = 1500;

  /**
   * Costfactor above which a single edge is profile-hostile regardless of tags.
   * Paved profiles return 1.0 for preferred ways (residential/cycleway/tertiary)
   * and >4 only for ways they actively avoid (track grade5, unpaved primary).
   */
  static final double HOSTILE_COSTFACTOR_THRESHOLD = 4.0;

  /**
   * Lower costfactor threshold for the scorer-side approximation
   * {@link #worstContiguousCostlyMetersForScorer}, which can't do tag-based
   * detection on single-pass tracks ({@code wayKeyValues} null). Set to 3.0 to
   * catch hostile-by-tag edges in the 2-4 fastbike cost band (below the gate's
   * hard 4.0) without flagging quiet rural roads (~1.0-2.0).
   */
  static final double SCORER_HOSTILE_COSTFACTOR_THRESHOLD = 3.0;

  /**
   * Way-tag fragments signalling profile-hostile terrain for a paved profile,
   * matched by substring against {@code MessageData.wayKeyValues} because the
   * cost lookup may not have populated {@code costfactor} for every edge.
   *
   * <p>Rough-but-rideable paved surfaces ({@code surface=cobblestone},
   * {@code pebblestone}) are deliberately excluded: fine on a road bike and
   * present in real cycle networks; listing them caused false-positive
   * rejections in suburban regions.
   */
  private static final String[] PAVED_PROFILE_HOSTILE_TAG_FRAGMENTS = {
    "highway=path",
    "highway=footway",
    "highway=bridleway",
    "highway=track",
    "highway=steps",
    "tracktype=grade3",
    "tracktype=grade4",
    "tracktype=grade5",
    "surface=ground",
    "surface=dirt",
    "surface=earth",
    "surface=grass",
    "surface=sand",
    "surface=mud",
    "surface=gravel",
    "surface=fine_gravel",
    "surface=unpaved",
  };

  /**
   * Surface fragments that override a hostile {@code highway=} classification
   * for paved profiles: a {@code highway=path} edge with one of these is paved
   * infrastructure (rural cycleways mis-tagged as path/footway/bridleway) and IS
   * road-bike rideable. OSM tagging is inconsistent, so {@code surface=} is the
   * more reliable rideability signal.
   */
  private static final String[] PAVED_PROFILE_HARD_SURFACE_FRAGMENTS = {
    "surface=asphalt",
    "surface=paved",
    "surface=paving_stones",
    "surface=concrete",
    "surface=concrete:plates",
    "surface=concrete:lanes",
    "surface=chipseal",
    // Constructed stone paving — rough and slow but a road bike CAN ride it
    // (cf. the cobbled sectors of Paris-Roubaix / the Tour of Flanders). These
    // are bona fide paved infrastructure, not loose off-road surface, so they
    // must not trip the hostile-stretch gate. The cost function may still mildly
    // penalise them for comfort; rideability and routing-preference are separate.
    "surface=pebblestone",
    "surface=cobblestone",
  };

  /**
   * Highway fragments whose hostility a
   * {@link #PAVED_PROFILE_HARD_SURFACE_FRAGMENTS} surface may override — the
   * subset of {@link #PAVED_PROFILE_HOSTILE_TAG_FRAGMENTS} commonly mis-tagged as
   * "narrow paved cycleway". Plain {@code highway=track} needs stronger evidence
   * ({@link #isRoadBikeSuitablePavedTrack(String)}); {@code highway=steps} is
   * never rideable regardless of surface.
   */
  private static final String[] SOFT_HIGHWAY_OVERRIDABLE = {
    "highway=path",
    "highway=footway",
    "highway=bridleway",
  };

  private RoundTripQualityGate() { /* static-only */ }

  // ---- Structured API ------------------------------------------------------

  /**
   * Run all hard checks plus the semantic reuse classifier and return a
   * structured verdict — route shape, disclosures, and reuse measurements (stem
   * length, scenic-spur length, max contiguous retrace). Evaluation order:
   * structural (null/nodes/closure/distance) → beeline/ferry → shape chaos →
   * profile-hostility → {@link ReuseClassifier#classify semantic reuse}; the
   * first failure wins. {@code pavedProfile} is the request-owned verdict from
   * {@link #classifyPavedProfile}; when true the hostile-surface checks apply.
   * Auto-generated mode; see the {@code explicitViaMode} overload for
   * explicit-via semantics.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                boolean pavedProfile, boolean allowSamewayback) {
    return evaluate(track, desiredDistance, pavedProfile, allowSamewayback, false);
  }

  /**
   * As {@link #evaluate(OsmTrack, double, boolean, boolean)} but with explicit-via
   * mode. When {@code explicitViaMode} is true the user's via skeleton defines
   * the route, so a distance-ratio mismatch and an INVALID_RETRACE verdict
   * downgrade from rejection to a disclosure. Hard safety checks (closure,
   * beeline, profile-hostility) stay active.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                boolean pavedProfile, boolean allowSamewayback,
                                                boolean explicitViaMode) {
    return evaluate(track, desiredDistance, pavedProfile, allowSamewayback, explicitViaMode, false);
  }

  /**
   * Full structured gate with explicit ferry opt-in: {@code route=ferry}/
   * {@code ferry=*} tags are hard failures unless {@code allowFerries} is set.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                boolean pavedProfile, boolean allowSamewayback,
                                                boolean explicitViaMode, boolean allowFerries) {
    if (track == null || track.nodes == null) {
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL, "no track").build();
    }
    int n = track.nodes.size();
    if (n < MIN_NODES) {
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL,
          "too few nodes (" + n + ", need ≥ " + MIN_NODES + ")")
        .build();
    }

    // 1. Closure: a loop must return to its origin.
    int closure = track.nodes.get(0).calcDistance(track.nodes.get(n - 1));
    if (closure > MAX_CLOSURE_METERS) {
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL,
          "closure=" + closure + "m exceeds " + MAX_CLOSURE_METERS + "m")
        .build();
    }

    // 2. Distance ratio: not below half (MIN_DISTANCE_RATIO=0.5) of the
    //    requested length, not above MAX_DISTANCE_RATIO=1.8× either.
    //    Same-way-back routes go out half the loop length then come back,
    //    so their total distance ≈ desired (out is half, back is half).
    //    Use the full-loop band either way; same-way-back doesn't change
    //    the expected total distance, only the shape.
    //
    //    Explicit-via mode: distance is advisory. A user routing through
    //    specific vias may end up with a total far from the requested
    //    {@code roundTripDistance} — that's the user's choice, not a fault
    //    of the engine. We record the mismatch as a disclosure below
    //    (via the classifier flow) instead of rejecting outright.
    double explicitViaDistanceRatioMismatch = 0; // 0 means within band
    if (desiredDistance > 0 && track.distance > 0) {
      double ratio = track.distance / desiredDistance;
      if (ratio < MIN_DISTANCE_RATIO || ratio > MAX_DISTANCE_RATIO) {
        if (!explicitViaMode) {
          return RoundTripQualityResult.builder()
            .shape(RouteShape.INVALID_RETRACE)
            .reject(RoundTripQualityResult.RejectionTier.QUALITY,
              String.format(Locale.US, "distance ratio %.2f outside [%.1f, %.1f]",
                ratio, MIN_DISTANCE_RATIO, MAX_DISTANCE_RATIO))
            .build();
        }
        explicitViaDistanceRatioMismatch = ratio;
      }
    }

    // 3. Beeline detection: the matcher marks waypoints as DIRECT when it
    //    could not snap them to a road and the engine had to insert a
    //    straight-line segment.
    List<MatchedWaypoint> mwps = track.getMatchedWaypoints();
    if (mwps != null) {
      for (MatchedWaypoint mwp : mwps) {
        if (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
          return RoundTripQualityResult.builder()
            .shape(RouteShape.INVALID_RETRACE)
            .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL,
              "track contains beeline (waypoint marked DIRECT)")
            .build();
        }
      }
    }

    // 4. Routed-segment hard markers: direct_segment means a beeline reached
    //    the final track without a DIRECT waypoint marker; ferry tags are
    //    rejected unless the request explicitly opted in.
    String synthetic = checkSyntheticSegments(track, allowFerries);
    if (synthetic != null) {
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(RoundTripQualityResult.RejectionTier.STRUCTURAL, synthetic)
        .build();
    }

    // 5. Geometry chaos: self-crossing spikes and repeated hairpins are
    //    user-visible failures even when every individual edge is routed.
    //    Tiering (load-robustness, 2026-06-12): moderate chaos stays QUALITY —
    //    the lenient product policy ships odd-but-rideable with a Warning. A
    //    crossing EXPLOSION (> 2× the cap) is not rideable-odd; it is the
    //    exhausted planner's weave residue (observed: coastal Nice 100km
    //    gravel shipping 42-57 crossings when CPU contention truncated the
    //    closure search) — STRUCTURAL, so lenient adoption, best-effort
    //    fallbacks and AUTO children all refuse it and fall through to
    //    cleaner candidates.
    // Computed once here and stamped onto every post-scan result below
    // (RoundTripQualityResult#getLoopAnalysis) so RouteChoiceScore, the
    // metrics, and the shipped-crossings advisory reuse it instead of
    // re-scanning the same full track. LoopAnalysis uses the metrics ceiling
    // (64): identical to the standalone count for every track at or below the
    // chaos thresholds; above ~20 crossings only the reported number can
    // differ, never the tier (both counts already exceed 2x the cap there).
    LoopAnalysis analysis = LoopAnalysis.of(track);
    int selfIntersections = analysis.selfIntersections;
    ChaosCheck chaos = checkShapeChaos(selfIntersections, track);
    if (chaos != null) {
      RoundTripQualityResult.RejectionTier tier =
        chaos.selfIntersections > 2 * MAX_SELF_INTERSECTIONS
          ? RoundTripQualityResult.RejectionTier.STRUCTURAL
          : RoundTripQualityResult.RejectionTier.QUALITY;
      // Stamp the analysis on this reject too: a QUALITY-tier chaos verdict is
      // exactly what lenient mode returns and AUTO ranks best-effort, so the
      // scorer/advisory reuse matters most on this path (long degraded routes).
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(tier, chaos.reason)
        .loopAnalysis(analysis)
        .build();
    }

    // 6. Profile-hostile segments: enforced for paved-only profiles even
    //    on scenic spurs. A LOLLIPOP through singletrack on a road bike
    //    is still bad — the scenic-spur exception is for the shape of the
    //    retracing, not for the surface compatibility.
    if (pavedProfile) {
      String hostile = checkHostileSegmentsPaved(track);
      if (hostile != null) {
        return RoundTripQualityResult.builder()
          .shape(RouteShape.INVALID_RETRACE)
          .reject(RoundTripQualityResult.RejectionTier.QUALITY, hostile)
          .loopAnalysis(analysis)
          .build();
      }
    }

    // 7. Semantic reuse classification — the heart of this gate.
    RoundTripQualityResult classified = ReuseClassifier.classify(
      track, desiredDistance, allowSamewayback).withLoopAnalysis(analysis);

    // Explicit-via mode: the user picked the route via the via skeleton, so
    // INVALID_RETRACE (mid-route retrace exceeds caps) downgrades from
    // rejection to an informational disclosure. Same for the distance-ratio
    // mismatch we captured above. We rebuild the result with accepted=true
    // and disclosures attached.
    if (!explicitViaMode) {
      return classified;
    }
    RoundTripQualityResult.Builder b = RoundTripQualityResult.builder()
      .accepted(true)
      .shape(classified.getShape())
      .totalReuseRatio(classified.getTotalReuseRatio())
      .maxContiguousReuseMeters(classified.getMaxContiguousReuseMeters())
      .terminalStemReuseMeters(classified.getTerminalStemReuseMeters())
      .scenicSpurReuseMeters(classified.getScenicSpurReuseMeters())
      .loopAnalysis(analysis);
    for (String d : classified.getDisclosures()) b.addDisclosure(d);
    if (!classified.isAccepted() && classified.getRejectionReason() != null) {
      b.addDisclosure("via-route note: " + classified.getRejectionReason());
    }
    if (explicitViaDistanceRatioMismatch != 0) {
      b.addDisclosure(String.format(Locale.US,
        "via-route distance %dm differs from requested %dm (ratio %.2f) — "
          + "distance is advisory only when via points are supplied",
        (int) track.distance, (int) desiredDistance, explicitViaDistanceRatioMismatch));
    }
    return b.build();
  }

  // ---- Hard safety helpers -------------------------------------------------

  private static String checkSyntheticSegments(OsmTrack track, boolean allowFerries) {
    for (int i = 1; i < track.nodes.size(); i++) {
      MessageData m = track.nodes.get(i).message;
      if (m == null || m.getWayKeyValues() == null) continue;
      String tags = m.getWayKeyValues();
      if (hasDirectSegmentTag(tags)) {
        return "track contains beeline (direct_segment marker)";
      }
      if (!allowFerries && hasFerryTag(tags)) {
        return "track contains ferry segment";
      }
    }
    return null;
  }

  private static boolean hasDirectSegmentTag(String tags) {
    return tags.contains("direct_segment");
  }

  private static boolean hasFerryTag(String tags) {
    return tags.contains("route=ferry") || tags.contains("ferry=");
  }

  /** Chaos verdict carrying the crossing count so {@link #evaluate}'s tier decision can reuse it. */
  private static final class ChaosCheck {
    final String reason;
    final int selfIntersections;

    ChaosCheck(String reason, int selfIntersections) {
      this.reason = reason;
      this.selfIntersections = selfIntersections;
    }
  }

  private static ChaosCheck checkShapeChaos(int selfIntersections, OsmTrack track) {
    if (selfIntersections > MAX_SELF_INTERSECTIONS) {
      return new ChaosCheck("route has " + selfIntersections + " self-intersections (max "
        + MAX_SELF_INTERSECTIONS + ") — chaotic loop geometry", selfIntersections);
    }

    int hairpins = countHairpinTurns(track);
    if (hairpins > MAX_HAIRPIN_TURNS) {
      return new ChaosCheck("route has " + hairpins + " hairpin turns (max "
        + MAX_HAIRPIN_TURNS + ") — chaotic loop geometry", selfIntersections);
    }
    return null;
  }

  /**
   * Crossings within this arc distance (m) of the route start or end are not
   * counted: leaving and returning through the same home neighborhood crosses
   * the outbound path by construction — expected, not a defect.
   */
  static final double CROSSING_START_END_EXEMPT_M = 500;

  /**
   * Vertical-separation exemption: a geometric crossing where either edge is a
   * bridge or tunnel is not at-grade (dominant false positives were bridge-ramp
   * loops and rivers re-crossed on different bridges). Edge tags ride on the
   * edge's END element ({@code message} of {@code nodes[i]} describes edge
   * i-1→i); raw tracks without messages get no exemption.
   */
  static boolean bridgeOrTunnelEdge(OsmPathElement edgeEnd) {
    if (edgeEnd == null || edgeEnd.message == null || edgeEnd.message.getWayKeyValues() == null) {
      return false;
    }
    String tags = edgeEnd.message.getWayKeyValues();
    return hasAffirmativeTag(tags, "bridge=") || hasAffirmativeTag(tags, "tunnel=");
  }

  /**
   * True when the space-separated {@code key=value} tag string carries the key
   * with an affirmative value — an explicit {@code bridge=no}/{@code bridge=0}/
   * {@code tunnel=false} must not exempt an at-grade crossing.
   */
  private static boolean hasAffirmativeTag(String tags, String keyEq) {
    int from = 0;
    while (true) {
      int i = tags.indexOf(keyEq, from);
      if (i < 0) {
        return false;
      }
      if (i == 0 || tags.charAt(i - 1) == ' ') {
        int valueStart = i + keyEq.length();
        int valueEnd = tags.indexOf(' ', valueStart);
        String value = valueEnd < 0 ? tags.substring(valueStart) : tags.substring(valueStart, valueEnd);
        return !"no".equals(value) && !"false".equals(value) && !"0".equals(value);
      }
      from = i + 1;
    }
  }

  public static int countSelfIntersections(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 4) return 0;
    // Full resolution at every size: the grid segment-pair scan and the hashed
    // node-revisit scan are near-linear, so the historical stride-decimation
    // guard (which fabricated crossings by chord-cutting curves — measured
    // gate=21 where the full count is 0) is no longer needed.
    List<OsmPathElement> nodes = track.nodes;
    int n = nodes.size();
    double[] cum = LoopGeometry.cumulativeDistances(nodes);
    double perim = cum[n - 1];
    // Hard ceiling proportional to the threshold; routes that already
    // qualify as chaotic don't benefit from precise upper counting and
    // we'd rather avoid the cost of long scans on degenerate input.
    int absoluteCeiling = MAX_SELF_INTERSECTIONS * 4;
    int crossings = countSegmentPairCrossings(nodes, cum, perim, absoluteCeiling);
    if (crossings > absoluteCeiling) return crossings;
    // The CCW scan above excludes segment pairs sharing an endpoint — but on a
    // road network most genuine self-crossings happen AT a shared junction node
    // (both passes ride through the same intersection), which made the count
    // systematically blind to exactly the knots a cyclist sees on the map
    // (observed: dreieich 50km fastbike W showing 2 visual knots, counted 1).
    crossings += countTransverseNodeRevisits(nodes, absoluteCeiling - crossings, cum);
    // Shared-corridor crossings: the route rides a short shared run (a roundabout
    // arc, a few junction edges) and exits the opposite side. Every node in the
    // run has a shared incident edge, so BOTH scans above exempt it — yet it is a
    // real knot (Rond-Point de la Contamine; Diacquenods figure-eight). Computed
    // on FULL-resolution nodes (sampling breaks the node-identity adjacency the
    // run-grouping needs); additive without double-counting because the shared
    // edges make these invisible to the segment/per-node scans. See sharedCorridors.
    if (crossings <= absoluteCeiling) {
      crossings += countCorridorCrossings(track.nodes);
    }
    return crossings;
  }

  /** Node count from which the segment-pair scan switches to the spatial grid. */
  private static final int GRID_MIN_SEGMENTS = 512;
  /**
   * Grid cell edge in raw ilon/ilat units (microdegrees): ~220m in latitude.
   * Typical sampled segments cover 1-4 cells, keeping bucket occupancy small and
   * the scan near-linear.
   */
  private static final int GRID_CELL_UNITS = 2000;

  /** Java-8 Math#floorDiv, inlined for Android API &lt; 24 compatibility. */
  private static int floorDiv(int x, int y) {
    int q = x / y;
    if ((x % y != 0) && ((x ^ y) < 0)) {
      q--;
    }
    return q;
  }

  /**
   * Per-crossing callback for the segment-pair scans: {@code i}/{@code j} are the
   * first-node indices of the two crossing segments. Fired exactly once per
   * counted crossing (including the one that trips the ceiling). For a COMPLETED
   * scan (total &le; ceiling) the grid and brute scans fire the same SET of
   * pairs, though not in the same order. A ceiling-truncated scan stops after
   * ceiling+1 crossings, and because enumeration order differs the two scans may
   * fire DIFFERENT subsets — counts still agree (both ceiling+1), but per-pair
   * classifications (smallLoop split, crossing markers) are only meaningful
   * below the ceiling.
   */
  interface SegmentCrossingVisitor {
    void crossing(int i, int j);
  }

  /**
   * Count crossing segment pairs (j >= i+2, both outside the start/end exemption
   * windows and not bridge/tunnel; closure pair (0, n-2) excluded). Dispatches to
   * a spatial-hash grid above {@link #GRID_MIN_SEGMENTS} segments; the grid yields
   * the IDENTICAL count as the O(n²) brute scan (a crossing lies in both segments'
   * bounding boxes so they share a cell; below the ceiling all pairs are counted,
   * above it both return ceiling+1).
   */
  static int countSegmentPairCrossings(List<OsmPathElement> nodes, double[] cum,
                                       double perim, int absoluteCeiling) {
    return countSegmentPairCrossings(nodes, cum, perim, absoluteCeiling, null);
  }

  /** As above, with a per-crossing visitor — the scorer path's arc classification hook. */
  static int countSegmentPairCrossings(List<OsmPathElement> nodes, double[] cum,
                                       double perim, int absoluteCeiling,
                                       SegmentCrossingVisitor visitor) {
    return nodes.size() - 1 >= GRID_MIN_SEGMENTS
      ? gridSegmentPairCrossings(nodes, cum, perim, absoluteCeiling, visitor)
      : bruteForceSegmentPairCrossings(nodes, cum, perim, absoluteCeiling, visitor);
  }

  /** The historical all-pairs scan, kept for small inputs and as the equivalence-test oracle. */
  static int bruteForceSegmentPairCrossings(List<OsmPathElement> nodes, double[] cum,
                                            double perim, int absoluteCeiling,
                                            SegmentCrossingVisitor visitor) {
    int n = nodes.size();
    int crossings = 0;
    for (int i = 0; i < n - 1; i++) {
      OsmPathElement a1 = nodes.get(i);
      OsmPathElement a2 = nodes.get(i + 1);
      boolean aExempt = cum[i + 1] <= CROSSING_START_END_EXEMPT_M
        || cum[i] >= perim - CROSSING_START_END_EXEMPT_M
        || bridgeOrTunnelEdge(a2);
      for (int j = i + 2; j < n - 1; j++) {
        // The first and last segments in a closed loop share the start/end
        // coordinate; that closure is not a self-crossing.
        if (i == 0 && j == n - 2) continue;
        if (aExempt
          || cum[j + 1] <= CROSSING_START_END_EXEMPT_M
          || cum[j] >= perim - CROSSING_START_END_EXEMPT_M) continue;
        if (segmentsCross(a1, a2, nodes.get(j), nodes.get(j + 1))) {
          if (bridgeOrTunnelEdge(nodes.get(j + 1))) continue; // vertically separated
          crossings++;
          if (visitor != null) visitor.crossing(i, j);
          if (crossings > absoluteCeiling) return crossings;
        }
      }
    }
    return crossings;
  }

  private static int gridSegmentPairCrossings(List<OsmPathElement> nodes, double[] cum,
                                              double perim, int absoluteCeiling,
                                              SegmentCrossingVisitor visitor) {
    int segCount = nodes.size() - 1;
    // A segment flagged here can never be part of a counted pair: the brute
    // scan skips exempt i rows and exempt j columns, and a bridge/tunnel on
    // either side suppresses the count after the cross test — so flagged
    // segments are excluded from the grid entirely.
    boolean[] skip = new boolean[segCount];
    for (int i = 0; i < segCount; i++) {
      skip[i] = cum[i + 1] <= CROSSING_START_END_EXEMPT_M
        || cum[i] >= perim - CROSSING_START_END_EXEMPT_M
        || bridgeOrTunnelEdge(nodes.get(i + 1));
    }
    Map<Long, List<Integer>> grid = new HashMap<>(segCount * 2);
    for (int i = 0; i < segCount; i++) {
      if (skip[i]) continue;
      OsmPathElement a = nodes.get(i);
      OsmPathElement b = nodes.get(i + 1);
      // floorDiv, not /: BRouter ilon/ilat are non-negative by convention,
      // but floor division keeps the cell partition uniform even for exotic
      // negative inputs (truncation would make cell 0 twice as wide).
      int x0 = floorDiv(Math.min(a.getILon(), b.getILon()), GRID_CELL_UNITS);
      int x1 = floorDiv(Math.max(a.getILon(), b.getILon()), GRID_CELL_UNITS);
      int y0 = floorDiv(Math.min(a.getILat(), b.getILat()), GRID_CELL_UNITS);
      int y1 = floorDiv(Math.max(a.getILat(), b.getILat()), GRID_CELL_UNITS);
      for (int x = x0; x <= x1; x++) {
        for (int y = y0; y <= y1; y++) {
          long cellHash = (((long) x) << 32) | (y & 0xFFFFFFFFL);
          List<Integer> bucket = grid.get(cellHash);
          if (bucket == null) {
            bucket = new ArrayList<>();
            grid.put(cellHash, bucket);
          }
          bucket.add(i);
        }
      }
    }
    int[] lastTested = new int[segCount];
    Arrays.fill(lastTested, -1);
    int crossings = 0;
    for (int i = 0; i < segCount; i++) {
      if (skip[i]) continue;
      OsmPathElement a1 = nodes.get(i);
      OsmPathElement a2 = nodes.get(i + 1);
      int x0 = floorDiv(Math.min(a1.getILon(), a2.getILon()), GRID_CELL_UNITS);
      int x1 = floorDiv(Math.max(a1.getILon(), a2.getILon()), GRID_CELL_UNITS);
      int y0 = floorDiv(Math.min(a1.getILat(), a2.getILat()), GRID_CELL_UNITS);
      int y1 = floorDiv(Math.max(a1.getILat(), a2.getILat()), GRID_CELL_UNITS);
      for (int x = x0; x <= x1; x++) {
        for (int y = y0; y <= y1; y++) {
          List<Integer> bucket = grid.get((((long) x) << 32) | (y & 0xFFFFFFFFL));
          if (bucket == null) continue;
          for (int bi = 0; bi < bucket.size(); bi++) {
            int j = bucket.get(bi);
            if (j <= i + 1 || lastTested[j] == i) continue;
            lastTested[j] = i;
            // The first and last segments in a closed loop share the start/end
            // coordinate; that closure is not a self-crossing.
            if (i == 0 && j == segCount - 1) continue;
            if (segmentsCross(a1, a2, nodes.get(j), nodes.get(j + 1))) {
              crossings++;
              if (visitor != null) visitor.crossing(i, j);
              if (crossings > absoluteCeiling) return crossings;
            }
          }
        }
      }
    }
    return crossings;
  }

  /**
   * Count node revisits where the second pass crosses the first transversely —
   * the four incident directions interleave around the shared node. A
   * touch-and-turn (teardrop pinch) and a same-edge retrace (shared neighbor) are
   * NOT crossings; the former is the near-revisit detector's domain, the latter
   * is reuse.
   */
  /** Per-revisit hook for {@link #transverseNodeRevisits} — LoopAnalysis's
   *  arc-classification and location collection. */
  interface NodeRevisitVisitor {
    void revisit(int k1, int k);
  }

  /**
   * Visitor-capable transverse node-revisit scan for {@link LoopAnalysis} —
   * the same exemptions and {@link #isTransverseRevisit} test as
   * {@link #countTransverseNodeRevisits}, with the historical
   * {@code LoopQualityMetrics.detectCrossings} boundary semantics (the
   * revisit that exceeds {@code budget} is still counted and visited before
   * the scan stops).
   */
  static int transverseNodeRevisits(List<OsmPathElement> nodes, double[] cum,
                                    int budget, NodeRevisitVisitor visitor) {
    int n = nodes.size();
    if (n < 5) return 0;
    double perim = cum[n - 1];
    Map<Long, int[]> first = new HashMap<>(n * 2);
    int crossings = 0;
    for (int k = 1; k < n - 1; k++) {
      long id = nodes.get(k).getIdFromPos();
      int[] prevIdx = first.get(id);
      if (prevIdx == null) {
        first.put(id, new int[]{k});
        continue;
      }
      boolean kExempt = cum[k] <= CROSSING_START_END_EXEMPT_M
        || cum[k] >= perim - CROSSING_START_END_EXEMPT_M;
      for (int k1 : prevIdx) {
        if (k - k1 <= 1) continue;
        if (kExempt || cum[k1] <= CROSSING_START_END_EXEMPT_M) continue;
        if (isTransverseRevisit(nodes, k1, k)) {
          crossings++;
          if (visitor != null) visitor.revisit(k1, k);
          if (crossings > budget) return crossings;
        }
      }
      int[] grown = Arrays.copyOf(prevIdx, prevIdx.length + 1);
      grown[prevIdx.length] = k;
      first.put(id, grown);
    }
    return crossings;
  }

  private static int countTransverseNodeRevisits(List<OsmPathElement> nodes, int ceiling, double[] cum) {
    int n = nodes.size();
    if (n < 5 || ceiling <= 0) return 0;
    double perim = cum[n - 1];
    Map<Long, int[]> first = new HashMap<>(n * 2);
    int crossings = 0;
    for (int k = 1; k < n - 1; k++) {
      long id = nodes.get(k).getIdFromPos();
      int[] prevIdx = first.get(id);
      if (prevIdx == null) {
        first.put(id, new int[]{k});
        continue;
      }
      // Start/end exemption: revisits of a junction in the home zone are the
      // expected leave-and-return weave, not a defect (see
      // CROSSING_START_END_EXEMPT_M).
      boolean kExempt = cum[k] <= CROSSING_START_END_EXEMPT_M
        || cum[k] >= perim - CROSSING_START_END_EXEMPT_M;
      for (int k1 : prevIdx) {
        if (k - k1 <= 1) continue;
        if (kExempt || cum[k1] <= CROSSING_START_END_EXEMPT_M) continue;
        if (isTransverseRevisit(nodes, k1, k)) {
          crossings++;
          if (crossings >= ceiling) return crossings;
        }
      }
      int[] grown = Arrays.copyOf(prevIdx, prevIdx.length + 1);
      grown[prevIdx.length] = k;
      first.put(id, grown);
    }
    return crossings;
  }

  // Package-visible: LoopQualityMetrics.detectCrossings reuses the same
  // transversality test so the report metric and the gate cannot drift.
  static boolean isTransverseRevisit(List<OsmPathElement> nodes, int k1, int k2) {
    OsmPathElement p = nodes.get(k1);
    OsmPathElement in1 = nodes.get(k1 - 1);
    OsmPathElement out1 = nodes.get(k1 + 1);
    OsmPathElement in2 = nodes.get(k2 - 1);
    OsmPathElement out2 = nodes.get(k2 + 1);
    // Shared-edge guard: a neighbor of pass 2 coinciding with a neighbor of
    // pass 1 means the passes share an incident edge — retrace, not a crossing.
    if (samePoint(in2, in1) || samePoint(in2, out1)
        || samePoint(out2, in1) || samePoint(out2, out1)) {
      return false;
    }
    // Degenerate zero-length neighbors cannot define a direction.
    if (samePoint(in1, p) || samePoint(out1, p) || samePoint(in2, p) || samePoint(out2, p)) {
      return false;
    }
    double b1 = CheapAngleMeter.getDirection(p.getILon(), p.getILat(), in1.getILon(), in1.getILat());
    double b2 = CheapAngleMeter.getDirection(p.getILon(), p.getILat(), out1.getILon(), out1.getILat());
    double c1 = CheapAngleMeter.getDirection(p.getILon(), p.getILat(), in2.getILon(), in2.getILat());
    double c2 = CheapAngleMeter.getDirection(p.getILon(), p.getILat(), out2.getILon(), out2.getILat());
    // Pass 1 splits the angular circle at b1/b2; pass 2 crosses transversely
    // iff its two directions fall in DIFFERENT sectors.
    boolean c1InSector = angleInSector(c1, b1, b2);
    boolean c2InSector = angleInSector(c2, b1, b2);
    return c1InSector != c2InSector;
  }

  /** Whether {@code x} lies in the clockwise sector from {@code from} to {@code to}. */
  private static boolean angleInSector(double x, double from, double to) {
    double span = (to - from + 360.0) % 360.0;
    double off = (x - from + 360.0) % 360.0;
    return off > 0 && off < span;
  }

  // ======================================================================
  // Shared-corridor crossings — LIVE (wired into countSelfIntersections
  // 2026-06-13, after the labeling pass below confirmed the rule).
  //
  // Annecy investigation (2026-06-11): a route that crosses itself THROUGH a
  // shared run of edges (a roundabout arc, a few junction edges) defeats
  // isTransverseRevisit's shared-edge guard at every node of the run, so the
  // count was systematically blind to exactly the X-knots a cyclist sees
  // (Rond-Point de la Contamine; Route des Diacquenods figure-eight). Matrix
  // harvest: 16% of shipped AUTO loops carried at least one such candidate.
  //
  // Labeling pass (2026-06-13, AI vision panel over 275 corridors) settled two
  // design points and the result was wired into countSelfIntersections:
  //  - LENGTH BOUND (MAX_CORRIDOR_CROSS_M): above ~300m of shared run, even a
  //    genuine geometric side-swap is dominated by the overlap and reads as
  //    road reuse — already priced by reuse% / CorridorOverlapIndex, so it must
  //    not also be counted as a crossing. Applied below.
  //  - GEOMETRY NOT further guarded: the ~3% short borderline false positives
  //    do NOT form a class separable from real crossings by local geometry (a
  //    confirmed crossing sat at a 2.9° margin, below three reuse cases), so a
  //    hand-tuned margin guard would overfit and create false negatives. Left
  //    as accepted noise: +1 spurious crossing on ~3% of routes, well under the
  //    MAX_SELF_INTERSECTIONS gate.
  // ======================================================================

  /**
   * Upper bound on shared-run length (m) for a corridor to count as a crossing;
   * longer same-direction overlaps are road reuse, not knots (see section note).
   */
  static final double MAX_CORRIDOR_CROSS_M = 300;

  /**
   * Maximal shared corridors of a closed track: runs of >=2 consecutive node
   * revisits (>=1 shared EDGE; the single-node case stays with
   * {@link #isTransverseRevisit}). Returns one {@code int[]{a1, a2, b1, b2,
   * sameDir, crossing}} per run — pass-1 span {@code a1..a2}, pass-2 span
   * {@code b1..b2}, {@code sameDir} whether pass 2 rides the run the same way,
   * {@code crossing} whether the loop crosses itself through it. Crossing
   * requires all three: same-direction (opposite = two-way retrace, the reuse
   * domain), run no longer than {@link #MAX_CORRIDOR_CROSS_M}, and a transversal
   * side-swap ({@link #corridorCrosses}).
   *
   * <p>Caller must pass FULL-resolution nodes: sampling breaks the node-identity
   * adjacency this grouping relies on.
   */
  static List<int[]> sharedCorridors(List<OsmPathElement> nodes) {
    List<int[]> out = new ArrayList<>();
    int n = nodes.size();
    if (n < 5) return out;
    double[] cum = LoopGeometry.cumulativeDistances(nodes);
    double perim = cum[n - 1];

    List<int[]> pairs = new ArrayList<>();
    Map<Long, int[]> first = new HashMap<>(n * 2);
    for (int k = 1; k < n - 1; k++) {
      long id = nodes.get(k).getIdFromPos();
      int[] prev = first.get(id);
      if (prev != null) {
        for (int k1 : prev) {
          if (k - k1 <= 1) continue;
          if (cum[k] <= CROSSING_START_END_EXEMPT_M || cum[k] >= perim - CROSSING_START_END_EXEMPT_M
            || cum[k1] <= CROSSING_START_END_EXEMPT_M) continue;
          pairs.add(new int[]{k1, k});
        }
        int[] grown = Arrays.copyOf(prev, prev.length + 1);
        grown[prev.length] = k;
        first.put(id, grown);
      } else {
        first.put(id, new int[]{k});
      }
    }
    if (pairs.isEmpty()) return out;
    Collections.sort(pairs, (x, y) -> Integer.compare(x[0], y[0]));

    List<int[]> run = new ArrayList<>();
    for (int i = 0; i <= pairs.size(); i++) {
      int[] p = i < pairs.size() ? pairs.get(i) : null;
      int[] last = run.isEmpty() ? null : run.get(run.size() - 1);
      if (p != null && (last == null
        || (p[0] - last[0] <= 2 && Math.abs(p[1] - last[1]) <= 2))) {
        run.add(p);
        continue;
      }
      if (run.size() >= 2) {
        int a1 = run.get(0)[0], a2 = run.get(run.size() - 1)[0];
        int b1 = Integer.MAX_VALUE, b2 = -1;
        for (int[] q : run) {
          b1 = Math.min(b1, q[1]);
          b2 = Math.max(b2, q[1]);
        }
        boolean sameDir = run.get(run.size() - 1)[1] > run.get(0)[1];
        double runLen = cum[a2] - cum[a1];
        boolean crossing = sameDir && runLen <= MAX_CORRIDOR_CROSS_M
          && corridorCrosses(nodes, a1, a2, b1, b2);
        out.add(new int[]{a1, a2, b1, b2, sameDir ? 1 : 0, crossing ? 1 : 0});
      }
      run = new ArrayList<>();
      if (p != null) run.add(p);
    }
    return out;
  }

  /**
   * Corridor-contracted transversality: does pass 2, riding the shared run from
   * {@code a1} to {@code a2}, cross pass 1's path? Since pass 2 rides exactly ON
   * pass 1's run, side-of-path propagates consistently, so the run contracts to
   * its two end nodes and pass 2 crosses iff its attachment falls in different
   * angular sectors at the two ends. The corridor analogue of
   * {@link #isTransverseRevisit}'s single-node test, with real node geometry (no
   * centroid approximation).
   */
  private static boolean corridorCrosses(List<OsmPathElement> nodes, int a1, int a2, int b1, int b2) {
    int n = nodes.size();
    if (a1 - 1 < 0 || a2 + 1 >= n || b1 - 1 < 0 || b2 + 1 >= n) return false;
    OsmPathElement e1 = nodes.get(a1), e2 = nodes.get(a2);
    OsmPathElement in1 = nodes.get(a1 - 1), out1 = nodes.get(a2 + 1);
    OsmPathElement in2 = nodes.get(b1 - 1), out2 = nodes.get(b2 + 1);
    OsmPathElement c1next = nodes.get(a1 + 1), c2prev = nodes.get(a2 - 1);
    // Shared approach/exit edge: the passes also share the edge OUTSIDE the
    // run on that side — an extended retrace shape, not a crossing through it.
    if (samePoint(in2, in1) || samePoint(out2, out1)) return false;
    // Degenerate zero-length rays cannot define a sector.
    if (samePoint(in1, e1) || samePoint(c1next, e1) || samePoint(in2, e1)
      || samePoint(out1, e2) || samePoint(c2prev, e2) || samePoint(out2, e2)) {
      return false;
    }
    boolean sideIn = angleInSector(
      CheapAngleMeter.getDirection(e1.getILon(), e1.getILat(), in2.getILon(), in2.getILat()),
      CheapAngleMeter.getDirection(e1.getILon(), e1.getILat(), c1next.getILon(), c1next.getILat()),
      CheapAngleMeter.getDirection(e1.getILon(), e1.getILat(), in1.getILon(), in1.getILat()));
    boolean sideOut = angleInSector(
      CheapAngleMeter.getDirection(e2.getILon(), e2.getILat(), out2.getILon(), out2.getILat()),
      CheapAngleMeter.getDirection(e2.getILon(), e2.getILat(), out1.getILon(), out1.getILat()),
      CheapAngleMeter.getDirection(e2.getILon(), e2.getILat(), c2prev.getILon(), c2prev.getILat()));
    return sideIn != sideOut;
  }

  /**
   * One crossing per qualifying same-direction run from {@link #sharedCorridors};
   * added into {@link #countSelfIntersections}. Pass FULL-resolution nodes.
   */
  public static int countCorridorCrossings(List<OsmPathElement> nodes) {
    int crossings = 0;
    for (int[] c : sharedCorridors(nodes)) {
      crossings += c[5];
      if (crossings >= MAX_SELF_INTERSECTIONS * 4) break;
    }
    return crossings;
  }

  private static boolean segmentsCross(OsmPathElement p1, OsmPathElement p2,
                                       OsmPathElement p3, OsmPathElement p4) {
    if (samePoint(p1, p3) || samePoint(p1, p4)
        || samePoint(p2, p3) || samePoint(p2, p4)) {
      return false;
    }
    long c1 = ccw(p1, p3, p4);
    long c2 = ccw(p2, p3, p4);
    long c3 = ccw(p1, p2, p3);
    long c4 = ccw(p1, p2, p4);
    return oppositeSigns(c1, c2) && oppositeSigns(c3, c4);
  }

  private static boolean samePoint(OsmPathElement a, OsmPathElement b) {
    return a.getILon() == b.getILon() && a.getILat() == b.getILat();
  }

  private static boolean oppositeSigns(long a, long b) {
    return (a > 0 && b < 0) || (a < 0 && b > 0);
  }

  private static long ccw(OsmPathElement a, OsmPathElement b, OsmPathElement c) {
    long dx1 = (long) b.getILon() - a.getILon();
    long dy1 = (long) b.getILat() - a.getILat();
    long dx2 = (long) c.getILon() - a.getILon();
    long dy2 = (long) c.getILat() - a.getILat();
    return dx1 * dy2 - dy1 * dx2;
  }

  static int countHairpinTurns(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 3) return 0;
    int count = 0;
    for (int i = 1; i < track.nodes.size() - 1; i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      OsmPathElement c = track.nodes.get(i + 1);
      if (a.calcDistance(b) < MIN_HAIRPIN_SEGMENT_METERS
          || b.calcDistance(c) < MIN_HAIRPIN_SEGMENT_METERS) {
        continue;
      }
      double d1 = CheapAngleMeter.getDirection(a.getILon(), a.getILat(), b.getILon(), b.getILat());
      double d2 = CheapAngleMeter.getDirection(b.getILon(), b.getILat(), c.getILon(), c.getILat());
      double delta = Math.abs(d2 - d1);
      if (delta > 180.0) delta = 360.0 - delta;
      if (delta > 130.0) {
        count++;
        if (count > MAX_HAIRPIN_TURNS) return count;
      }
    }
    return count;
  }

  // ---- Profile-hostility helpers (unchanged from prior implementation) -----

  /**
   * Walk track edges and reject on any of: a single unbroken hostile stretch
   * over {@link #MAX_CONTIGUOUS_HOSTILE_METERS} (the "sent down 2km of farm
   * track" complaint surface); total hostile share over
   * {@link #MAX_HOSTILE_FRACTION}; or suspect (missing-metadata) share over
   * {@link #MAX_HOSTILE_FRACTION}. Missing metadata is suspect, never proof of
   * quality, and breaks the contiguous-hostile run (reset to 0), so the worst
   * stretch is under-reported rather than spanned across unknown gaps (see
   * {@link #worstContiguousHostileMetersPaved}).
   */
  private static String checkHostileSegmentsPaved(OsmTrack track) {
    double total = 0;
    double hostile = 0;
    double suspect = 0;
    int worstContiguousHostile = worstContiguousHostileMetersPaved(track);

    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = a.calcDistance(b);
      total += segLen;

      MessageData m = b.message;
      if (m == null) {
        suspect += segLen;
        continue;
      }
      if (m.getWayKeyValues() == null) {
        // No tags to classify by. A router-confirmed expensive edge (cost above
        // the hostile threshold) is treated as hostile even without tags —
        // consistent with isHostileForPavedProfile's costfactor rule, which is
        // otherwise unreachable here because the null-tag edge would short-
        // circuit to suspect. A low-cost untagged edge stays genuinely
        // unverifiable (suspect). NOTE: this cost-based reclassification applies
        // to the fraction tally only; the contiguous-stretch metric stays purely
        // tag-based (see worstHostileStretchPaved) — the scorer's
        // worstContiguousMetersAboveCostfactor already covers cost-contiguity
        // during candidate selection.
        if (m.getCostfactor() > HOSTILE_COSTFACTOR_THRESHOLD) {
          hostile += segLen;
        } else {
          suspect += segLen;
        }
        continue;
      }
      if (isHostileForPavedProfile(m)) {
        hostile += segLen;
      }
    }

    if (total <= 0) return null;

    // Contiguous-hostile check FIRST: a single long off-road stretch is the
    // physical-experience complaint the cyclist will surface, regardless
    // of how many paved kilometres came before or after.
    if (worstContiguousHostile > MAX_CONTIGUOUS_HOSTILE_METERS) {
      return String.format(Locale.US,
        "contiguous %dm of profile-hostile way (max %dm) — single off-road stretch too long for road bike",
        worstContiguousHostile, MAX_CONTIGUOUS_HOSTILE_METERS);
    }

    double hostileFrac = hostile / total;
    if (hostileFrac > MAX_HOSTILE_FRACTION) {
      return String.format(Locale.US,
        "%.0f%% of distance on profile-hostile ways (max %.0f%%) — route uses path/track/unpaved that a road bike should avoid",
        hostileFrac * 100.0, MAX_HOSTILE_FRACTION * 100.0);
    }

    // Missing metadata is allowed in small doses (router fallbacks for
    // corrupt edges happen) but a paved-profile route mostly on edges we
    // can't verify is not safe to ship.
    double suspectFrac = suspect / total;
    if (suspectFrac > MAX_HOSTILE_FRACTION) {
      return String.format(Locale.US,
        "%.0f%% of distance on edges with missing/unknown metadata — cannot verify paved-ness for road-bike profile",
        suspectFrac * 100.0);
    }

    // Combined backstop: neither bucket alone crossed its ceiling, but their
    // sum (confirmed-hostile + unverifiable) is too high a share of
    // non-confirmed-paved surface to ship to a road-bike rider.
    double questionableFrac = (hostile + suspect) / total;
    if (questionableFrac > MAX_QUESTIONABLE_FRACTION) {
      return String.format(Locale.US,
        "%.0f%% of distance on profile-hostile or unverifiable surface (max %.0f%%) — too much non-confirmed-paved surface for a road-bike profile",
        questionableFrac * 100.0, MAX_QUESTIONABLE_FRACTION * 100.0);
    }

    return null;
  }

  /**
   * Longest unbroken run of paved-profile-hostile edges in meters — the same
   * predicate {@link #checkHostileSegmentsPaved} uses for the contiguous-stretch
   * ceiling, exposed so the candidate scorer can prefer routes staying well under
   * {@link #MAX_CONTIGUOUS_HOSTILE_METERS}. Suspect (missing-tag) edges break the
   * streak (conservative under-report). Returns 0 for an empty/single-node track.
   */
  static int worstContiguousHostileMetersPaved(OsmTrack track) {
    return worstHostileStretchPaved(track).meters;
  }

  /**
   * Scorer-side approximation of {@link #worstContiguousHostileMetersPaved} for
   * single-pass tracks, where {@code MessageData.wayKeyValues} is null and the
   * tag-based check returns false (so the precise metric always returns 0 and the
   * scorer's contiguous-hostility penalty never fires). Bypasses the null-tags
   * guard and uses ONLY per-edge {@code costfactor} at the lower
   * {@link #SCORER_HOSTILE_COSTFACTOR_THRESHOLD} (3.0 vs the gate's
   * {@link #HOSTILE_COSTFACTOR_THRESHOLD} of 4.0) to catch hostile-by-tag edges
   * in the 2-4 fastbike cost band. Ranking only — gate enforcement must keep
   * using the tag-aware {@link #worstContiguousHostileMetersPaved}.
   *
   * @return longest unbroken meters with costfactor &gt;
   *         {@link #SCORER_HOSTILE_COSTFACTOR_THRESHOLD}; 0 without MessageData.
   */
  static int worstContiguousCostlyMetersForScorer(OsmTrack track) {
    return worstContiguousMetersAboveCostfactor(track, SCORER_HOSTILE_COSTFACTOR_THRESHOLD, null);
  }

  /**
   * As {@link #worstContiguousCostlyMetersForScorer(OsmTrack)} but with
   * caller-precomputed per-segment distances ({@code segLens[i-1]} = distance
   * from node i-1 to i). Must match {@code calcDistance}, so the result is
   * bit-identical.
   */
  static int worstContiguousCostlyMetersForScorer(OsmTrack track, int[] segLens) {
    return worstContiguousMetersAboveCostfactor(track, SCORER_HOSTILE_COSTFACTOR_THRESHOLD, segLens);
  }

  /**
   * Costfactor-only worst-contiguous-stretch finder. Package-private for testing
   * different thresholds; production uses
   * {@link #worstContiguousCostlyMetersForScorer}.
   */
  static int worstContiguousMetersAboveCostfactor(OsmTrack track, double threshold) {
    return worstContiguousMetersAboveCostfactor(track, threshold, null);
  }

  /**
   * @param segLens precomputed per-segment distances ({@code segLens[i-1]} =
   *                distance from node i-1 to i), or {@code null} to compute inline
   *                (must match {@code calcDistance} to reproduce the scan exactly).
   */
  static int worstContiguousMetersAboveCostfactor(OsmTrack track, double threshold, int[] segLens) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) return 0;
    int best = 0;
    double current = 0;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement b = track.nodes.get(i);
      double segLen = (segLens != null) ? segLens[i - 1] : track.nodes.get(i - 1).calcDistance(b);
      MessageData m = b.message;
      if (m == null) {
        current = 0;
        continue;
      }
      if (m.getCostfactor() > threshold) {
        current += segLen;
        int meters = (int) current;
        if (meters > best) best = meters;
      } else {
        current = 0;
      }
    }
    return best;
  }

  /**
   * Details (coordinates, tags) for the longest unbroken hostile run on a paved
   * profile. Diagnostic-only — validation uses
   * {@link #worstContiguousHostileMetersPaved(OsmTrack)}; tests and planner
   * instrumentation need the coordinates to tell unavoidable terrain from
   * planner-induced bad choices.
   */
  static HostileStretch worstHostileStretchPaved(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) return HostileStretch.NONE;
    HostileStretch best = HostileStretch.NONE;
    double current = 0;
    int currentStartIndex = -1;
    int currentStartIlon = 0;
    int currentStartIlat = 0;
    String currentStartTags = null;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = a.calcDistance(b);
      MessageData m = b.message;
      if (m == null || m.getWayKeyValues() == null) {
        current = 0; // suspect breaks the run
        currentStartIndex = -1;
        continue;
      }
      if (isHostileForPavedProfile(m)) {
        if (current == 0) {
          currentStartIndex = i - 1;
          currentStartIlon = a.getILon();
          currentStartIlat = a.getILat();
          currentStartTags = m.getWayKeyValues();
        }
        current += segLen;
        int meters = (int) current;
        if (meters > best.meters) {
          best = new HostileStretch(meters,
            currentStartIndex, i,
            currentStartIlon, currentStartIlat,
            b.getILon(), b.getILat(),
            currentStartTags, m.getWayKeyValues());
        }
      } else {
        current = 0;
        currentStartIndex = -1;
      }
    }
    return best;
  }

  static final class HostileStretch {
    static final HostileStretch NONE = new HostileStretch(
      0, -1, -1, 0, 0, 0, 0, null, null);

    final int meters;
    final int startIndex;
    final int endIndex;
    final int startIlon;
    final int startIlat;
    final int endIlon;
    final int endIlat;
    final String startTags;
    final String endTags;

    private HostileStretch(int meters, int startIndex, int endIndex,
                           int startIlon, int startIlat,
                           int endIlon, int endIlat,
                           String startTags, String endTags) {
      this.meters = meters;
      this.startIndex = startIndex;
      this.endIndex = endIndex;
      this.startIlon = startIlon;
      this.startIlat = startIlat;
      this.endIlon = endIlon;
      this.endIlat = endIlat;
      this.startTags = startTags;
      this.endTags = endTags;
    }

    boolean isPresent() {
      return meters > 0;
    }

    double startLon() {
      return (startIlon - 180000000) / 1000000.0;
    }

    double startLat() {
      return (startIlat - 90000000) / 1000000.0;
    }

    double endLon() {
      return (endIlon - 180000000) / 1000000.0;
    }

    double endLat() {
      return (endIlat - 90000000) / 1000000.0;
    }

    String describe() {
      if (!isPresent()) return "none";
      return String.format(Locale.US,
        "%dm [%d..%d] %.6f,%.6f -> %.6f,%.6f tags=%s -> %s",
        meters, startIndex, endIndex,
        startLat(), startLon(), endLat(), endLon(),
        trimTags(startTags), trimTags(endTags));
    }

    private static String trimTags(String tags) {
      if (tags == null) return "null";
      return tags.length() <= 160 ? tags : tags.substring(0, 157) + "...";
    }
  }

  /**
   * Fraction of route distance whose target node lacks source way metadata —
   * suspect for paved-profile validation, which can't prove asphalt vs track vs
   * ferry vs direct fallback. Exposed so a committed greedy graph-native leg can
   * verify its metadata retracking succeeded before joining the mutable loop
   * state.
   */
  static double missingMetadataFraction(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) return 0.0;
    double total = 0.0;
    double missing = 0.0;
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1);
      OsmPathElement b = track.nodes.get(i);
      double segLen = a.calcDistance(b);
      total += segLen;
      MessageData m = b.message;
      if (m == null || m.getWayKeyValues() == null) {
        missing += segLen;
      }
    }
    return total > 0.0 ? missing / total : 0.0;
  }

  static boolean isHostileForPavedProfile(MessageData m) {
    String tags = m.getWayKeyValues();
    if (tags != null) {
      if (isRoadBikeSuitablePavedTrack(tags)) return false;
      // A soft highway (path/footway/bridleway) carrying an explicit hard
      // surface is paved cycleway infrastructure, rideable on a road bike — even
      // when the cost function scores the surface as "unpaved" and pushes the
      // costfactor over the threshold (fastbike treats surface=pebblestone like
      // gravel, so a cobbled cycleway lands at costfactor ~15). The explicit
      // surface tag is the more reliable rideability signal, so honour it BEFORE
      // the costfactor check, which would otherwise reject it as hostile.
      //
      // The cost is intentionally NOT bounded here — cobbled cycleways are a
      // deliberately high-cost-but-rideable case, and the cost function still
      // penalises them for comfort. But a poor tracktype (grade2-5) is a
      // stronger surface-quality signal than the surface tag and overrides it
      // (e.g. a broken-asphalt grade3 forest path), exactly as the sibling
      // isRoadBikeSuitablePavedTrack guards — so a rough-graded path does not
      // get the rideability pass even if it carries an incidental hard surface.
      if (hasSoftOverridableHighway(tags) && hasHardSurface(tags)
          && !hasExplicitBicycleRestriction(tags) && !hasPoorTracktype(tags)) {
        return false;
      }
    }
    if (m.getCostfactor() > HOSTILE_COSTFACTOR_THRESHOLD) return true;
    if (tags == null) return false;
    for (String fragment : PAVED_PROFILE_HOSTILE_TAG_FRAGMENTS) {
      if (tags.contains(fragment)) {
        // Soft-highway fragments (path/footway/bridleway) are overridden
        // when the surface is hard: a path tagged surface=asphalt is paved
        // cycleway infrastructure, rideable on a road bike. Tracks /
        // steps / surface=gravel and friends are NOT overridable. A poor
        // tracktype (grade2-5) vetoes the override here too, symmetric with the
        // pre-costfactor override above and the track case — so a rough-graded
        // path stays hostile regardless of whether it lands on this low-cost
        // branch or the costfactor branch.
        if (isOverridableHostileTag(fragment) && hasHardSurface(tags)
            && !hasPoorTracktype(tags)) {
          continue;
        }
        return true;
      }
    }
    return false;
  }

  private static boolean isOverridableHostileTag(String fragment) {
    for (String s : SOFT_HIGHWAY_OVERRIDABLE) {
      if (s.equals(fragment)) return true;
    }
    return false;
  }

  private static boolean hasSoftOverridableHighway(String tags) {
    for (String s : SOFT_HIGHWAY_OVERRIDABLE) {
      if (tags.contains(s)) return true;
    }
    return false;
  }

  /**
   * Whether the way has a poor tracktype (grade2-5) — a stronger surface-quality
   * signal than the surface tag, used to veto the "hard surface ⇒ rideable"
   * overrides for both tracks and soft highways.
   */
  private static boolean hasPoorTracktype(String tags) {
    return tags.contains("tracktype=grade2")
      || tags.contains("tracktype=grade3")
      || tags.contains("tracktype=grade4")
      || tags.contains("tracktype=grade5");
  }

  private static boolean hasHardSurface(String tags) {
    for (String s : PAVED_PROFILE_HARD_SURFACE_FRAGMENTS) {
      if (tags.contains(s)) return true;
    }
    return false;
  }

  /**
   * Whether the way is a road-bike-suitable {@code highway=track}. Any one
   * cascade suffices (all require no explicit bicycle restriction):
   * <ol>
   *   <li>hard surface alone ({@code asphalt|paved|paving_stones|concrete|
   *       chipseal}) — asphalt is rideable regardless of tracktype;</li>
   *   <li>{@code tracktype=grade1} + on a cycle network (grade1 implies a hard
   *       surface; the network tag is curated evidence of rideability);</li>
   *   <li>grade1 + explicit hard surface — the original strict case.</li>
   * </ol>
   * Evidence: 1032 cyclist-curated GPX routes replayed point-to-point (Basel +
   * Mallorca + Innsbruck + Freiburg), 95.9% pass; residual failures are genuine
   * gravel or the grade1-no-surface / asphalt-no-grade patterns this accepts.
   */
  private static boolean isRoadBikeSuitablePavedTrack(String tags) {
    if (!tags.contains("highway=track")) return false;
    if (hasExplicitBicycleRestriction(tags)) return false;
    boolean grade1 = tags.contains("tracktype=grade1");
    // tracktype=grade2|3|4|5 is a more-specific signal of poor riding
    // surface that overrides the surface tag (e.g. broken asphalt on a
    // grade2 forest road). Don't activate the cascade if those are set.
    if (hasPoorTracktype(tags)) return false;
    boolean hardSurface = hasHardSurface(tags);
    boolean cycleNetwork = hasCycleNetworkTag(tags);
    return hardSurface
      || (grade1 && cycleNetwork);
  }

  /**
   * Whether the way is on an OSM cycle network — curated evidence of
   * rideability, since those networks aren't routed over unpaved unless the
   * unpaved section is explicitly surface-tagged.
   */
  private static boolean hasCycleNetworkTag(String tags) {
    return tags.contains("route_bicycle_lcn=yes")
      || tags.contains("route_bicycle_rcn=yes")
      || tags.contains("route_bicycle_ncn=yes")
      || tags.contains("route_bicycle_icn=yes");
  }

  /**
   * Explicit access denials overriding "default permissive" tagging:
   * {@code bicycle=no}, {@code access=private}, {@code access=no}.
   * {@code bicycle=dismount} is NOT treated as a restriction — a surface hint
   * the cost function already handles, not a legal denial.
   */
  private static boolean hasExplicitBicycleRestriction(String tags) {
    return hasTag(tags, "bicycle=no")
      || hasTag(tags, "access=private")
      || hasTag(tags, "access=no");
  }

  /**
   * Whether {@code keyValue} appears as a whole token in {@code tags} (a
   * space-joined {@code key=value} list). Token-boundary matching avoids the
   * substring trap where {@code contains("bicycle=no")} also matches the
   * cyclist-friendly {@code oneway:bicycle=no} (the opposite of a ban).
   */
  static boolean hasTag(String tags, String keyValue) {
    if (tags == null) return false;
    int from = 0;
    while (true) {
      int idx = tags.indexOf(keyValue, from);
      if (idx < 0) return false;
      boolean leftBoundary = idx == 0 || tags.charAt(idx - 1) == ' ';
      int end = idx + keyValue.length();
      boolean rightBoundary = end == tags.length() || tags.charAt(end) == ' ';
      if (leftBoundary && rightBoundary) return true;
      from = idx + 1;
    }
  }

  /**
   * Lower bound on cf(grade3 gravel track) / cf(paved residential) at/above which
   * a profile is paved-only — treats loose unpaved as off-limits, so a round-trip
   * routed onto it is rejected. Vehicles that can't ride loose unpaved sit well
   * above 5.0 (fastbike 8.3, velomobil 23.5, car-vario 10000); unpaved-tolerant
   * bikes below (trekking 2.65, gravel 0.79, mtb 0.57). The ratio, not the
   * absolute cost, discriminates: mtb penalises unpaved heavily (abs 7.5) but
   * paved harder (13.6), giving 0.55.
   */
  static final double PAVED_PROBE_RATIO = 5.0;

  /**
   * Classify a profile as paved/road-bike by what its cost model charges for an
   * unpaved way, independent of name. Pure probe with no shared state — callers
   * run it once at request entry, while the way-expression context is available,
   * and store the verdict on their request state (e.g.
   * {@code RoundTripRequest.pavedProfile}).
   *
   * <p>Resolution order: (1) explicit author override {@code roadbikeSurfaceGate}
   * ({@code 1}=paved-only, {@code 0}=not); (2) cost-model probe
   * {@code cf(gravel track)/cf(paved residential)} >= {@link #PAVED_PROBE_RATIO}.
   * With no way context (isolated unit tests only; production always parses a
   * profile) the profile is treated as not-paved, so the hostile-surface gate is
   * simply not imposed.
   */
  public static boolean classifyPavedProfile(BExpressionContextWay expctxWay) {
    if (expctxWay == null) {
      return false;
    }
    float override = expctxWay.getVariableValue("roadbikeSurfaceGate", -1f);
    if (override >= 0f) {
      return override >= 0.5f;
    }
    return probePavedFromCostModel(expctxWay);
  }

  /**
   * Probe the profile's surface policy: paved-only iff a loose-unpaved way
   * (grade3 gravel track) is far costlier than a paved residential road
   * ({@code cf} ratio &gt;= {@link #PAVED_PROBE_RATIO}). Returns false if the
   * context can't be probed. The ratio is invariant to a global scaling of the
   * profile's costfactors. grade3 is the probe point because grade1 doesn't
   * discriminate — a road bike rides grade1 cheaply but skating/moped/car
   * penalise even grade1, so "rides grade1 cheaply" is not a universal paved-only
   * trait; a profile the probe misjudges can set the {@code roadbikeSurfaceGate}
   * override.
   */
  static boolean probePavedFromCostModel(BExpressionContextWay expctxWay) {
    float unpaved = wayCostFactor(expctxWay, "highway=track", "tracktype=grade3", "surface=gravel");
    float paved = wayCostFactor(expctxWay, "highway=residential", "surface=asphalt");
    if (Float.isNaN(unpaved) || Float.isNaN(paved)) {
      return false;
    }
    // costfactor is always >= 1.0, but guard the divide defensively.
    double ratio = unpaved / Math.max(paved, 1.0f);
    return ratio >= PAVED_PROBE_RATIO;
  }

  /** Evaluate the profile's costfactor for a synthetic way described by tags. */
  private static float wayCostFactor(BExpressionContextWay expctxWay, String... tags) {
    int[] lookupData = expctxWay.createNewLookupData();
    if (lookupData == null) {
      return Float.NaN; // lookup table not frozen / context unusable
    }
    for (String tag : tags) {
      int i = tag.indexOf('=');
      if (i > 0) {
        expctxWay.addLookupValue(tag.substring(0, i), tag.substring(i + 1), lookupData);
      }
    }
    byte[] description = expctxWay.encode(lookupData);
    expctxWay.evaluate(false, description); // forward direction
    return expctxWay.getCostfactor();
  }
}
