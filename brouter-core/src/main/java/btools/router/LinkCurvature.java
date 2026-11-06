package btools.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;
import btools.util.CheapAngleMeter;
import btools.util.CheapRuler;

/**
 * Makes curvy roads cheaper, for road motorcyclists.
 *
 * A profile selects it by turncost = -12 (see RoutingContext.getLinkCostModifier);
 * the turn angle penalty is switched off then.
 *
 * The calculation is done once per link, from its whole geometry, before the
 * engine processes the sections of the link:
 * - the curvature c of the link is computed, 0 = straight ... MAX_C = very curvy,
 * - the engine then multiplies every section cost of the link by 1 - c.
 *
 * How c is computed: the link is cut into pieces of at most 20 m (WINDOW / 2).
 * For each piece, look at the 40 m of road around its middle (WINDOW) and measure
 * how much the direction of travel changes over them.
 * An arc of 40 m that turns by an angle a has the radius R = 40 m / a, so the
 * bigger the change of direction, the tighter the curve; e.g. a change of 90 deg
 * over 40 m is a curve with R = 25 m. Every piece gets the band of its radius
 * (LIMITS: hairpin < 30 m, sharp < 60 m, medium < 100 m,
 * gentle < 175 m, otherwise straight), and c is the mean of the band weights
 * (WEIGHTS), weighted by length. Links shorter than MIN_LENGTH get c = 0,
 * they are too short to tell.
 *
 * Serpentine rule: the mean alone gives only about c = 0.5 even on the best
 * serpentines, because the straighter pieces between the hairpins pull it down.
 * Mountain passes should stand out clearly, so a link that has at least 20 %
 * (SERP_SHARE) and at least 35 m (SERP_MIN_LENGTH) of its length in tight curves
 * (R < 60 m, SERP_BANDS) gets at least c = 0.8 (SERP_C). Both conditions are needed:
 * the share alone would also hit a short link with one corner at a junction,
 * the length alone a long road with a few scattered bends.
 *
 * The radius bands and their weights come from the Curvature project by Adam Franco
 * (https://github.com/adamfranco/curvature, roadcurvature.com), which finds fun roads
 * in OSM data. Curvature sums the weighted lengths of a whole road; here c is their
 * mean, so it does not grow with the length of the link.
 *
 * Stateless, so one instance can be shared by all routing threads.
 */
final class LinkCurvature implements LinkCostModifier {

  // window length W in m
  private static final double WINDOW = 40.;
  // |angle| at the source node above this is a real turn, not road shape
  private static final double JUNCTION_MAX_DEG = 90.;
  // shorter links get c = 0
  private static final double MIN_LENGTH = 40.;
  // max. distance a vertex angle is smeared to each side, in m
  private static final double SPREAD_CAP = 10.;
  // radius band limits in m; the same as the change of direction over the 40 m window:
  //   band      R         change of direction
  //   hairpin   < 30 m    > 76 deg
  //   sharp     < 60 m    > 38 deg
  //   medium    < 100 m   > 23 deg
  //   gentle    < 175 m   > 13 deg
  //   straight  otherwise
  private static final double[] LIMITS = {30., 60., 100., 175.};
  // weights per band, hairpin ... straight
  private static final double[] WEIGHTS = {2., 1.6, 1.3, 1., 0.};
  // serpentine rule: number of tight bands counted from the hairpin one (2 = R < 60 m)
  private static final int SERP_BANDS = 2;
  // serpentine rule: min. share of the link length in tight curves
  private static final double SERP_SHARE = 0.2;
  // serpentine rule: min. length in tight curves, in m
  private static final double SERP_MIN_LENGTH = 35.;
  // serpentine rule: c is raised at least to this
  private static final double SERP_C = 0.8;
  // cap of c, a link costs at least a tenth
  private static final double MAX_C = 0.9;

  // EXPORT (temporary, curvature verification): every evaluated link once, as
  // GeoJSONSeq in the format of LinkDump plus c, bands, serpentine.
  // Switch off by commenting out the first line and in the second.
  private static final String EXPORT_FILE = System.getProperty("java.io.tmpdir") + "/link-curvature.geojsonl";
  // private static final String EXPORT_FILE = null;
  private static final String EXPORT_TRACK = "search-" + System.currentTimeMillis();
  private static Writer exportWriter;
  private static Set<String> exported;
  private static int exportSeq;

  @Override
  public double linkCostFactor(int lon0, int lat0, OsmNode sourceNode, OsmTransferNode transferNodes, OsmNode targetNode) {
    int n = 1;
    for (OsmTransferNode tn = transferNodes; tn != null; tn = tn.next) {
      n++;
    }

    // the points of the link in the direction of travel, n sections
    int[] lons = new int[n + 1];
    int[] lats = new int[n + 1];
    short[] eles = new short[n + 1];
    lons[0] = sourceNode.ilon;
    lats[0] = sourceNode.ilat;
    eles[0] = sourceNode.selev;
    int k = 1;
    for (OsmTransferNode tn = transferNodes; tn != null; tn = tn.next) {
      lons[k] = tn.ilon;
      lats[k] = tn.ilat;
      eles[k++] = tn.selev;
    }
    lons[n] = targetNode.ilon;
    lats[n] = targetNode.ilat;
    eles[n] = targetNode.selev;

    // dist and angle of each section as in OsmPath, without its RoutingContext
    boolean isRouteStart = lon0 == -1 && lat0 == -1;
    CheapAngleMeter anglemeter = new CheapAngleMeter();
    int[] dist = new int[n];
    double[] angle = new double[n];
    for (int i = 0; i < n; i++) {
      dist[i] = calcDistance(lons[i], lats[i], lons[i + 1], lats[i + 1]);
      if (i == 0) {
        angle[i] = isRouteStart ? 0. : anglemeter.calcAngle(lon0, lat0, lons[0], lats[0], lons[1], lats[1]);
      } else {
        angle[i] = anglemeter.calcAngle(lons[i - 1], lats[i - 1], lons[i], lats[i], lons[i + 1], lats[i + 1]);
      }
    }

    double[] bands = bands(dist, angle, isRouteStart);
    double c = 0.;
    boolean serpentine = false;
    if (bands != null) {
      c = weightedC(bands);
      serpentine = isSerpentine(bands);
      if (serpentine) {
        c = Math.max(c, SERP_C);
      }
      c = Math.min(c, MAX_C);
    }

    if (EXPORT_FILE != null) { // EXPORT (temporary)
      export(lon0, lat0, lons, lats, eles, dist, angle, c, bands, serpentine);
    }
    return 1. - c;
  }

  // the same as RoutingContext.calcDistance without the nogo and waypoint matching
  private static int calcDistance(int lon1, int lat1, int lon2, int lat2) {
    double[] lonlat2m = CheapRuler.getLonLatToMeterScales((lat1 + lat2) >> 1);
    double dx = (lon2 - lon1) * lonlat2m[0];
    double dy = (lat2 - lat1) * lonlat2m[1];
    double d = Math.sqrt(dy * dy + dx * dx);
    return (int) Math.max(1.0, Math.round(d));
  }

  /**
   * Length of the link in each radius band (index as in WEIGHTS), null for a link
   * shorter than MIN_LENGTH.
   *
   * @param dist         length of section i (point i to i+1) in m
   * @param angle        signed turn angle at point i in degrees; angle[0] is the turn
   *                     at the source node relative to the previous link
   * @param isRouteStart the link starts the route, angle[0] has no meaning
   */
  static double[] bands(int[] dist, double[] angle, boolean isRouteStart) {
    int n = dist.length;
    int length = 0;
    for (int i = 0; i < n; i++) {
      length += dist[i];
    }
    if (length < MIN_LENGTH) {
      return null;
    }

    // step 1: angle[0] is kept as road shape, except at the route start or a real turn
    double[] ang = new double[n];
    for (int i = 0; i < n; i++) {
      ang[i] = Math.toRadians(angle[i]);
    }
    if (isRouteStart || Math.abs(angle[0]) > JUNCTION_MAX_DEG) {
      ang[0] = 0.;
    }

    // step 2: knots of theta(s). The angle of vertex i rises linearly over
    // [s_i - left, s_i + right], left / right = half of the neighbouring section,
    // capped at SPREAD_CAP; vertex 0 has nothing on its left (the previous link is
    // not part of this one). Two knots per vertex plus the start and the end;
    // the knot positions never decrease.
    double[] ks = new double[2 * n + 2];
    double[] kt = new double[2 * n + 2];
    int k = 1; // ks[0] = kt[0] = 0
    double s = 0.;
    double t = 0.;
    double prev = 0.;
    for (int i = 0; i < n; i++) {
      double d = dist[i];
      ks[k] = s - Math.min(prev / 2., SPREAD_CAP);
      kt[k++] = t;
      t += ang[i];
      ks[k] = s + Math.min(d / 2., SPREAD_CAP);
      kt[k++] = t;
      s += d;
      prev = d;
    }
    ks[k] = length;
    kt[k] = t;

    // step 3: the window centred in each piece of a section (pieces <= W/2); the net
    // turning in it gives the radius, the piece length goes to the band of the radius.
    // Both window ends only move forward, so two cursors walk the knots.
    double half = WINDOW / 2.;
    int[] lo = new int[1];
    int[] hi = new int[1];
    double[] bands = new double[WEIGHTS.length];
    s = 0.;
    for (int i = 0; i < n; i++) {
      double d = dist[i];
      int pieces = Math.max(1, (int) Math.ceil(d / half));
      double piece = d / pieces;
      for (int q = 0; q < pieces; q++) {
        double x = s + (q + 0.5) * piece;
        double a = Math.max(0., x - half);
        double b = Math.min(length, x + half);
        double turn = Math.abs(theta(ks, kt, hi, b) - theta(ks, kt, lo, a));
        bands[bandIndex(b - a, turn)] += piece;
      }
      s += d;
    }
    return bands;
  }

  /**
   * theta(x) on the knots ks / kt, linear between them. cursor[0] is the last knot
   * with ks <= x of the previous call; x must not decrease between the calls.
   */
  private static double theta(double[] ks, double[] kt, int[] cursor, double x) {
    int j = cursor[0];
    while (j + 1 < ks.length && ks[j + 1] <= x) {
      j++;
    }
    cursor[0] = j;
    if (j + 1 >= ks.length) {
      return kt[j];
    }
    // here ks[j] <= x < ks[j + 1], so the knots differ
    return kt[j] + (kt[j + 1] - kt[j]) * (x - ks[j]) / (ks[j + 1] - ks[j]);
  }

  /**
   * Band of the radius R = windowLength / turn (turn in radians). Without the division:
   * R < limit  if  turn * limit > windowLength, which also covers turn = 0 (straight).
   */
  private static int bandIndex(double windowLength, double turn) {
    for (int i = 0; i < LIMITS.length; i++) {
      if (turn * LIMITS[i] > windowLength) {
        return i;
      }
    }
    return LIMITS.length;
  }

  // length-weighted mean of the band weights / 2, clipped at 1
  private static double weightedC(double[] bands) {
    double sum = 0.;
    double length = 0.;
    for (int i = 0; i < bands.length; i++) {
      sum += bands[i] * WEIGHTS[i];
      length += bands[i];
    }
    return Math.min(1., sum / length / 2.);
  }

  /**
   * Serpentine rule: both the share and the length in tight curves must reach the
   * thresholds. The share separates serpentines from long gentle bends, the length
   * a chain of bends from a single junction corner.
   */
  private static boolean isSerpentine(double[] bands) {
    double tight = 0.;
    double length = 0.;
    for (int i = 0; i < bands.length; i++) {
      if (i < SERP_BANDS) {
        tight += bands[i];
      }
      length += bands[i];
    }
    return tight / length >= SERP_SHARE && tight >= SERP_MIN_LENGTH;
  }

  // EXPORT (temporary, curvature verification): one GeoJSON feature per link and line,
  // the properties named as in LinkDump (read by the python scripts), each link
  // (with the point before it) only once
  private static synchronized void export(int lon0, int lat0, int[] lons, int[] lats, short[] eles,
                                          int[] dist, double[] angle, double c, double[] bands, boolean serpentine) {
    int npoints = lons.length;
    String key = lon0 + "," + lat0 + "," + Arrays.toString(lons) + Arrays.toString(lats);
    if (exported == null) {
      exported = new HashSet<>();
    }
    if (!exported.add(key)) {
      return;
    }
    int length = 0;
    for (int i = 0; i < npoints - 1; i++) {
      length += dist[i];
    }
    StringBuilder sb = new StringBuilder(256 + 80 * npoints);
    sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
    for (int i = 0; i < npoints; i++) {
      if (i > 0) sb.append(',');
      sb.append('[').append(deg(lons[i] - 180000000)).append(',').append(deg(lats[i] - 90000000)).append(']');
    }
    sb.append("]},\"properties\":{");
    sb.append("\"track\":\"").append(EXPORT_TRACK).append('"');
    sb.append(",\"seq\":").append(exportSeq++);
    sb.append(",\"length_m\":").append(length);
    sb.append(",\"n_points\":").append(npoints);
    sb.append(",\"partial_start\":false");
    sb.append(",\"origin_ilon\":").append(lon0);
    sb.append(",\"origin_ilat\":").append(lat0);
    sb.append(",\"ilon\":[");
    for (int i = 0; i < npoints; i++) sb.append(i > 0 ? "," : "").append(lons[i]);
    sb.append("],\"ilat\":[");
    for (int i = 0; i < npoints; i++) sb.append(i > 0 ? "," : "").append(lats[i]);
    sb.append("],\"ele\":[");
    for (int i = 0; i < npoints; i++) {
      sb.append(i > 0 ? "," : "").append(eles[i] == Short.MIN_VALUE ? "null" : String.valueOf(eles[i] / 4.));
    }
    sb.append("],\"dist\":[");
    for (int i = 0; i < npoints - 1; i++) sb.append(i > 0 ? "," : "").append(dist[i]);
    sb.append("],\"angle\":[");
    for (int i = 0; i < npoints - 1; i++) {
      sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.2f", angle[i]));
    }
    sb.append("],\"c\":").append(String.format(Locale.ROOT, "%.4f", c));
    sb.append(",\"bands\":");
    if (bands == null) {
      sb.append("null");
    } else {
      sb.append('[');
      for (int i = 0; i < bands.length; i++) {
        sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.2f", bands[i]));
      }
      sb.append(']');
    }
    sb.append(",\"serpentine\":").append(serpentine);
    sb.append("}}\n");
    try {
      if (exportWriter == null) {
        exportWriter = new BufferedWriter(new FileWriter(EXPORT_FILE, true));
      }
      exportWriter.write(sb.toString());
      exportWriter.flush();
    } catch (IOException e) {
      throw new RuntimeException("curvature export: cannot write " + EXPORT_FILE, e);
    }
  }

  private static String deg(int micro) {
    return String.format(Locale.ROOT, "%.6f", micro / 1e6);
  }
}
