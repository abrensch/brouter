package btools.router.roundtrip;

import btools.router.OsmNodeNamed;

/**
 * Input to the optimized FAST waypoint placement. The caller resolves every knob
 * (variety-seed jitter already applied), so placement is a pure function of this
 * request plus the road network. The directional-lobe via cap is a compile-time
 * geometry constant ({@link FastWaypointPlanner#LOBE_VIA_CAP}), not carried here.
 */
public final class FastPlacementRequest {

  /** The loop's start (and closing) waypoint. */
  final OsmNodeNamed start;

  /** Effective search radius in meters (post-variety-seed). */
  final double searchRadius;

  /** Effective requested direction in degrees, or {@code < 0} for none. */
  final double direction;

  /** Target waypoint count for the loop (start included). */
  final int targetPoints;

  /** Directional-lobe mode: on when the caller supplied a start bearing. */
  final boolean directional;

  public FastPlacementRequest(OsmNodeNamed start, double searchRadius, double direction,
                       int targetPoints, boolean directional) {
    this.start = start;
    this.searchRadius = searchRadius;
    this.direction = direction;
    this.targetPoints = targetPoints;
    this.directional = directional;
  }
}
