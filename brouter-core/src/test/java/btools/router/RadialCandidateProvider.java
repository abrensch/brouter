package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Test-only geometric candidate source: places candidates on a ring at
 * {@code airRadius} around the current position. Production planners use the
 * graph-native / isochrone-blended providers; this ring is kept in tests as a
 * deterministic, segment-free provider for planner unit tests.
 */
final class RadialCandidateProvider implements RoundTripCandidateProvider {

  private static final int DEFAULT_DIRECTIONS = 12;

  private final int directions;

  RadialCandidateProvider() {
    this(DEFAULT_DIRECTIONS);
  }

  RadialCandidateProvider(int directions) {
    this.directions = directions;
  }

  @Override
  public List<CandidatePoint> candidatesForStep(
    int fromIlon, int fromIlat, double airRadius,
    int step, int totalSteps,
    int startIlon, int startIlat,
    double startDirection,
    OsmTrack refTrack) {
    // baseAngle: align the first ring slot with the user's direction on the
    // first two steps so the loop heads where the user asked; thereafter let
    // the scorer's loop/direction terms decide.
    double baseAngle = (step <= 2 && startDirection >= 0) ? startDirection : 0;
    double angleStep = 360.0 / directions;
    List<CandidatePoint> points = new ArrayList<>(directions);
    for (int i = 0; i < directions; i++) {
      double bearing = CheapAngleMeter.normalize(baseAngle + i * angleStep);
      int[] dest = CheapRuler.destination(fromIlon, fromIlat, airRadius, bearing);
      CandidatePoint cp = new CandidatePoint();
      cp.ilon = dest[0];
      cp.ilat = dest[1];
      cp.bearing = bearing;
      points.add(cp);
    }
    return points;
  }
}
