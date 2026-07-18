package btools.router.roundtrip;

import java.io.File;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;

/**
 * Engine seam role: read-only view of the ROUND-TRIP REQUEST FIELDS behind
 * {@link RoundTripRequestState} — nothing here mutates those. The
 * {@link #routingContext()} it hands out is the request's live configuration
 * object, deliberately shared: tiers do write general routing knobs through
 * it (waypoint catching range, dynamic-distance mode).
 */
public interface EngineContext {

  /**
   * The request's live configuration object, deliberately shared (see class
   * doc). Boundary: do not mutate ROUND-TRIP REQUEST-STATE fields through it
   * (those are orchestrator-owned); the documented general routing knobs
   * (waypoint catching range, dynamic-distance mode) are live and mutable.
   */
  RoutingContext routingContext();

  /** Segment directory (used to construct child engines). */
  File segmentDir();

  boolean isRoundTripMode();

  /** True while an explicit-via round trip is being generated. */
  boolean explicitViaRoundTrip();

  /** Active search radius; 0 outside round-trip requests. */
  double roundTripSearchRadius();

  boolean roundTripFerriesAllowed();

  /** True when the uniform quality gate would hard-reject this verdict. */
  boolean roundTripQualityHardReject(RoundTripQualityResult quality);

  /** Area-info based random direction pick. */
  double getRandomDirectionFromData(OsmNodeNamed wp, double searchRadius);

  /** v1.7.8 geometric circle placement. */
  void buildPointsFromCircle(List<OsmNodeNamed> waypoints, double startAngle,
                             double searchRadius, int points);

  /** Recalculate a track's totals. */
  void recalcTrack(OsmTrack track);

  /** The optimized-FAST placement seam of this engine. */
  FastPlacementOps fastPlacementOps();

  /** Milliseconds left of the request budget; Long.MAX_VALUE when untimed. */
  long remainingRequestBudgetMs();
}
