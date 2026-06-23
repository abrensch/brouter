package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import btools.expressions.BExpressionContextWay;
import btools.mapaccess.MatchedWaypoint;
import btools.util.CheapAngleMeter;

/**
 * Production-safety acceptance gate for round-trip routes.
 *
 * <p>A generated round-trip is unsafe to ship to a cyclist if any of the
 * following hard checks fails:
 * <ul>
 *   <li>a synthetic beeline segment (the engine inserted a straight line
 *       across un-routable terrain);</li>
 *   <li>a ferry segment unless the request explicitly allowed ferries;</li>
 *   <li>chaotic geometry with many self-crossings or hairpin reversals;</li>
 *   <li>a closure gap larger than {@link #MAX_CLOSURE_METERS} (the route
 *       did not actually return to the origin);</li>
 *   <li>a distance ratio outside {@code [MIN_DISTANCE_RATIO,
 *       MAX_DISTANCE_RATIO]} (the route is much shorter or much longer
 *       than the cyclist asked for);</li>
 *   <li>too few nodes to be a real loop;</li>
 *   <li>for a paved-only profile (fastbike/road), a significant share of
 *       distance on path/track/footway/unpaved surfaces — the cyclist
 *       cannot safely ride those on a road bike.</li>
 * </ul>
 *
 * <p>The paved-profile hostility check is surface-aware: a soft highway
 * tag ({@code highway=path}, {@code highway=footway}, {@code highway=bridleway})
 * combined with a hard {@code surface=} tag ({@code asphalt}, {@code paved},
 * {@code paving_stones}, {@code concrete}, {@code chipseal}) is treated as
 * paved cycleway infrastructure, NOT as off-road. OSM data routinely uses
 * {@code highway=path} for paved cycleways, and the {@code surface=} tag is
 * the more reliable signal. {@code highway=steps} stays hostile regardless of
 * surface. {@code highway=track} is hostile <em>unless</em> it carries an
 * explicit hard surface (and is not {@code tracktype=grade2..5}), or is a
 * {@code tracktype=grade1} track on an OSM cycle network — see
 * {@link #isRoadBikeSuitablePavedTrack(String)}.
 *
 * <p>On top of those hard checks the gate runs a <em>semantic reuse
 * classifier</em> ({@link ReuseClassifier}) that distinguishes:
 * <ul>
 *   <li><b>STRICT_LOOP</b> — a clean loop. Accepted.</li>
 *   <li><b>LOLLIPOP</b> — a loop with a retraced terminal spur (e.g. a
 *       scenic spur to a cape, climb to a pass, valley with one access
 *       road). Accepted with a disclosure.</li>
 *   <li><b>OUT_AND_BACK</b> — essentially out-and-back along the
 *       same road. Accepted only when {@code allowSamewayback=true}.</li>
 *   <li><b>INVALID_RETRACE</b> — accidental backtracking in the middle of
 *       a loop. Always rejected.</li>
 * </ul>
 *
 * <p>This is a HARD gate applied uniformly to all round-trip algorithms
 * (WAYPOINT, ISOCHRONE, GREEDY, ISO_GREEDY). A failing check sets
 * {@code foundTrack=null} and surfaces the rejection reason; the
 * algorithm-internal "best effort" must NOT silently downgrade to a
 * surprising route.
 *
 * <p>The thresholds intentionally err toward rejection: a clearly-bad
 * route is far worse user experience than "no route available, try a
 * different start or distance". <em>But</em> the classifier explicitly
 * preserves iconic forced-spur routes (mountain pass, cape, dead-end
 * valley) by recognising their structural signature rather than treating
 * all retracing as failure.
 */
public final class RoundTripQualityGate {

  /** Minimum acceptable {@code actualDistance / desiredDistance}. */
  public static final double MIN_DISTANCE_RATIO = 0.5;
  /** Maximum acceptable {@code actualDistance / desiredDistance}. */
  public static final double MAX_DISTANCE_RATIO = 1.8;
  /** Maximum acceptable gap between the route's start and end points. */
  public static final int MAX_CLOSURE_METERS = 400;
  /** Minimum acceptable node count for a real loop (start + intermediate + close ≥ 4). */
  public static final int MIN_NODES = 4;
  /**
   * Maximum self-crossings allowed before a route is considered chaotic.
   *
   * <p>Calibrated against the mallorca-75km probe-baseline (18 self-
   * crossings, clearly chaotic) vs iso_greedy-baseline (3, clean). The
   * {@code <= 5} cap matches the AUTO competition's quality bar in
   * {@code RoutingEngineAutoCompetitionTest.mallorca75kmFastbikeAutoBeatsProbeSpike}.
   *
   * <p>A briefly-tried relaxation to {@code 15} (during Phase 2 v3) was
   * reverted on user direction: empirically the cyclist-acceptable
   * geometry is much tighter than "fewer than 15 self-crossings", and
   * routes with 6-10 incidental crossings tend to be the chaotic
   * planner outputs the gate should keep rejecting. The shape-aware
   * variants (greedy / iso_greedy) routinely produce 0-3 crossings;
   * routes that exceed 5 typically indicate a planner choosing through
   * a dense road grid in a way that looks like spaghetti on the map.
   */
  public static final int MAX_SELF_INTERSECTIONS = 5;
  /** Maximum hairpin-like turns allowed before a route is considered chaotic. */
  public static final int MAX_HAIRPIN_TURNS = 20;
  /**
   * Node cap above which the self-intersection scan falls back to stride
   * decimation. Set high enough that it is only ever a degenerate-input guard:
   * a 100km loop is ~3300 nodes, 250km ~8000, and the O(n²) scan with the
   * {@link #countSelfIntersections} early-exit ceiling runs sub-second at this
   * size. Decimation is NOT shape-preserving — stride sampling replaces curved
   * sub-paths with straight chords, fabricating crossings on dense switchback
   * tracks (gate=21 where the full-resolution count is 0; measured on the
   * alpine/coastal 100km loops). It under-counts on other geometries. So the
   * scan must run at full node resolution for any realistic loop and only
   * decimate as a last-resort cost guard on pathological input.
   */
  private static final int MAX_SHAPE_SCAN_NODES = 10000;
  /** Ignore tiny digitization jitter when counting U-turns. */
  private static final int MIN_HAIRPIN_SEGMENT_METERS = 25;

  /**
   * Maximum acceptable fraction of distance on profile-hostile edges. For a
   * paved profile, hostile means {@code highway=path|track|footway|...} or
   * a high-cost spike. 10% is intentionally tight: 10% of a 50km loop is
   * 5km of dirt/path the cyclist would hit unexpectedly.
   *
   * <p>The total-fraction check is complemented by
   * {@link #MAX_CONTIGUOUS_HOSTILE_METERS} which bounds the longest
   * unbroken hostile stretch — a cyclist's complaint surface is "I was
   * sent down 2 km of farm track" rather than "12 % of my route was mixed
   * surface in 200 m bursts", and the contiguous-stretch length is the
   * physical experience metric.
   *
   * <p><b>Phase 3 calibration (kept at 0.10).</b> The gate-tuning spec asked
   * whether the post-Phase-1 fraction check could be slackened in favour of
   * the contiguous-stretch check alone. We sampled the rejections after
   * Phase 1 landed and found only four scenarios where total-fraction was
   * the sole trip wire (every other rejection was already caught by the
   * contiguous-stretch sub-cap or hard cost spikes). Slackening the
   * fraction would have admitted those four — all with hostile mileage
   * distributed in many small bursts on an otherwise paved loop — which is
   * exactly the "death by a thousand cuts" pattern Phase 1's contiguous
   * cap can't see. So the constant stays at 0.10 by deliberate choice, not
   * inheritance.
   */
  public static final double MAX_HOSTILE_FRACTION = 0.10;

  /**
   * Maximum combined share of distance that is either confirmed-hostile OR
   * unverifiable (missing/unknown metadata). The hostile and suspect fractions
   * each have their own {@link #MAX_HOSTILE_FRACTION} ceiling, but a route that
   * sits just under both (e.g. 9% hostile + 9% suspect = 18% non-confirmed-paved)
   * would otherwise pass while delivering far more questionable surface than a
   * road-bike rider should get. This ceiling is the aggregate backstop; it sits
   * above each individual ceiling so the more specific single-bucket messages
   * fire first.
   */
  public static final double MAX_QUESTIONABLE_FRACTION = 0.15;

  /**
   * Maximum length of a single unbroken hostile stretch on a paved
   * profile, regardless of total share. Routes pass the total-fraction
   * check but trip this one when they contain one long off-road section
   * — a 1.5 km farm-track detour mid-loop is the kind of surprise
   * cyclists complain about even if the rest of the loop is asphalt.
   */
  public static final int MAX_CONTIGUOUS_HOSTILE_METERS = 1500;

  /**
   * Costfactor above which a single edge is considered profile-hostile,
   * independent of its tags. Paved profiles return {@code costfactor=1.0}
   * for preferred ways (residential/cycleway/tertiary) and >4 only when
   * the way is something the profile actively avoids (track grade5,
   * unpaved primary, etc.).
   */
  public static final double HOSTILE_COSTFACTOR_THRESHOLD = 4.0;

  /**
   * Lower costfactor threshold used by the scorer-side approximation
   * {@link #worstContiguousCostlyMetersForScorer}. The scorer cannot use
   * tag-based hostility detection on single-pass tracks (wayKeyValues is
   * null), so it compensates by flagging edges with moderately elevated
   * costfactor — many hostile-by-tag edges sit in the 2-4 costfactor
   * band under the fastbike profile, below the gate's hard threshold but
   * already indicating "the profile is paying a real penalty here".
   *
   * <p>Calibrated to: (a) not flag quiet rural roads (~1.0-2.0) and
   * (b) catch tracks/paths whose costfactor isn't quite at 4.0 but tags
   * would have flagged them on a detailed track.
   */
  public static final double SCORER_HOSTILE_COSTFACTOR_THRESHOLD = 3.0;

  /**
   * Way-tag fragments that signal profile-hostile terrain for a paved
   * profile. We match by substring against {@code MessageData.wayKeyValues}
   * because the cost lookup may not have populated {@code costfactor} for
   * every edge (e.g. data-error fallbacks).
   *
   * <p>Rough but rideable paved surfaces — {@code surface=cobblestone},
   * {@code surface=pebblestone} — are deliberately <em>not</em> listed
   * here: uncomfortable on narrow tyres, fine to ride on a road bike,
   * and present in real cycle networks (German pedestrian zones, Belgian
   * cycle paths). Their inclusion in earlier versions of this list was
   * triggering false-positive rejections in suburban regions.
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
   * for paved profiles. A {@code highway=path} edge with one of these surface
   * tags is paved infrastructure (typical: rural cycleways tagged generically
   * as {@code path}/{@code footway}/{@code bridleway}) and IS rideable on a
   * road bike. The OSM tagging convention is inconsistent here — cycleways
   * get tagged as {@code path} or {@code footway} more often than they
   * should, and the {@code surface=} tag is the more reliable signal for
   * rideability.
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
   * Highway= fragments whose hostility may be overridden by a
   * {@link #PAVED_PROFILE_HARD_SURFACE_FRAGMENTS} surface. Subset of
   * {@link #PAVED_PROFILE_HOSTILE_TAG_FRAGMENTS} that is commonly mis-tagged
   * to mean "narrow paved cycleway" rather than "off-road". Plain
   * {@code highway=track} is handled by
   * {@link #isRoadBikeSuitablePavedTrack(String)} because it needs stronger
   * evidence than just {@code surface=asphalt}; {@code highway=steps} cannot
   * be rideable regardless of surface.
   */
  private static final String[] SOFT_HIGHWAY_OVERRIDABLE = {
    "highway=path",
    "highway=footway",
    "highway=bridleway",
  };

  private RoundTripQualityGate() { /* static-only */ }

  // ---- Legacy String API (kept for callers that just need a reject reason) -

  /**
   * Convenience overload: assumes loop-style routes (no deliberate retracing).
   * Use {@link #validate(OsmTrack, double, String, boolean)} to relax the
   * reuse and ratio checks for same-way-back / out-and-back routes.
   */
  public static String validate(OsmTrack track, double desiredDistance, String profileName) {
    return validate(track, desiredDistance, profileName, false);
  }

  /**
   * Validate a generated round-trip track. Returns {@code null} when the
   * track is acceptable; a human-readable rejection reason otherwise.
   * Equivalent to {@link #evaluate} but returning only the rejection
   * string for callers that don't need the structured verdict.
   *
   * <p>{@code allowSamewayback=true} signals the cyclist explicitly asked
   * for an out-and-back; the gate then permits the
   * {@link RouteShape#OUT_AND_BACK} shape.
   */
  public static String validate(OsmTrack track, double desiredDistance, String profileName,
                                  boolean allowSamewayback) {
    RoundTripQualityResult r = evaluate(track, desiredDistance, profileName, allowSamewayback, false);
    return r.isAccepted() ? null : r.getRejectionReason();
  }

  // ---- Structured API ------------------------------------------------------

  /**
   * Run all hard checks plus the semantic reuse classifier and return a
   * structured verdict. Callers that want the route shape, disclosures, and
   * specific reuse measurements (stem length, scenic spur length, max
   * contiguous retrace) should use this entry point.
   *
   * <p>Order of evaluation:
   * <ol>
   *   <li>Null / too-few-nodes / closure / distance ratio — fast structural
   *       checks. Failures here always produce {@link RouteShape#INVALID_RETRACE}
   *       with a descriptive reason; the route never reached the loop
   *       classifier because it doesn't even resemble a loop.</li>
   *   <li>Beeline/ferry markers — synthetic gap and disallowed ferry detection.</li>
   *   <li>Shape chaos — self-crossings and excessive hairpin turns.</li>
   *   <li>Profile-hostility — for paved profiles, reject path/track-heavy
   *       routes. This gate applies <em>regardless</em> of route shape:
   *       a scenic spur on a fastbike profile that's mostly track is still
   *       a bad route, no matter how iconic.</li>
   *   <li>{@link ReuseClassifier#classify Semantic reuse classification} —
   *       decides STRICT_LOOP vs LOLLIPOP vs OUT_AND_BACK vs
   *       INVALID_RETRACE and produces the final accept/reject verdict.</li>
   * </ol>
   *
   * <p>This three-arg overload is for auto-generated round-trip mode; see
   * the four-arg overload for explicit-via-mode semantics.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                String profileName, boolean allowSamewayback) {
    return evaluate(track, desiredDistance, profileName, allowSamewayback, false);
  }

  /**
   * Same as the four-arg {@link #evaluate} above but with an explicit-via
   * mode flag. When {@code explicitViaMode} is true:
   * <ul>
   *   <li>distance-ratio mismatch is downgraded from rejection to a soft
   *       disclosure — the user-supplied via skeleton defines the route,
   *       not the {@code roundTripDistance} target;</li>
   *   <li>the semantic reuse classification stays informational; an
   *       INVALID_RETRACE verdict on an explicit-via route only attaches
   *       a disclosure rather than failing acceptance — the user picked
   *       the vias, the engine just connects them.</li>
   * </ul>
   * Hard safety checks (closure, beeline, profile-hostility) remain
   * active in explicit-via mode.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                String profileName, boolean allowSamewayback,
                                                boolean explicitViaMode) {
    return evaluate(track, desiredDistance, profileName, allowSamewayback, explicitViaMode, false);
  }

  /**
   * Full structured gate with explicit ferry opt-in. Generated loops call this
   * overload so {@code route=ferry}/{@code ferry=*} tags are hard failures by
   * default, while ferry-aware callers can deliberately allow them.
   */
  public static RoundTripQualityResult evaluate(OsmTrack track, double desiredDistance,
                                                String profileName, boolean allowSamewayback,
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
    List<MatchedWaypoint> mwps = track.matchedWaypoints;
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
    String chaos = checkShapeChaos(track);
    if (chaos != null) {
      RoundTripQualityResult.RejectionTier tier =
        countSelfIntersections(track) > 2 * MAX_SELF_INTERSECTIONS
          ? RoundTripQualityResult.RejectionTier.STRUCTURAL
          : RoundTripQualityResult.RejectionTier.QUALITY;
      return RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .reject(tier, chaos)
        .build();
    }

    // 6. Profile-hostile segments: enforced for paved-only profiles even
    //    on scenic spurs. A LOLLIPOP through singletrack on a road bike
    //    is still bad — the scenic-spur exception is for the shape of the
    //    retracing, not for the surface compatibility.
    if (isPavedProfile(profileName)) {
      String hostile = checkHostileSegmentsPaved(track);
      if (hostile != null) {
        return RoundTripQualityResult.builder()
          .shape(RouteShape.INVALID_RETRACE)
          .reject(RoundTripQualityResult.RejectionTier.QUALITY, hostile)
          .build();
      }
    }

    // 7. Semantic reuse classification — the heart of this gate.
    RoundTripQualityResult classified = ReuseClassifier.classify(
      track, desiredDistance, allowSamewayback);

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
      .scenicSpurReuseMeters(classified.getScenicSpurReuseMeters());
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
      if (m == null || m.wayKeyValues == null) continue;
      String tags = m.wayKeyValues;
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

  private static String checkShapeChaos(OsmTrack track) {
    int selfIntersections = countSelfIntersections(track);
    if (selfIntersections > MAX_SELF_INTERSECTIONS) {
      return "route has " + selfIntersections + " self-intersections (max "
        + MAX_SELF_INTERSECTIONS + ") — chaotic loop geometry";
    }

    int hairpins = countHairpinTurns(track);
    if (hairpins > MAX_HAIRPIN_TURNS) {
      return "route has " + hairpins + " hairpin turns (max "
        + MAX_HAIRPIN_TURNS + ") — chaotic loop geometry";
    }
    return null;
  }

  /**
   * Crossings whose segments lie within this arc distance of the route start
   * or end are NOT counted (user labeling, 2026-06-11): leaving and returning
   * through the same home neighborhood crosses the outbound path by
   * construction — expected, not a defect.
   */
  static final double CROSSING_START_END_EXEMPT_M = 500;

  /**
   * Vertical-separation exemption (user labeling, 2026-06-11): a geometric
   * crossing where either involved edge is a bridge or tunnel is not an
   * at-grade crossing — the dominant false-positive classes were bridge-ramp
   * loops (route crosses under its own bridge approach, e.g. a spiral ramp
   * built to avoid stairs) and river crossings re-crossed on different
   * bridges. Edge tags ride on the edge's END element ({@code message} of
   * {@code nodes[i]} describes edge i-1→i); raw tracks without messages keep
   * the historic behaviour (no exemption).
   */
  static boolean bridgeOrTunnelEdge(OsmPathElement edgeEnd) {
    if (edgeEnd == null || edgeEnd.message == null || edgeEnd.message.wayKeyValues == null) {
      return false;
    }
    String tags = edgeEnd.message.wayKeyValues;
    return tags.contains("bridge=") || tags.contains("tunnel=");
  }

  static int countSelfIntersections(OsmTrack track) {
    if (track == null || track.nodes == null || track.nodes.size() < 4) return 0;
    List<OsmPathElement> nodes = sampledShapeNodes(track.nodes);
    int n = nodes.size();
    double[] cum = new double[n];
    for (int k = 1; k < n; k++) {
      cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
    }
    double perim = cum[n - 1];
    int crossings = 0;
    // Hard ceiling proportional to the threshold; routes that already
    // qualify as chaotic don't benefit from precise upper counting and
    // we'd rather avoid the O(n²) cost of long scans on degenerate input.
    int absoluteCeiling = MAX_SELF_INTERSECTIONS * 4;
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
          if (crossings > absoluteCeiling) return crossings;
        }
      }
    }
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

  /**
   * Count node revisits where the second pass crosses the first TRANSVERSELY —
   * the four incident path directions interleave around the shared node. A
   * touch-and-turn (teardrop pinch: both pass-2 directions inside one sector
   * of pass 1) and a same-edge retrace (shared neighbor) are NOT crossings;
   * the former is the near-revisit detector's domain, the latter is reuse.
   */
  private static int countTransverseNodeRevisits(List<OsmPathElement> nodes, int ceiling, double[] cum) {
    int n = nodes.size();
    if (n < 5 || ceiling <= 0) return 0;
    double perim = cum[n - 1];
    Map<Long, int[]> first = new java.util.HashMap<>(n * 2);
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
      int[] grown = java.util.Arrays.copyOf(prevIdx, prevIdx.length + 1);
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
   * Upper bound on shared-run length (m) for a corridor to count as a crossing.
   * Longer same-direction overlaps are road reuse, not knots (see section note).
   */
  static final double MAX_CORRIDOR_CROSS_M = 300;

  /**
   * Maximal shared corridors of a closed track: runs of >=2 consecutive node
   * revisits (i.e. at least one shared EDGE — the single-node case stays with
   * {@link #isTransverseRevisit}). Returns one {@code int[]{a1, a2, b1, b2,
   * sameDir, crossing}} per run, where {@code a1..a2} is the pass-1 index
   * span, {@code b1..b2} the pass-2 span, {@code sameDir} whether pass 2
   * rides the run in the same direction (always true on oneway/roundabout
   * edges), and {@code crossing} whether the loop transversally crosses
   * itself through this run. {@code crossing} requires three things:
   * same-direction (opposite-direction runs are a two-way retrace, the
   * reuse/CorridorOverlapIndex domain — counting them would mislabel measured
   * retraces like Route de Clermont), a shared run no longer than
   * {@link #MAX_CORRIDOR_CROSS_M} (longer = reuse, not a knot), and a
   * transversal side-swap ({@link #corridorCrosses}).
   *
   * <p>Caller passes FULL-resolution nodes: sampling breaks the node-identity
   * adjacency this grouping relies on.
   */
  static List<int[]> sharedCorridors(List<OsmPathElement> nodes) {
    List<int[]> out = new ArrayList<>();
    int n = nodes.size();
    if (n < 5) return out;
    double[] cum = new double[n];
    for (int k = 1; k < n; k++) cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
    double perim = cum[n - 1];

    List<int[]> pairs = new ArrayList<>();
    Map<Long, int[]> first = new java.util.HashMap<>(n * 2);
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
        int[] grown = java.util.Arrays.copyOf(prev, prev.length + 1);
        grown[prev.length] = k;
        first.put(id, grown);
      } else {
        first.put(id, new int[]{k});
      }
    }
    if (pairs.isEmpty()) return out;
    pairs.sort((x, y) -> Integer.compare(x[0], y[0]));

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
   * Exact corridor-contracted transversality: does pass 2 (entering the shared
   * run at node {@code a1} from {@code nodes[b1-1]}, exiting at {@code a2}
   * toward {@code nodes[b2+1]}) cross pass 1's path (… {@code a1-1} → run →
   * {@code a2+1} …)? Since pass 2 rides exactly ON pass 1's run, side-of-path
   * propagates consistently along it, so the run contracts to its two end
   * nodes: at each end, pass 1's outgoing and incoming rays split the angular
   * circle, and pass 2 crosses iff its attachment falls in different sectors
   * at the two ends (same outgoing-then-incoming boundary order at both ends
   * keeps the sector orientation comparable). This is the corridor analogue of
   * {@link #isTransverseRevisit}'s single-node test, with real node geometry —
   * no centroid approximation.
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
   * Count over {@link #sharedCorridors}: one crossing per qualifying
   * same-direction run. Added to {@link #countSelfIntersections} (see the call
   * site there); pass FULL-resolution nodes.
   */
  static int countCorridorCrossings(List<OsmPathElement> nodes) {
    int crossings = 0;
    for (int[] c : sharedCorridors(nodes)) {
      crossings += c[5];
      if (crossings >= MAX_SELF_INTERSECTIONS * 4) break;
    }
    return crossings;
  }

  private static List<OsmPathElement> sampledShapeNodes(List<OsmPathElement> nodes) {
    if (nodes.size() <= MAX_SHAPE_SCAN_NODES) return nodes;
    List<OsmPathElement> sampled = new ArrayList<>(MAX_SHAPE_SCAN_NODES);
    double step = (double) (nodes.size() - 1) / (MAX_SHAPE_SCAN_NODES - 1);
    for (int i = 0; i < MAX_SHAPE_SCAN_NODES; i++) {
      int idx = (int) Math.round(i * step);
      if (idx >= nodes.size()) idx = nodes.size() - 1;
      sampled.add(nodes.get(idx));
    }
    return sampled;
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
   * Walk track edges and reject on either:
   * <ul>
   *   <li>a single unbroken hostile stretch longer than
   *       {@link #MAX_CONTIGUOUS_HOSTILE_METERS} (the cyclist's "I was
   *       sent down 2km of farm track" complaint surface), or</li>
   *   <li>total hostile distance share above {@link #MAX_HOSTILE_FRACTION},
   *       or</li>
   *   <li>missing-metadata (suspect) distance share above
   *       {@link #MAX_HOSTILE_FRACTION}.</li>
   * </ul>
   * Missing metadata is treated as suspect, never as proof of quality.
   * Suspect edges <em>break</em> the contiguous-hostile run (reset to 0): an
   * unknown span is conservatively assumed to interrupt the hostile stretch, so
   * the worst contiguous hostile length is under-reported rather than spanned
   * across unknown gaps (see {@link #worstContiguousHostileMetersPaved}).
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
      if (m.wayKeyValues == null) {
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
        if (m.costfactor > HOSTILE_COSTFACTOR_THRESHOLD) {
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
   * Walk the track and return the longest unbroken run of paved-profile-hostile
   * edges in meters. The same predicate as {@link #checkHostileSegmentsPaved}
   * uses for the gate's contiguous-stretch ceiling, exposed so the
   * candidate scorer (Phase 2 v2) can prefer routes whose worst contiguous
   * stretch stays well under {@link #MAX_CONTIGUOUS_HOSTILE_METERS} — the
   * same metric the gate enforces, fed back into candidate selection.
   *
   * <p>Suspect edges (missing tags) break the streak. They neither extend
   * the hostile run nor are counted as clean; treating them as breaks is
   * conservative (under-reports the worst stretch).
   *
   * <p>Returns 0 for an empty or single-node track.
   */
  public static int worstContiguousHostileMetersPaved(OsmTrack track) {
    return worstHostileStretchPaved(track).meters;
  }

  /**
   * Scorer-side approximation of {@link #worstContiguousHostileMetersPaved}
   * that works on single-pass tracks (where {@code MessageData.wayKeyValues}
   * is null and the tag-based hostility check at
   * {@link #isHostileForPavedProfile} silently returns false).
   *
   * <p>The gate's precise predicate uses both costfactor and tag matching,
   * and breaks the contiguous run on any edge with null {@code wayKeyValues}.
   * That design is correct for the gate (don't reject without evidence) but
   * produces a near-useless signal for candidate ranking: single-pass tracks
   * always return 0, so the scorer's
   * {@code contiguousHostilityPenalty} never fires and hostile candidates
   * keep their high routedScore rank. The gate later rejects them post-
   * detail, and the planner has to shrink radius or pick a worse-shape
   * alternate to recover.
   *
   * <p>This method bypasses the {@code wayKeyValues == null} guard and
   * uses ONLY the per-edge {@code costfactor} (which IS populated during
   * single-pass routing). It uses a lower threshold than the gate
   * ({@link #SCORER_HOSTILE_COSTFACTOR_THRESHOLD} = 3.0 vs
   * {@link #HOSTILE_COSTFACTOR_THRESHOLD} = 4.0) to compensate for the
   * missing tag check — many hostile-by-tag edges have costfactor in the
   * 2-4 range under the fastbike profile, just below the gate's cost-only
   * threshold. The lower bound is calibrated to flag edges whose cost
   * already signals "the profile is paying a real penalty here" without
   * firing on quiet rural roads (typically costfactor ~1.0-2.0).
   *
   * <p>Intended for candidate <em>ranking</em> only. Gate enforcement must
   * continue to use the precise tag-aware
   * {@link #worstContiguousHostileMetersPaved}.
   *
   * @return longest unbroken meters of edges with costfactor &gt;
   *         {@link #SCORER_HOSTILE_COSTFACTOR_THRESHOLD}; 0 on tracks
   *         without MessageData.
   */
  public static int worstContiguousCostlyMetersForScorer(OsmTrack track) {
    return worstContiguousMetersAboveCostfactor(track, SCORER_HOSTILE_COSTFACTOR_THRESHOLD, null);
  }

  /**
   * SAFE-5 overload: same as {@link #worstContiguousCostlyMetersForScorer(OsmTrack)}
   * but uses caller-precomputed per-segment distances ({@code segLens[i-1]} =
   * distance from node i-1 to i) instead of recomputing {@code calcDistance}.
   * {@code segLens} must match {@code calcDistance} for every segment (it is the
   * same int), so the result is bit-identical.
   */
  public static int worstContiguousCostlyMetersForScorer(OsmTrack track, int[] segLens) {
    return worstContiguousMetersAboveCostfactor(track, SCORER_HOSTILE_COSTFACTOR_THRESHOLD, segLens);
  }

  /**
   * Costfactor-only worst-contiguous-stretch finder. Package-private for
   * unit testing different thresholds; the production path uses
   * {@link #worstContiguousCostlyMetersForScorer}.
   */
  static int worstContiguousMetersAboveCostfactor(OsmTrack track, double threshold) {
    return worstContiguousMetersAboveCostfactor(track, threshold, null);
  }

  /**
   * @param segLens SAFE-5 precomputed per-segment distances ({@code segLens[i-1]}
   *                = distance from node i-1 to i), or {@code null} to compute
   *                inline. The original always computed {@code calcDistance}
   *                for every segment (before the null-message check), so a
   *                fully-populated buffer reproduces the scan exactly.
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
      if (m.costfactor > threshold) {
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
   * Return details for the longest unbroken hostile run on a paved profile.
   * This is intentionally diagnostic-only: validation still depends on
   * {@link #worstContiguousHostileMetersPaved(OsmTrack)}, but tests and
   * planner instrumentation need coordinates to distinguish unavoidable
   * terrain from planner-induced bad choices.
   */
  public static HostileStretch worstHostileStretchPaved(OsmTrack track) {
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
      if (m == null || m.wayKeyValues == null) {
        current = 0; // suspect breaks the run
        currentStartIndex = -1;
        continue;
      }
      if (isHostileForPavedProfile(m)) {
        if (current == 0) {
          currentStartIndex = i - 1;
          currentStartIlon = a.getILon();
          currentStartIlat = a.getILat();
          currentStartTags = m.wayKeyValues;
        }
        current += segLen;
        int meters = (int) current;
        if (meters > best.meters) {
          best = new HostileStretch(meters,
            currentStartIndex, i,
            currentStartIlon, currentStartIlat,
            b.getILon(), b.getILat(),
            currentStartTags, m.wayKeyValues);
        }
      } else {
        current = 0;
        currentStartIndex = -1;
      }
    }
    return best;
  }

  public static final class HostileStretch {
    public static final HostileStretch NONE = new HostileStretch(
      0, -1, -1, 0, 0, 0, 0, null, null);

    public final int meters;
    public final int startIndex;
    public final int endIndex;
    public final int startIlon;
    public final int startIlat;
    public final int endIlon;
    public final int endIlat;
    public final String startTags;
    public final String endTags;

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

    public boolean isPresent() {
      return meters > 0;
    }

    public double startLon() {
      return (startIlon - 180000000) / 1000000.0;
    }

    public double startLat() {
      return (startIlat - 90000000) / 1000000.0;
    }

    public double endLon() {
      return (endIlon - 180000000) / 1000000.0;
    }

    public double endLat() {
      return (endIlat - 90000000) / 1000000.0;
    }

    public String describe() {
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
   * Fraction of route distance whose target node lacks source way metadata.
   * Paved-profile validation treats these edges as suspect because it cannot
   * prove whether they are asphalt, track, ferry, or direct fallback geometry.
   *
   * <p>This is exposed for greedy planning so a committed graph-native leg can
   * verify that its metadata retracking pass actually succeeded before the leg
   * becomes part of the mutable loop state.
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
      if (m == null || m.wayKeyValues == null) {
        missing += segLen;
      }
    }
    return total > 0.0 ? missing / total : 0.0;
  }

  static boolean isHostileForPavedProfile(MessageData m) {
    String tags = m.wayKeyValues;
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
    if (m.costfactor > HOSTILE_COSTFACTOR_THRESHOLD) return true;
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
   * Whether the way carries a poor tracktype (grade2-5), a stronger
   * surface-quality signal than the surface tag. Used to veto the
   * "hard surface ⇒ rideable" overrides for both tracks and soft highways.
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
   * Whether the way is a road-bike-suitable {@code highway=track}. Three
   * evidence cascades, any of which is sufficient (the way still needs
   * to be free of explicit bicycle restrictions in all cases):
   *
   * <ol>
   *   <li><b>hardSurface alone</b> — {@code surface=asphalt|paved|paving_stones|
   *       concrete|chipseal}. Asphalt is asphalt regardless of {@code tracktype};
   *       cyclists routinely ride paved tracks without explicit cycling tagging
   *       (B.2 evidence: Kandel "Saubergweg" appears in 2 different Freiburg
   *       routes as {@code highway=track surface=asphalt} with no tracktype).</li>
   *   <li><b>grade1 + cycle network</b> — {@code tracktype=grade1 +
   *       route_bicycle_lcn|rcn|ncn|icn=yes}. The OSM definition of grade1
   *       is "Solid hard surface, typically tarmac/asphalt/concrete," so the
   *       hard surface is implicit. The cycle-network tag is curated evidence
   *       that the route is rideable (B.1 evidence: Baar grade1+lcn track
   *       appears in 2 different Freiburg routes; cycle networks aren't
   *       routed over unpaved unless explicitly so tagged).</li>
   *   <li><b>grade1 + explicit hard surface</b> — the original strict case,
   *       kept for symmetry and the rare grade1 way that has both an
   *       explicit surface tag and isn't part of a cycle network.</li>
   * </ol>
   *
   * <p>Evidence backing: 1032 cyclist-curated GPX routes replayed
   * point-to-point (Basel + Mallorca + Innsbruck + Freiburg) — 95.9% pass
   * rate. The remaining failures cluster on genuine gravel sections
   * (defensible) or these grade1-no-surface / asphalt-no-grade patterns
   * this predicate now accepts.
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
   * Whether the way is tagged as part of an OSM cycle network. Strong
   * curated evidence that the route is rideable — these networks aren't
   * routed over unpaved unless the unpaved section is explicitly
   * surface-tagged that way.
   */
  private static boolean hasCycleNetworkTag(String tags) {
    return tags.contains("route_bicycle_lcn=yes")
      || tags.contains("route_bicycle_rcn=yes")
      || tags.contains("route_bicycle_ncn=yes")
      || tags.contains("route_bicycle_icn=yes");
  }

  /**
   * Explicit access denials that override "default permissive" tagging.
   * {@code bicycle=no} is the direct denial; {@code access=private} and
   * {@code access=no} are blanket denials that include bicycles. We do NOT
   * treat {@code bicycle=dismount} as a restriction here — that's a
   * surface-quality hint, not a legal denial, and the cost-function
   * already penalises it appropriately.
   */
  private static boolean hasExplicitBicycleRestriction(String tags) {
    return hasTag(tags, "bicycle=no")
      || hasTag(tags, "access=private")
      || hasTag(tags, "access=no");
  }

  /**
   * Whether {@code keyValue} appears as a whole token in {@code tags} (a
   * space-joined list of {@code key=value} pairs from
   * {@link btools.expressions.BExpressionContext#getKeyValueDescription}).
   * Token-boundary matching avoids the substring trap where a naive
   * {@code contains("bicycle=no")} also matches the cyclist-friendly
   * {@code oneway:bicycle=no} (cyclists exempted from a oneway — the opposite
   * of a ban).
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
   * Lower bound on cf(grade3 gravel track) / cf(paved residential) at or above
   * which a profile is classified paved-only — i.e. it treats loose unpaved
   * surface as effectively off-limits, so a round-trip routed onto it should be
   * rejected. Validated against every profile whose vehicle cannot ride loose
   * unpaved (all well above 5.0): fastbike 8.3, skating 9523, moped 5015,
   * car-vario 10000, velomobil 23.5; versus the unpaved-tolerant bikes well
   * below it: trekking 2.65, gravel 0.79, mtb 0.57. The ratio (not the absolute
   * cost) is the discriminator: mtb penalises unpaved heavily too (abs 7.5) but
   * penalises paved even harder (13.6), giving 0.55.
   */
  static final double PAVED_PROBE_RATIO = 5.0;

  /**
   * Memoised paved/road-bike classification, keyed by profile name. Populated by
   * {@link #classifyPavedProfile} (which has the expression context) and read by
   * {@link #isPavedProfile} (which only has the name). A single routing JVM
   * serves a stable set of profiles, so name is an adequate cache key.
   */
  private static final Map<String, Boolean> PAVED_CLASSIFICATION = new ConcurrentHashMap<>();

  /**
   * Classify whether a profile is paved/road-bike by what its cost model
   * actually charges for an unpaved way — independent of the profile's name.
   * The result is memoised by {@code profileName} and subsequently returned by
   * {@link #isPavedProfile}. Call this once per request at round-trip setup,
   * while the way-expression context is available.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>explicit author override global {@code roadbikeSurfaceGate}
   *       ({@code 1} = paved-only, {@code 0} = not) — lets a profile force the
   *       classification when the probe would be ambiguous;</li>
   *   <li>cost-model probe: {@code cf(gravel track) / cf(paved residential)} at
   *       or above {@link #PAVED_PROBE_RATIO}.</li>
   * </ol>
   *
   * <p>When no way context is available to probe (only the case in isolated
   * unit tests — production always parses a profile), the profile is treated as
   * not-paved so the hostile-surface gate is simply not imposed.
   */
  public static boolean classifyPavedProfile(BExpressionContextWay expctxWay, String profileName) {
    boolean paved;
    if (expctxWay == null) {
      paved = false;
    } else {
      float override = expctxWay.getVariableValue("roadbikeSurfaceGate", -1f);
      if (override >= 0f) {
        paved = override >= 0.5f;
      } else {
        paved = probePavedFromCostModel(expctxWay);
      }
    }
    // Only cache a verdict computed from a real probe context. A null expctxWay
    // means we could not probe, so `paved` is just the safe `false` default for
    // this call's own immediate use; writing it would poison the shared static
    // cache and make later isPavedProfile() lookups wrongly bypass the
    // hostile-surface gate. Note: AUTO-competition child engines do NOT have a
    // null context here — each child re-parses its own profile (non-null
    // expctxWay) and re-runs this probe, so it writes the same verdict
    // idempotently rather than being skipped. The guard defends against any
    // genuinely context-less caller, not against the AUTO children.
    if (profileName != null && expctxWay != null) {
      PAVED_CLASSIFICATION.put(profileName, paved);
    }
    return paved;
  }

  /**
   * Probe the profile's surface policy: a profile is paved-only if it makes a
   * representative loose-unpaved way (grade3 gravel track) far costlier than a
   * paved residential road. Returns false if the context cannot be probed.
   *
   * <p>Measured ratio cf(grade3-gravel-track)/cf(paved-residential):
   * <pre>
   *   fastbike                  8.33    paved
   *   fastbike-verylowtraffic   8.33    paved
   *   skating                  ~9523    paved (rollerblades — forbids tracks)
   *   moped                    ~5015    paved
   *   car-vario               ~10000    paved
   *   vm-velomobil             23.53    paved (faired road vehicle — cannot ride gravel)
   *   trekking                  2.65    NOT paved (tolerates loose surface)
   *   gravel                    0.79    NOT paved
   *   mtb                       0.57    NOT paved (penalises paved even harder)
   *   shortest                  1.00    NOT paved
   * </pre>
   * Every vehicle that cannot ride loose unpaved scores well above 5.0; the
   * unpaved-tolerant bikes sit at or below 2.65 — a wide, robust margin. The
   * verdict is a ratio against the paved reference, so it is invariant to a
   * global scaling of the profile's costfactors (cost is relative — scaling
   * every costfactor by a constant changes neither the chosen route nor the
   * classification).
   *
   * <p>grade3 is the probe point because grade1 does not discriminate: a road
   * bike rides a well-graded grade1 track cheaply (fastbike 1.0x paved) but
   * skating/moped/car penalise even grade1 — so "rides grade1 cheaply" is NOT a
   * universal paved-only trait, and a profile the grade3 probe misjudges can set
   * the {@code roadbikeSurfaceGate} override.
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

  /**
   * Paved/road-bike classification for a profile, by name.
   *
   * <p>Returns the cost-model-probe result recorded by {@link #classifyPavedProfile}
   * (the round-trip path warms it once at setup). A profile that has not been
   * classified is treated as not-paved — there is no name-based guess.
   */
  public static boolean isPavedProfile(String profileName) {
    if (profileName == null) {
      return false;
    }
    Boolean classified = PAVED_CLASSIFICATION.get(profileName);
    return classified != null && classified;
  }

  /**
   * Test-only seam: seed the classification cache directly, so unit tests that
   * exercise the gate's paved-surface behaviour can mark a profile name as
   * paved/not-paved without parsing a real profile to run the probe.
   */
  static void putPavedClassificationForTest(String profileName, boolean paved) {
    PAVED_CLASSIFICATION.put(profileName, paved);
  }
}
