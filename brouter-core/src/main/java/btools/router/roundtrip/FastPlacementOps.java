package btools.router.roundtrip;

import btools.mapaccess.MatchedWaypoint;
import btools.router.OsmNodeNamed;
import btools.router.RoutingEngine;

import java.util.List;

/**
 * Narrow seam between {@link FastWaypointPlanner} and the routing engine's
 * shared probe/snap/island/circle primitives. Deliberately small — it must NOT
 * expose the node cache, {@code findTrack}, deadlines, or mutable engine fields.
 * Production adapter is {@link RoutingEngine#fastPlacementOps()}; tests use a
 * deterministic fake.
 */
public interface FastPlacementOps {

  /** Usability verdict for a retained probe match, in check order. */
  enum SnapUsability { OK, FERRY_LIKE, PROFILE_HOSTILE }

  /**
   * FAST-tier reachability probe: snap the bearing grid to roads and retain the
   * best usable match per direction, hiding the engine's cache reset and
   * waypoint matcher as one operation.
   */
  ProbeResult probe(OsmNodeNamed start, double searchRadius, double[] bearings);

  /**
   * Ferry/profile usability of a match — the same rule every snap-validation
   * site applies. The planner re-checks committed vias as belt-and-braces.
   */
  SnapUsability snapUsability(MatchedWaypoint m);

  /**
   * Island guard: {@code false} only when {@code via} sits on a small road
   * component that cannot reach the start.
   */
  boolean isViaReachable(MatchedWaypoint via, MatchedWaypoint startMatch);

  /**
   * Geometric circle fallback, validated: append circle vias + closing point to
   * {@code skeleton} and run the shared matching pass, so the caller never
   * decides whether the skeleton needs another pass.
   */
  void circleFallbackValidated(List<OsmNodeNamed> skeleton, double direction,
                               double searchRadius, int targetPoints);

  /** Engine log line; a no-op in {@code quite} child engines (AUTO candidates). */
  void log(String msg);
}
