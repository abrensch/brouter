/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.GeometryDecoder;
import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

public final class RoutingContext {
  public void setAlternativeIdx(int idx) {
    alternativeIdx = idx;
  }

  public int getAlternativeIdx(int min, int max) {
    return alternativeIdx < min ? min : (alternativeIdx > max ? max : alternativeIdx);
  }

  /**
   * Variety seed for round-trip mode: the raw {@code alternativeidx} value with
   * only a lower clamp at 0. Unlike classic routing's enumerated 0–3
   * alternatives, round trips reuse {@code alternativeidx} as a free seed —
   * any value &gt;= 1 deterministically selects one loop variant; 0 (or absent)
   * is bit-identical to the unperturbed baseline. The seed never influences the
   * start-direction draw.
   */
  public int getRoundTripSeed() {
    return Math.max(0, alternativeIdx);
  }

  /**
   * Multiplier on the anti-reuse refTrack penalty in
   * {@link OsmPath#addAddionalPenalty}: a traveled refTrack edge costs an
   * extra {@code linkdist × this factor}. 1.0 is the historic behaviour and
   * exact at integer math (bit-identical costs); the greedy round-trip
   * return-variant search lowers it (0.5 / 0.0) to offer retrace-tolerant
   * closing legs when the fully-penalised return ships a self-crossing.
   * Nothing outside the round-trip planner should ever set it ≠ 1.0.
   */
  public double refTrackCostFactor = 1.0;

  /**
   * True while this context drives round-trip (loop) generation (engineMode 4).
   * Gates the anti-reuse refTrack penalty in {@link OsmPath#addAddionalPenalty}
   * to its edge-membership form (a link is reused only when the refTrack actually
   * traveled it). General routing (incl. alternativeidx alternatives) keeps the
   * historic both-endpoints node-membership test so its output is unchanged. Set
   * in the {@link RoutingEngine} constructor for every round-trip engine (parent
   * and AUTO children) and carried into child contexts by {@link #copyRequestFields()}.
   */
  public boolean roundTrip = false;

  public int alternativeIdx = 0;
  public String localFunction;
  public long profileTimestamp;

  public Map<String, String> keyValues;

  public String rawTrackPath;
  public String rawAreaPath;

  public String getProfileName() {
    String name = localFunction == null ? "unknown" : localFunction;
    if (name.endsWith(".brf")) name = name.substring(0, localFunction.length() - 4);
    int idx = name.lastIndexOf(File.separatorChar);
    if (idx >= 0) name = name.substring(idx + 1);
    return name;
  }

  public BExpressionContextWay expctxWay;
  public BExpressionContextNode expctxNode;

  public GeometryDecoder geometryDecoder = new GeometryDecoder();

  public int memoryclass = 64;

  public boolean carMode;
  public boolean bikeMode;
  public boolean footMode;
  public boolean considerTurnRestrictions;
  public boolean processUnusedTags;
  public boolean forceSecondaryData;
  public double pass1coefficient;
  public double pass2coefficient;
  public int elevationpenaltybuffer;
  public int elevationmaxbuffer;
  public int elevationbufferreduce;

  public double cost1speed;
  public double additionalcostfactor;
  public double changetime;
  public double buffertime;
  public double waittimeadjustment;
  public double inittimeadjustment;
  public double starttimeoffset;
  public boolean transitonly;

  public double waypointCatchingRange;
  public boolean correctMisplacedViaPoints;
  public double correctMisplacedViaPointsDistance;
  public boolean continueStraight;
  public boolean useDynamicDistance;
  public boolean buildBeelineOnRange;

  public AreaInfo ai;

  private void setModel(String className) {
    if (className == null) {
      pm = new StdModel();
    } else {
      try {
        Class<?> clazz = Class.forName(className);
        pm = (OsmPathModel) clazz.getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Cannot create path-model: " + e);
      }
    }
    initModel();
  }

  public void initModel() {
    pm.init(expctxWay, expctxNode, keyValues);
  }

  public long getKeyValueChecksum() {
    long s = 0L;
    if (keyValues != null) {
      for (Map.Entry<String, String> e : keyValues.entrySet()) {
        s += e.getKey().hashCode() + e.getValue().hashCode();
      }
    }
    return s;
  }

  public void readGlobalConfig() {
    BExpressionContext expctxGlobal = expctxWay; // just one of them...
    setModel(expctxGlobal._modelClass);

    carMode = 0.f != expctxGlobal.getVariableValue("validForCars", 0.f);
    bikeMode = 0.f != expctxGlobal.getVariableValue("validForBikes", 0.f);
    footMode = 0.f != expctxGlobal.getVariableValue("validForFoot", 0.f);

    consider_crossing = 0.f != expctxGlobal.getVariableValue("consider_crossing", 0.f);

    crossing_Prio_H = (int) expctxGlobal.getVariableValue("crossing_Prio_H", 0.f);
    crossing_Prio_L = (int) expctxGlobal.getVariableValue("crossing_Prio_L", 0.f);

    cost_ToLeft_from_H_class1 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class1", 0.f);
    cost_ToLeft_from_H_class2 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class2", 0.f);
    cost_ToLeft_from_H_class3 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class3", 0.f);
    cost_ToLeft_from_H_class4 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class4", 0.f);
    cost_ToLeft_from_H_class5 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class5", 0.f);
    cost_ToLeft_from_H_class6 = (int) expctxGlobal.getVariableValue("cost_ToLeft_from_H_class6", 0.f);

    // for left-hand traffic
    cost_ToRight_from_H_class1 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class1", 0.f);
    cost_ToRight_from_H_class2 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class2", 0.f);
    cost_ToRight_from_H_class3 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class3", 0.f);
    cost_ToRight_from_H_class4 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class4", 0.f);
    cost_ToRight_from_H_class5 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class5", 0.f);
    cost_ToRight_from_H_class6 = (int) expctxGlobal.getVariableValue("cost_ToRight_from_H_class6", 0.f);


    waypointCatchingRange = expctxGlobal.getVariableValue("waypointCatchingRange", 250.f);

    // turn-restrictions not used per default for foot profiles
    considerTurnRestrictions = 0.f != expctxGlobal.getVariableValue("considerTurnRestrictions", footMode ? 0.f : 1.f);

    correctMisplacedViaPoints = 0.f != expctxGlobal.getVariableValue("correctMisplacedViaPoints", 0.f);
    correctMisplacedViaPointsDistance = expctxGlobal.getVariableValue("correctMisplacedViaPointsDistance", 400.f); // 0 == don't use distance

    continueStraight = 0.f != expctxGlobal.getVariableValue("continueStraight", 0.f);

    // process tags not used in the profile (to have them in the data-tab)
    processUnusedTags = 0.f != expctxGlobal.getVariableValue("processUnusedTags", 0.f);

    forceSecondaryData = 0.f != expctxGlobal.getVariableValue("forceSecondaryData", 0.f);
    pass1coefficient = expctxGlobal.getVariableValue("pass1coefficient", 1.5f);
    pass2coefficient = expctxGlobal.getVariableValue("pass2coefficient", 0.f);
    elevationpenaltybuffer = (int) (expctxGlobal.getVariableValue("elevationpenaltybuffer", 5.f) * 1000000);
    elevationmaxbuffer = (int) (expctxGlobal.getVariableValue("elevationmaxbuffer", 10.f) * 1000000);
    elevationbufferreduce = (int) (expctxGlobal.getVariableValue("elevationbufferreduce", 0.f) * 10000);

    cost1speed = expctxGlobal.getVariableValue("cost1speed", 22.f);
    additionalcostfactor = expctxGlobal.getVariableValue("additionalcostfactor", 1.5f);
    changetime = expctxGlobal.getVariableValue("changetime", 180.f);
    buffertime = expctxGlobal.getVariableValue("buffertime", 120.f);
    waittimeadjustment = expctxGlobal.getVariableValue("waittimeadjustment", 0.9f);
    inittimeadjustment = expctxGlobal.getVariableValue("inittimeadjustment", 0.2f);
    starttimeoffset = expctxGlobal.getVariableValue("starttimeoffset", 0.f);
    transitonly = expctxGlobal.getVariableValue("transitonly", 0.f) != 0.f;

    showspeed = 0.f != expctxGlobal.getVariableValue("showspeed", 0.f);
    showSpeedProfile = 0.f != expctxGlobal.getVariableValue("showSpeedProfile", 0.f);
    inverseRouting = 0.f != expctxGlobal.getVariableValue("inverseRouting", 0.f);
    showTime = 0.f != expctxGlobal.getVariableValue("showtime", 0.f);

    int tiMode = (int) expctxGlobal.getVariableValue("turnInstructionMode", 0.f);
    if (tiMode != 1) { // automatic selection from coordinate source
      turnInstructionMode = tiMode;
    }
    turnInstructionCatchingRange = expctxGlobal.getVariableValue("turnInstructionCatchingRange", 40.f);
    turnInstructionRoundabouts = expctxGlobal.getVariableValue("turnInstructionRoundabouts", footMode ? 0.f : 1.f) != 0.f;

    // Speed computation model (for bikes)
    // Total mass (biker + bike + luggages or hiker), in kg
    totalMass = expctxGlobal.getVariableValue("totalMass", 90.f);
    // Max speed (before braking), in km/h in profile and m/s in code
    if (footMode) {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 6.f) / 3.6;
    } else {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 45.f) / 3.6;
    }
    // Equivalent surface for wind, S * C_x, F = -1/2 * S * C_x * v^2 = - S_C_x * v^2
    S_C_x = expctxGlobal.getVariableValue("S_C_x", 0.5f * 0.45f);
    // Default resistance of the road, F = - m * g * C_r (for good quality road)
    defaultC_r = expctxGlobal.getVariableValue("C_r", 0.01f);
    // Constant power of the biker (in W)
    bikerPower = expctxGlobal.getVariableValue("bikerPower", 100.f);

    useDynamicDistance = expctxGlobal.getVariableValue("use_dynamic_range", 1f) == 1f;
    buildBeelineOnRange = expctxGlobal.getVariableValue("add_beeline", 0f) == 1f;

    boolean test = expctxGlobal.getVariableValue("check_start_way", 1f) == 1f;
    if (!test) freeNoWays();

  }

  public void freeNoWays() {
    BExpressionContext expctxGlobal = expctxWay;
    if (expctxGlobal != null) expctxGlobal.freeNoWays();
  }

  public List<OsmNodeNamed> poipoints;

  public List<OsmNodeNamed> nogopoints = null;
  private List<OsmNodeNamed> nogopoints_all = null; // full list not filtered for wayoints-in-nogos
  private List<OsmNodeNamed> keepnogopoints = null;
  private OsmNodeNamed pendingEndpoint = null;

  // Dense (town/city) areas for GREEDY via-steering. Null = off (general routing and non-steered
  // round-trips). Built at round-trip time from the desirability grid (RoutingEngine) and consumed
  // only by the round-trip planner — never by the general per-segment cost engine.
  public DenseAreaMap denseAreaMap = null;

  public Integer startDirection;
  public boolean startDirectionValid;
  public boolean forceUseStartDirection;
  public Integer roundTripDistance;
  /** Desired total loop distance in meters. Takes precedence over roundTripDistance. */
  public Integer roundTripLength;
  public Integer roundTripDirectionAdd;
  public Integer roundTripPoints;
  public boolean allowSamewayback;
  public RoundTripAlgorithm roundTripAlgorithm = RoundTripAlgorithm.AUTO;
  /**
   * Quality-gate strictness for generated round-trips. When {@code false}
   * (the default), a route that fails only a QUALITY check
   * ({@link RoundTripQualityResult.RejectionTier#QUALITY}: distance off-target,
   * self-crossing/hairpin chaos, profile-hostile surface, mid-route
   * backtracking) is returned anyway with an advisory {@code Warning:} message
   * so the user can decide whether to ride it. STRUCTURAL failures (broken /
   * un-routable / not-a-loop) are always hard-rejected. When {@code true},
   * QUALITY failures are hard-rejected too (the pre-existing behaviour), e.g.
   * for the quality-measurement test matrices that must only grade clean loops.
   * Settable via the request parameter {@code roundTripStrictQuality=1}.
   */
  public boolean roundTripStrictQuality;

  /**
   * Via-arc densification: when on, the explicit-via round-trip inserts generated
   * "bulge" waypoints between consecutive user anchors, offset outward from the
   * anchor centroid, so each leg follows the loop perimeter instead of cutting the
   * chord. Opt-in; off by default.
   * {@link #explicitViaDensifyAlpha} is the bulge offset as a fraction of the leg chord length.
   *
   * <p>This is the effective per-request flag, computed by {@code doExplicitViaRoundTrip}
   * from {@link #explicitViaDensifyOverride}; callers normally set the override, not this.
   */
  public boolean explicitViaDensify;
  /**
   * Request densification of explicit-via legs. The engine computes the effective
   * {@link #explicitViaDensify} as {@code Boolean.TRUE.equals(override) && !isPavedProfile}:
   * <ul>
   *   <li>{@code TRUE} → opt in, but still gated to non-paved profiles. Paved profiles
   *       (road bike) keep the plain explicit-via route, because in sparse terrain a
   *       retracing paved lollipop beats a one-way track loop the gate would reject.
   *       {@code TRUE} does <b>not</b> bypass the paved-profile gate.</li>
   *   <li>{@code FALSE} or {@code null} (default) → no densification.</li>
   * </ul>
   * Used by tests/measurement to compare both modes deterministically. Tests that need
   * densification on a paved profile must set {@link #explicitViaDensify} directly.
   */
  public Boolean explicitViaDensifyOverride;
  public double explicitViaDensifyAlpha = 0.5;
  /**
   * Max profile cost-factor a densification "bulge" point may snap to. A bulge is an
   * optional nicety, so it is placed ONLY on a road the profile genuinely likes (near-ideal),
   * not merely an accessible one — keeping it well below the lenient user-snap reject
   * threshold. Where the only road outward is profile-hostile (e.g. a road bike facing a
   * track), the bulge is dropped and that leg reverts to its baseline form. Profile-relative
   * (cost-factor is per-profile), so gravel still accepts tracks it likes while fastbike does not.
   */
  public double explicitViaDensifyMaxCostFactor = 1.8;

  /**
   * Shortcut for {@link #roundTripAlgorithm} = {@link RoundTripAlgorithm#ISOCHRONE},
   * settable via the URL parameter {@code roundTripIsochrone=1}. Honoured only when
   * {@link #roundTripAlgorithm} is left at AUTO — an explicit algorithm always wins.
   *
   * <p>This is a request-input shortcut only: {@code doRoundTrip()} resolves it
   * into {@link #roundTripAlgorithm} once, up front, after which the algorithm
   * field is the single source of truth (this boolean is no longer read and is
   * not copied into child contexts by {@link #copyRequestFields()}).
   */
  public boolean roundTripIsochrone;

  /**
   * Experimental profile-desirability heatmap for GREEDY round-trips (issue #15),
   * settable via the request parameter {@code roundTripDesirability=1}. Off by
   * default. When on, the GREEDY round-trip accumulates a coarse profile-cost-density
   * grid during its isochrone expansion and biases waypoint placement toward
   * high-desirability cells (see {@link DesirabilityCandidateProvider}).
   *
   * <p>Takes effect only when the GREEDY algorithm actually runs; inert for
   * ISOCHRONE / ISO_GREEDY / WAYPOINT. Under the default AUTO algorithm a GREEDY
   * child is spawned only when ISO_GREEDY does not clearly win the competition, so
   * on good tile data this flag can be silently inert under AUTO — set
   * {@code roundTripAlgorithm=GREEDY} explicitly to guarantee it is honoured. This
   * is an exploratory infrastructure lever, not a tuned route-quality default.
   */
  public boolean roundTripDesirability;

  /**
   * Experimental urban-capsule loop planning for GREEDY round-trips, settable via
   * the request parameter {@code roundTripCapsule=1}. Off by default. When on, the
   * GREEDY round-trip accumulates the same coarse density/elevation grid as the
   * desirability flag and uses {@link CapsuleCandidateProvider} to steer waypoints
   * out of dense capsule interiors toward boundary "portal" cells, plus reward
   * higher ground to counter the flat-terrain bias.
   *
   * <p>Same activation caveats as {@link #roundTripDesirability}: honoured only by
   * the GREEDY algorithm; set {@code roundTripAlgorithm=GREEDY} explicitly to
   * guarantee it takes effect under AUTO. If both flags are set, capsule wins.
   */
  public boolean roundTripCapsule;

  /**
   * Via-steering for GREEDY round-trips (opt-in, request param {@code roundTripSteerVias=1}, default
   * off). When on, the GREEDY round-trip builds the coarse density grid, derives a {@link DenseAreaMap}
   * of town/city cores, and penalises candidate waypoints placed inside those areas — so the planned
   * loop keeps its vias out of built-up cores. Honoured by GREEDY only; costs one extra isochrone
   * expansion to build the grid, which is why it is opt-in.
   */
  public boolean roundTripSteerVias;

  public CheapAngleMeter anglemeter = new CheapAngleMeter();

  public double nogoCost = 0.;
  public boolean isEndpoint = false;

  public boolean shortestmatch = false;
  public double wayfraction;
  public int ilatshortest;
  public int ilonshortest;

  public boolean inverseDirection;

  public boolean showspeed;
  public boolean showSpeedProfile;
  public boolean inverseRouting;
  public boolean showTime;
  public boolean hasDirectRouting;

  public String outputFormat = "gpx";
  public boolean exportWaypoints = false;
  public boolean exportCorrectedWaypoints = false;

  public OsmPrePath firstPrePath;

  public int turnInstructionMode; // 0=none, 1=auto, 2=locus, 3=osmand, 4=comment-style, 5=gpsies-style
  public double turnInstructionCatchingRange;
  public boolean turnInstructionRoundabouts;

  // Speed computation model (for bikes)
  public double totalMass;
  public double maxSpeed;
  public double S_C_x;
  public double defaultC_r;
  public double bikerPower;

  // variables in the profile to activate "crossing costs" at nodes with "estimated_crossing_class" not null
  public boolean consider_crossing = false; // consider crossing

  public int crossing_Prio_H = 0; // min value to considered a HW as "highprio"
  public int crossing_Prio_L = 0; // max value to considered a HW as "lowprio"

  // cost when turning left from a Highprio to a lowprio
  public int cost_ToLeft_from_H_class1 =0;
  public int cost_ToLeft_from_H_class2 =0;
  public int cost_ToLeft_from_H_class3 =0;
  public int cost_ToLeft_from_H_class4 =0;
  public int cost_ToLeft_from_H_class5 =0;
  public int cost_ToLeft_from_H_class6 =0;

  // cost when turning Right from a Highprio to a lowprio
  public int cost_ToRight_from_H_class1 =0;
  public int cost_ToRight_from_H_class2 =0;
  public int cost_ToRight_from_H_class3 =0;
  public int cost_ToRight_from_H_class4 =0;
  public int cost_ToRight_from_H_class5 =0;
  public int cost_ToRight_from_H_class6 =0;

  public static void prepareNogoPoints(List<OsmNodeNamed> nogos) {
    for (OsmNodeNamed nogo : nogos) {
      if (nogo instanceof OsmNogoPolygon) {
        continue;
      }
      String s = nogo.name;
      int idx = s.indexOf(' ');
      if (idx > 0) s = s.substring(0, idx);
      int ir = 20; // default radius
      if (s.length() > 4) {
        try {
          ir = Integer.parseInt(s.substring(4));
        } catch (Exception e) { /* ignore */ }
      }
      // Radius of the nogo point in meters
      nogo.radius = ir;
    }
  }

  /**
   * restore the full nogolist previously saved by cleanNogoList
   */
  public void restoreNogoList() {
    nogopoints = nogopoints_all;
  }

  /**
   * clean the nogolist (previoulsy saved by saveFullNogolist())
   * by removing nogos with waypoints within
   *
   * @return true if all wayoints are all in the same (full-weigth) nogo area (triggering bee-line-mode)
   */
  public void cleanNogoList(List<OsmNode> waypoints) {
    nogopoints_all = nogopoints;
    if (nogopoints == null) return;
    List<OsmNodeNamed> nogos = new ArrayList<>();
    for (OsmNodeNamed nogo : nogopoints) {
      boolean goodGuy = true;
      for (OsmNode wp : waypoints) {
        if (wp.calcDistance(nogo) < nogo.radius
          && (!(nogo instanceof OsmNogoPolygon)
          || (((OsmNogoPolygon) nogo).isClosed
          ? ((OsmNogoPolygon) nogo).isWithin(wp.ilon, wp.ilat)
          : ((OsmNogoPolygon) nogo).isOnPolyline(wp.ilon, wp.ilat)))) {
          goodGuy = false;
        }
      }
      if (goodGuy) nogos.add(nogo);
    }
    nogopoints = nogos.isEmpty() ? null : nogos;
  }

  public void checkMatchedWaypointAgainstNogos(List<MatchedWaypoint> matchedWaypoints) {
    if (nogopoints == null) return;
    int theSize = matchedWaypoints.size();
    if (theSize<2) return;
    int removed = 0;
    List<MatchedWaypoint> newMatchedWaypoints = new ArrayList<>();
    MatchedWaypoint prevMwp = null;
    boolean prevMwpIsInside = false;
    for (int i = 0; i < theSize; i++) {
      MatchedWaypoint mwp = matchedWaypoints.get(i);
      boolean isInsideNogo = false;
      OsmNode wp = mwp.crosspoint;
      for (OsmNodeNamed nogo : nogopoints) {
        if (Double.isNaN(nogo.nogoWeight)
          && wp.calcDistance(nogo) < nogo.radius
          && (!(nogo instanceof OsmNogoPolygon)
          || (((OsmNogoPolygon) nogo).isClosed
          ? ((OsmNogoPolygon) nogo).isWithin(wp.ilon, wp.ilat)
          : ((OsmNogoPolygon) nogo).isOnPolyline(wp.ilon, wp.ilat)))) {
          isInsideNogo = true;
          break;
        }
      }
      if (isInsideNogo) {
        boolean useAnyway = false;
        if (prevMwp == null) useAnyway = true;
        else if (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) useAnyway = true;
        else if (prevMwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) useAnyway = true;
        else if (prevMwpIsInside) useAnyway = true;
        else if (i == theSize-1) {
          throw new IllegalArgumentException("last wpt in restricted area ");
        }
        if (useAnyway) {
          prevMwpIsInside = true;
          newMatchedWaypoints.add(mwp);
        } else {
          removed++;
          prevMwpIsInside = false;
        }

      } else {
        prevMwpIsInside = false;
        newMatchedWaypoints.add(mwp);
      }
      prevMwp = mwp;
    }
    if (newMatchedWaypoints.size() < 2) {
      throw new IllegalArgumentException("a wpt in restricted area ");
    }
    if (removed > 0) {
      matchedWaypoints.clear();
      matchedWaypoints.addAll(newMatchedWaypoints);
    }
  }

  public boolean allInOneNogo(List<OsmNode> waypoints) {
    if (nogopoints == null) return false;
    boolean allInTotal = false;
    for (OsmNodeNamed nogo : nogopoints) {
      boolean allIn = Double.isNaN(nogo.nogoWeight);
      for (OsmNode wp : waypoints) {
        int dist = wp.calcDistance(nogo);
        if (dist < nogo.radius
          && (!(nogo instanceof OsmNogoPolygon)
          || (((OsmNogoPolygon) nogo).isClosed
          ? ((OsmNogoPolygon) nogo).isWithin(wp.ilon, wp.ilat)
          : ((OsmNogoPolygon) nogo).isOnPolyline(wp.ilon, wp.ilat)))) {
          continue;
        }
        allIn = false;
      }
      allInTotal |= allIn;
    }
    return allInTotal;
  }

  public long[] getNogoChecksums() {
    long[] cs = new long[3];
    int n = nogopoints == null ? 0 : nogopoints.size();
    for (int i = 0; i < n; i++) {
      OsmNodeNamed nogo = nogopoints.get(i);
      cs[0] += nogo.ilon;
      cs[1] += nogo.ilat;
      // 10 is an arbitrary constant to get sub-integer precision in the checksum
      cs[2] += (long) (nogo.radius * 10.);
    }
    return cs;
  }

  public void setWaypoint(OsmNodeNamed wp, boolean endpoint) {
    setWaypoint(wp, null, endpoint);
  }

  public void setWaypoint(OsmNodeNamed wp, OsmNodeNamed pendingEndpoint, boolean endpoint) {
    keepnogopoints = nogopoints;
    nogopoints = new ArrayList<>();
    nogopoints.add(wp);
    if (keepnogopoints != null) nogopoints.addAll(keepnogopoints);
    isEndpoint = endpoint;
    this.pendingEndpoint = pendingEndpoint;
  }

  public boolean checkPendingEndpoint() {
    if (pendingEndpoint != null) {
      isEndpoint = true;
      nogopoints.set(0, pendingEndpoint);
      pendingEndpoint = null;
      return true;
    }
    return false;
  }

  public void unsetWaypoint() {
    nogopoints = keepnogopoints;
    pendingEndpoint = null;
    isEndpoint = false;
  }

  public int calcDistance(int lon1, int lat1, int lon2, int lat2) {
    double[] lonlat2m = CheapRuler.getLonLatToMeterScales((lat1 + lat2) >> 1);
    double dlon2m = lonlat2m[0];
    double dlat2m = lonlat2m[1];
    double dx = (lon2 - lon1) * dlon2m;
    double dy = (lat2 - lat1) * dlat2m;
    double d = Math.sqrt(dy * dy + dx * dx);

    shortestmatch = false;

    if (nogopoints != null && !nogopoints.isEmpty() && d > 0.) {
      for (int ngidx = 0; ngidx < nogopoints.size(); ngidx++) {
        OsmNodeNamed nogo = nogopoints.get(ngidx);
        double x1 = (lon1 - nogo.ilon) * dlon2m;
        double y1 = (lat1 - nogo.ilat) * dlat2m;
        double x2 = (lon2 - nogo.ilon) * dlon2m;
        double y2 = (lat2 - nogo.ilat) * dlat2m;
        double r12 = x1 * x1 + y1 * y1;
        double r22 = x2 * x2 + y2 * y2;
        double radius = Math.abs(r12 < r22 ? y1 * dx - x1 * dy : y2 * dx - x2 * dy) / d;

        if (radius < nogo.radius) { // 20m
          double s1 = x1 * dx + y1 * dy;
          double s2 = x2 * dx + y2 * dy;


          if (s1 < 0.) {
            s1 = -s1;
            s2 = -s2;
          }
          if (s2 > 0.) {
            radius = Math.sqrt(s1 < s2 ? r12 : r22);
            if (radius > nogo.radius) continue;
          }
          if (nogo.isNogo) {
            if (!(nogo instanceof OsmNogoPolygon)) {  // nogo is a circle
              if (Double.isNaN(nogo.nogoWeight)) {
                // default nogo behaviour (ignore completely)
                nogoCost = -1;
              } else {
                // nogo weight, compute distance within the circle
                nogoCost = nogo.distanceWithinRadius(lon1, lat1, lon2, lat2, d) * nogo.nogoWeight;
              }
            } else if (((OsmNogoPolygon) nogo).intersects(lon1, lat1, lon2, lat2)) {
              // nogo is a polyline/polygon, we have to check there is indeed
              // an intersection in this case (radius check is not enough).
              if (Double.isNaN(nogo.nogoWeight)) {
                // default nogo behaviour (ignore completely)
                nogoCost = -1;
              } else {
                if (((OsmNogoPolygon) nogo).isClosed) {
                  // compute distance within the polygon
                  nogoCost = ((OsmNogoPolygon) nogo).distanceWithinPolygon(lon1, lat1, lon2, lat2) * nogo.nogoWeight;
                } else {
                  // for a polyline, just add a constant penalty
                  nogoCost = nogo.nogoWeight;
                }
              }
            }
          } else {
            shortestmatch = true;
            nogo.radius = radius; // shortest distance to way
            // calculate remaining distance
            if (s2 < 0.) {
              wayfraction = -s2 / (d * d);
              double xm = x2 - wayfraction * dx;
              double ym = y2 - wayfraction * dy;
              ilonshortest = (int) (xm / dlon2m + nogo.ilon);
              ilatshortest = (int) (ym / dlat2m + nogo.ilat);
            } else if (s1 > s2) {
              wayfraction = 0.;
              ilonshortest = lon2;
              ilatshortest = lat2;
            } else {
              wayfraction = 1.;
              ilonshortest = lon1;
              ilatshortest = lat1;
            }

            // here it gets nasty: there can be nogo-points in the list
            // *after* the shortest distance point. In case of a shortest-match
            // we use the reduced way segment for nogo-matching, in order not
            // to cut our escape-way if we placed a nogo just in front of where we are
            if (isEndpoint) {
              wayfraction = 1. - wayfraction;
              lon2 = ilonshortest;
              lat2 = ilatshortest;
            } else {
              nogoCost = 0.;
              lon1 = ilonshortest;
              lat1 = ilatshortest;
            }
            dx = (lon2 - lon1) * dlon2m;
            dy = (lat2 - lat1) * dlat2m;
            d = Math.sqrt(dy * dy + dx * dx);
          }
        }
      }
    }
    return (int) Math.max(1.0, Math.round(d));
  }

  public OsmPathModel pm;

  public OsmPrePath createPrePath(OsmPath origin, OsmLink link) {
    OsmPrePath p = pm.createPrePath();
    if (p != null) {
      p.init(origin, link, this);
    }
    return p;
  }

  public OsmPath createPath(OsmLink link) {
    OsmPath p = pm.createPath();
    p.init(link);
    return p;
  }

  public OsmPath createPath(OsmPath origin, OsmLink link, OsmTrack refTrack, boolean detailMode) {
    OsmPath p = pm.createPath();
    p.init(origin, link, refTrack, detailMode, this);
    return p;
  }

  /**
   * Produce a fresh {@link RoutingContext} carrying only the REQUEST-LEVEL
   * fields: profile path, key/value lookups, round-trip settings, output
   * format, no-go list. Used by the AUTO candidate competition (each algorithm
   * gets its own child {@link RoutingEngine}) to construct isolated child
   * engines without sharing parsed/runtime state.
   *
   * <p>What is intentionally NOT copied:
   * <ul>
   *   <li>{@code expctxWay} / {@code expctxNode} — compiled profile
   *       expression contexts. Each child engine compiles its own via
   *       {@link ProfileCache#parseProfile}; the cache makes this cheap.</li>
   *   <li>{@code pm} — profile path model; child re-creates.</li>
   *   <li>{@code anglemeter}, {@code wayfraction}, {@code ilonshortest}/
   *       {@code ilatshortest}, {@code shortestmatch}, {@code inverseDirection} —
   *       mutable search state.</li>
   *   <li>{@code messageHandler}, {@code ai} — caller-specific output state.</li>
   * </ul>
   * Adding new request-level fields? Mirror them here so the child engine
   * receives them.
   */
  public RoutingContext copyRequestFields() {
    RoutingContext c = new RoutingContext();
    c.localFunction = this.localFunction;
    c.keyValues = this.keyValues;
    c.profileTimestamp = this.profileTimestamp;
    c.rawTrackPath = this.rawTrackPath;
    c.rawAreaPath = this.rawAreaPath;
    c.alternativeIdx = this.alternativeIdx;
    c.memoryclass = this.memoryclass;
    c.processUnusedTags = this.processUnusedTags;
    c.forceSecondaryData = this.forceSecondaryData;
    c.useDynamicDistance = this.useDynamicDistance;
    c.buildBeelineOnRange = this.buildBeelineOnRange;
    c.correctMisplacedViaPoints = this.correctMisplacedViaPoints;
    c.correctMisplacedViaPointsDistance = this.correctMisplacedViaPointsDistance;
    c.continueStraight = this.continueStraight;
    c.startDirection = this.startDirection;
    c.startDirectionValid = this.startDirectionValid;
    c.forceUseStartDirection = this.forceUseStartDirection;
    c.roundTripDistance = this.roundTripDistance;
    c.roundTripLength = this.roundTripLength;
    c.roundTripDirectionAdd = this.roundTripDirectionAdd;
    c.roundTripPoints = this.roundTripPoints;
    c.allowSamewayback = this.allowSamewayback;
    c.roundTripAlgorithm = this.roundTripAlgorithm;
    // AUTO children are round-trip engines too, so the edge-membership refTrack
    // gate must follow the parent (the child constructor sets it again from engineMode).
    c.roundTrip = this.roundTrip;
    // Strictness must follow the parent into AUTO children: otherwise a strict
    // request runs lenient children that adopt QUALITY best-effort tracks and
    // report no errorMessage, so the parent's strict re-gate finds no winner
    // but can only surface "unknown" instead of the child's real reason.
    c.roundTripStrictQuality = this.roundTripStrictQuality;
    // The desirability flag (issue #15) must reach the GREEDY child spawned by the
    // AUTO competition, where it actually takes effect.
    c.roundTripDesirability = this.roundTripDesirability;
    // The capsule flag must likewise reach the GREEDY child spawned by AUTO.
    c.roundTripCapsule = this.roundTripCapsule;
    // Via-steering flag reaches the GREEDY child spawned by AUTO; the derived dense-area map (built
    // after the grid expansion) is read-only during leg routing, so aliasing the reference is safe.
    c.roundTripSteerVias = this.roundTripSteerVias;
    c.denseAreaMap = this.denseAreaMap;
    // Densification request inputs (the effective explicitViaDensify flag is
    // recomputed per request in doExplicitViaRoundTrip, so it is not copied).
    // AUTO children currently route a single waypoint and never densify, but
    // copying these keeps a child consistent with the parent if that changes.
    c.explicitViaDensifyOverride = this.explicitViaDensifyOverride;
    c.explicitViaDensifyAlpha = this.explicitViaDensifyAlpha;
    c.explicitViaDensifyMaxCostFactor = this.explicitViaDensifyMaxCostFactor;
    // roundTripIsochrone is intentionally NOT copied: doRoundTrip() resolves it
    // into roundTripAlgorithm before any child is spawned, so the algorithm
    // (copied above) is the single source of truth in child contexts.
    c.outputFormat = this.outputFormat;
    c.waypointCatchingRange = this.waypointCatchingRange;
    c.exportWaypoints = this.exportWaypoints;
    c.exportCorrectedWaypoints = this.exportCorrectedWaypoints;
    c.poipoints = this.poipoints;
    // Defensive copy: the child engine's doRouting() appends synthetic nogo
    // points (continueStraight handling) to its nogopoints list. Aliasing the
    // parent's list would leak those child-only nogos back into the parent and
    // contaminate every subsequent AUTO candidate and the final adopted route.
    c.nogopoints = this.nogopoints == null ? null : new ArrayList<>(this.nogopoints);
    c.inverseRouting = this.inverseRouting;
    c.turnInstructionMode = this.turnInstructionMode;
    c.turnInstructionCatchingRange = this.turnInstructionCatchingRange;
    c.turnInstructionRoundabouts = this.turnInstructionRoundabouts;
    c.showTime = this.showTime;
    c.showspeed = this.showspeed;
    c.showSpeedProfile = this.showSpeedProfile;
    return c;
  }

}
