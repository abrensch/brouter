package btools.router.roundtrip;

import btools.mapaccess.OsmPos;
import btools.router.OsmTrack;
import btools.util.CheapRuler;

/**
 * A road-native candidate node from a {@code RoutingEngine#runIsochroneExpansion}
 * Dijkstra: in a given angular bucket, the node whose path-cost from the start is
 * closest to a cost target — the full budget ({@link #sourceContour}=100,
 * "frontier-max") or an intermediate contour (25/50/75%). Used by
 * {@link IsochroneCandidateProvider} as the QUALITY-tier candidate pool, a
 * road-on-the-ground replacement for the radial provider's geometric ring.
 *
 * <p>Implements {@link OsmPos} to interoperate with the engine's distance/identity
 * helpers.
 */
public final class IsoCandidate implements OsmPos {

  /** Longitude (1e6 ilon units). */
  public final int ilon;
  /** Latitude (1e6 ilat units). */
  public final int ilat;
  /** Compass bearing from the loop start to this candidate, in {@code [0, 360)}. */
  final double bearingFromStart;
  /** Air distance from the loop start in meters. */
  public final double airDistanceFromStart;
  /** Routing cost from the loop start in cost units (≈ meters × profile costfactor). */
  final int costFromStart;
  /** Angular bucket index (0-35 for the default 10°-wide buckets). */
  public final int bucket;
  /** Population count of this bucket — confidence signal. {@code <3} ≈ one-shot dead-end. */
  final int bucketHits;
  /**
   * Source contour (25/50/75 = cost-budget percentage; 100 = frontier-max).
   * The filter prefers the higher contour within each bucket.
   */
  public final int sourceContour;
  /** Optional exact graph path to this candidate, available for per-step graph-native candidates. */
  final OsmTrack routedTrack;

  public IsoCandidate(int ilon, int ilat, double bearingFromStart, double airDistanceFromStart,
               int costFromStart, int bucket, int bucketHits, int sourceContour) {
    this(ilon, ilat, bearingFromStart, airDistanceFromStart,
      costFromStart, bucket, bucketHits, sourceContour, null);
  }

  public IsoCandidate(int ilon, int ilat, double bearingFromStart, double airDistanceFromStart,
               int costFromStart, int bucket, int bucketHits, int sourceContour,
               OsmTrack routedTrack) {
    this.ilon = ilon;
    this.ilat = ilat;
    this.bearingFromStart = bearingFromStart;
    this.airDistanceFromStart = airDistanceFromStart;
    this.costFromStart = costFromStart;
    this.bucket = bucket;
    this.bucketHits = bucketHits;
    this.sourceContour = sourceContour;
    this.routedTrack = routedTrack;
  }

  @Override
  public int getILon() {
    return ilon;
  }

  @Override
  public int getILat() {
    return ilat;
  }

  /** Elevation is not tracked during isochrone expansion. */
  @Override
  public short getSElev() {
    return Short.MIN_VALUE;
  }

  @Override
  public double getElev() {
    return Short.MIN_VALUE / 4.0;
  }

  @Override
  public int calcDistance(OsmPos p) {
    return (int) (CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat()) + 0.5);
  }

  @Override
  public long getIdFromPos() {
    return ((long) ilon) << 32 | (ilat & 0xFFFFFFFFL);
  }
}
