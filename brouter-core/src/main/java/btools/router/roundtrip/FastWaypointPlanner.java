package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.util.CheapRuler;

import java.util.*;

/**
 * Optimized FAST round-trip waypoint generation: bearing generation, probing,
 * narrow-arc radius correction, confidence filtering, spread selection, loop
 * ordering, safeguard substitution, and the ring/circle fallbacks all live here
 * so their call order cannot drift across call sites — both regressions this
 * module descends from were exactly that drift.
 *
 * <p>Scope: only the optimized FAST path ({@code roundtrip.fast.optimized},
 * default on). ISOCHRONE, legacy A/B FAST, explicit-via and same-way-back use
 * the engine's shared helpers directly; AUTO reaches this module through its
 * FAST candidate child engines.
 *
 * <p>The planner reaches the engine only through the narrow
 * {@link FastPlacementOps} seam, so scenario tests can drive it with a
 * deterministic fake instead of segment files.
 */
public final class FastWaypointPlanner {

  // Re-probe at the corrected radius when the scale from the actual viable
  // directions exceeds the nominal full-ring scale by this factor — the
  // narrow-arc (constrained terrain) case where the pre-probe shrink would
  // otherwise roughly halve the loop length.
  private static final double FAST_RESCALE_TRIGGER = 1.15;

  private final FastPlacementOps ops;

  public FastWaypointPlanner(FastPlacementOps ops) {
    this.ops = ops;
  }

  /**
   * Bearings for a directional loop lobe: {@code count} vias fanned across the
   * forward arc toward {@code direction} (arc half-width 90-180/points, so a
   * wider fan for more points). This is what makes FAST head in the requested
   * direction instead of encircling the start. Deterministic, so the derived
   * radius scale — and thus the loop length — is stable.
   */
  /**
   * Via cap for the directional lobe. A handful of well-spread vias makes a
   * clean directional loop; beyond this the vias crowd the forward arc and the
   * router weaves detours that inflate the routed length past target. It also
   * bounds the lobe's arc width: the fan half-span is {@code 90 - 180/points}
   * and {@code points = viaCount + 1}, so the cap holds the forward fan at
   * ~120° (five vias) rather than letting it widen toward a half-circle.
   *
   * <p>A compile-time geometry constant, not a runtime knob: nothing tunes it
   * per deployment, and changing it reshapes every medium/long FAST loop (a
   * calibration change that needs golden-signature recapture, not a restart).
   */
  static final int LOBE_VIA_CAP = 5;

  static double[] directionalLobeBearings(double direction, int count) {
    int points = count + 1;
    double[] b = new double[count];
    for (int i = 0; i < count; i++) {
      double anAngle = 90.0 - 180.0 * (i + 1) / points;
      b[i] = ((direction - anAngle) % 360 + 360) % 360;
    }
    return b;
  }

  /** Largest distance (m) from the start (index 0) to any placed via — used to
   *  detect a degenerate loop whose vias collapsed onto a tiny cluster. */
  static double maxViaDistFromStart(List<OsmNodeNamed> wps) {
    OsmNodeNamed s = wps.get(0);
    double max = 0;
    for (int i = 1; i < wps.size(); i++) {
      double d = CheapRuler.distance(s.ilon, s.ilat, wps.get(i).ilon, wps.get(i).ilat);
      if (d > max) max = d;
    }
    return max;
  }

  /**
   * The spread-selected direction subset placement will use — shared with the
   * narrow-arc rescale trigger so it scores exactly the set placement consumes.
   * Returned length doubles as placement's via-count target.
   */
  private static double[] selectPreferredDirections(double[] viable, int targetPoints,
                                                    double startDirection) {
    int needed = Math.min(Math.max(2, targetPoints - 1), viable.length);
    double anchor = startDirection >= 0 ? startDirection : 0;
    return needed >= viable.length ? viable
      : PlacementGeometry.selectSpreadDirections(viable, needed, anchor);
  }

  /**
   * Radius scale for the bearings FAST placement will actually route: apply the
   * confidence filter, select the preferred subset, then put that subset in
   * loop order before measuring consecutive chord gaps.
   */
  static double fastRingRadiusScale(ProbeResult probe, int targetPoints,
                                    double startDirection) {
    double[] viable = PlacementGeometry.filterByProbeConfidence(probe, targetPoints);
    if (viable == null || viable.length < 2) {
      return 1.0;
    }
    double anchor = startDirection >= 0 ? startDirection : 0;
    double[] selected = selectPreferredDirections(viable, targetPoints, startDirection);
    return PlacementGeometry.computeRadiusScale(
      PlacementGeometry.sortDirectionsForLoop(selected, anchor), targetPoints);
  }

  /**
   * Ring-probe confidence filter: drop fragile single-snap bearings when enough
   * two-snap-strong ones remain (see
   * {@link PlacementGeometry#filterByProbeConfidence}). The filtered set also
   * bounds the replacement-selection substitutes, so a fragile bearing cannot
   * re-enter as a stand-in for a dropped one.
   */
  private static ProbeResult withConfidenceFilteredDirections(ProbeResult probe, int targetPoints) {
    if (probe == null) {
      return null;
    }
    double[] filtered = PlacementGeometry.filterByProbeConfidence(probe, targetPoints);
    if (filtered == null || filtered.length == probe.viableDirections.length) {
      return probe;
    }
    return new ProbeResult(filtered, probe.scored, probe.startMatch);
  }

  /**
   * Ring-mode probe refinement, one owner for both ring call sites (initial ring
   * and the directional lobe's ring fallback), so scale measurement and the
   * returned confidence-filtered probe cannot use different placement rules.
   *
   * <p>Narrow-arc correction: the pre-probe shrink assumes a near-full ring; in
   * constrained terrain (coast, valley) viable bearings bunch into a narrow arc
   * whose correct scale — measured from the ACTUAL selected directions, which
   * never shrinks a narrow arc — is much larger. One corrective re-probe at that
   * radius keeps the loop near target length instead of roughly half of it.
   * Directional lobes skip this: their scale derives from the exact lobe
   * distribution.
   */
  private ProbeResult refineRingProbe(ProbeResult probe, OsmNodeNamed start,
                                      double searchRadius, double nominalScale,
                                      double[] bearings, int targetPoints,
                                      double direction) {
    if (probe == null || probe.viableDirections.length < 3) {
      return probe;
    }
    double actualScale = fastRingRadiusScale(probe, targetPoints, direction);
    if (actualScale > nominalScale * FAST_RESCALE_TRIGGER) {
      ops.log("optimized FAST placement: narrow viable arc, re-probing at scale "
        + String.format(Locale.US, "%.2f", actualScale)
        + " (nominal " + String.format(Locale.US, "%.2f", nominalScale) + ")");
      ProbeResult rescaled = ops.probe(start, searchRadius * actualScale, bearings);
      if (rescaled != null && rescaled.viableDirections.length >= 3) {
        probe = rescaled;
      }
    }
    return withConfidenceFilteredDirections(probe, targetPoints);
  }

  /**
   * Optimized FAST waypoint placement. Distribute the vias as a directional lobe
   * toward the resolved bearing (so the loop heads that way — never an
   * encircling ring as the primary shape; the ring is the placement/routing
   * fallback). Scale the probe radius from that exact distribution
   * so the loop hits target length — the lobe's perimeter/radius ratio differs
   * from a ring. Cap the lobe's via count: a handful of well-spread vias gives a
   * clean directional loop, while many just add road detours that inflate the
   * routed length past target.
   */
  public FastPlacementOutcome place(FastPlacementRequest req) {
    List<OsmNodeNamed> skeleton = new ArrayList<>();
    skeleton.add(req.start);
    int viaCount = req.directional
      ? Math.max(3, Math.min(req.targetPoints - 1, LOBE_VIA_CAP))
      : Math.max(3, req.targetPoints - 1);
    double[] bearings;
    double fastScale;
    if (req.directional) {
      bearings = directionalLobeBearings(req.direction, viaCount);
      fastScale = PlacementGeometry.computeRadiusScale(
        PlacementGeometry.sortDirectionsForLoop(bearings, req.direction), req.targetPoints);
    } else {
      // Dense ring probe (matching the pre-refactor 12-direction sweep so forming
      // stays as robust), scaled to the loop's via count; placement caps below.
      bearings = PlacementGeometry.fullCircleBearings(Math.max(12, viaCount));
      fastScale = PlacementGeometry.computeRadiusScale(PlacementGeometry.sortDirectionsForLoop(
        PlacementGeometry.fullCircleBearings(viaCount), 0), req.targetPoints);
    }
    ProbeResult probe = ops.probe(req.start, req.searchRadius * fastScale, bearings);
    if (!req.directional) {
      probe = refineRingProbe(probe, req.start, req.searchRadius, fastScale,
        bearings, req.targetPoints, req.direction);
    }
    int placed = (probe != null && probe.viableDirections.length >= 3)
      ? placeWaypointsFromProbeMatches(skeleton, probe, req.direction, req.targetPoints)
      : 0;
    FastPlacementOutcome.Path path = req.directional
      ? FastPlacementOutcome.Path.DIRECTIONAL_LOBE : FastPlacementOutcome.Path.RING;
    // Fall back to an encircling ring when the directional lobe is too road-poor
    // to close a loop OR its vias collapse onto a tiny cluster near the start (a
    // degenerate loop). The ring finds spread-out roads and forms a real loop —
    // the pre-directional behaviour — so a bearing never costs a result.
    boolean degenerate = placed >= PlacementGeometry.MIN_ROUNDTRIP_VIAS
      && maxViaDistFromStart(skeleton) < 0.4 * req.searchRadius * fastScale;
    if (req.directional && (placed < PlacementGeometry.MIN_ROUNDTRIP_VIAS || degenerate)) {
      skeleton.subList(1, skeleton.size()).clear();
      int ringCount = Math.max(3, req.targetPoints - 1);
      double[] ring = PlacementGeometry.fullCircleBearings(Math.max(12, ringCount));
      double ringScale = PlacementGeometry.computeRadiusScale(PlacementGeometry.sortDirectionsForLoop(
        PlacementGeometry.fullCircleBearings(ringCount), 0), req.targetPoints);
      probe = ops.probe(req.start, req.searchRadius * ringScale, ring);
      probe = refineRingProbe(probe, req.start, req.searchRadius, ringScale,
        ring, req.targetPoints, req.direction);
      placed = (probe != null && probe.viableDirections.length >= 3)
        ? placeWaypointsFromProbeMatches(skeleton, probe, req.direction, req.targetPoints)
        : 0;
      path = FastPlacementOutcome.Path.RING_FALLBACK;
    }
    if (placed >= PlacementGeometry.MIN_ROUNDTRIP_VIAS) {
      return new FastPlacementOutcome(skeleton, path, null);
    }
    // Too few reachable vias survived dedup/island filtering (or the probe
    // was too thin): drop whatever was placed and fall back to the circle
    // path, which validateAndAdjustWaypoints then snaps/prunes with its own
    // min-via floor — so the loop never degenerates to start->start.
    skeleton.subList(1, skeleton.size()).clear();
    String reason = "optimized FAST placement yielded " + placed
      + " reachable vias (<" + PlacementGeometry.MIN_ROUNDTRIP_VIAS + "); falling back to circle";
    ops.log(reason);
    ops.circleFallbackValidated(skeleton, req.direction, req.searchRadius, req.targetPoints);
    return new FastPlacementOutcome(skeleton, FastPlacementOutcome.Path.CIRCLE_FALLBACK, reason);
  }

  /**
   * FAST placement that reuses the probe's already-snapped road nodes as vias,
   * deduping any that collapse onto the same node or the start (which both skips
   * the redundant {@code validateAndAdjustWaypoints} re-matching pass and fixes
   * the stacked-waypoint bug — multiple bearings snapping to one node kept as
   * duplicates).
   *
   * <p>Safeguards mirror the validation this path skips: ferry-like and
   * profile-hostile matches are never committed, and a dropped direction is
   * replaced from the remaining viable pool instead of shrinking the ring.
   * Selection already filters retained matches with the same rule, so the
   * ferry/hostile counters here are belt-and-braces and should stay at zero.
   */
  int placeWaypointsFromProbeMatches(List<OsmNodeNamed> waypoints, ProbeResult probe,
                                     double startDirection, int targetPoints) {
    OsmNodeNamed start = waypoints.get(0);
    // The caller distributed the bearings (a directional lobe toward the requested
    // bearing, or a full ring). Cap to the target via count (the encircle fallback
    // probes a denser ring than we want vias), order for the loop, and reuse the
    // snapped nodes. The safeguards below drop ferry-like / profile-hostile /
    // duplicate / islanded picks and REPLACE them from the remaining snapped
    // bearings, so a drop never shrinks the via count while substitutes exist.
    double anchor = startDirection >= 0 ? startDirection : 0;
    double[] viable = probe.viableDirections;
    double[] preferred = selectPreferredDirections(viable, targetPoints, startDirection);
    int needed = preferred.length;

    Map<Double, MatchedWaypoint> byDir = new HashMap<>();
    for (ProbeDirection pd : probe.scored) {
      if (pd.bestMatch != null && pd.bestMatch.crosspoint != null) {
        byDir.put(pd.direction, pd.bestMatch);
      }
    }

    int deduped = 0;
    int islanded = 0;
    int ferryLike = 0;
    int hostile = 0;
    // Two ordered passes: the spread-selected directions first, then the rest
    // of the viable ring as substitutes for any direction a safeguard drops.
    List<Double> chosen = new ArrayList<>();
    Set<Double> visited = new HashSet<>();
    Set<Long> usedNodes = new HashSet<>();
    usedNodes.add(start.getIdFromPos());
    double[][] passes = {preferred, viable};
    for (int p = 0; p < passes.length && chosen.size() < needed; p++) {
      for (double dir : passes[p]) {
        if (chosen.size() >= needed) break;
        if (!visited.add(dir)) continue;
        MatchedWaypoint m = byDir.get(dir);
        if (m == null || m.crosspoint == null) continue;
        // A via committed onto a ferry-like edge routes the loop across the
        // ferry (same rule as every other snap-validation site); a via on a
        // profile-hostile road forces the loop through it.
        FastPlacementOps.SnapUsability usability = ops.snapUsability(m);
        if (usability == FastPlacementOps.SnapUsability.FERRY_LIKE) {
          ferryLike++;
          continue;
        }
        if (usability == FastPlacementOps.SnapUsability.PROFILE_HOSTILE) {
          hostile++;
          continue;
        }
        // Dedup: drop a via that lands on the start or an already-chosen node.
        long key = m.crosspoint.getIdFromPos();
        if (usedNodes.contains(key)) {
          deduped++;
          continue;
        }
        // Reachability guard: drop a via stranded on a small island disconnected
        // from the start, rather than letting it fail the whole loop at routing.
        if (!ops.isViaReachable(m, probe.startMatch)) {
          islanded++;
          continue;
        }
        usedNodes.add(key);
        chosen.add(dir);
      }
    }

    double[] loopDirs = new double[chosen.size()];
    for (int i = 0; i < loopDirs.length; i++) {
      loopDirs[i] = chosen.get(i);
    }
    loopDirs = PlacementGeometry.sortDirectionsForLoop(loopDirs, anchor);
    int added = 0;
    for (double dir : loopDirs) {
      MatchedWaypoint m = byDir.get(dir);
      OsmNodeNamed onn = new OsmNodeNamed(
        new OsmNode(m.crosspoint.getILon(), m.crosspoint.getILat()));
      onn.name = "rt" + (++added);
      waypoints.add(onn);
    }

    OsmNodeNamed closing = new OsmNodeNamed(start);
    closing.name = "to_rt";
    waypoints.add(closing);

    ops.log("placeWaypointsFromProbeMatches: " + added + " road-snapped vias"
      + (deduped > 0 ? " (" + deduped + " deduped)" : "")
      + (islanded > 0 ? " (" + islanded + " islanded dropped)" : "")
      + (ferryLike > 0 ? " (" + ferryLike + " ferry-like dropped)" : "")
      + (hostile > 0 ? " (" + hostile + " profile-hostile dropped)" : "")
      + " from " + viable.length + " snapped bearings");
    return added;
  }
}
