package btools.router;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;

/**
 * Shared-corridor crossing probe (report-only, opt-in {@code -Dcorridor.probe=true}).
 *
 * <p>Background (Annecy investigation, 2026-06-11): the self-intersection count
 * is blind to crossings routed THROUGH a shared corridor — the route rides the
 * same edges for a short run (a roundabout arc, a few junction edges) and exits
 * on the opposite side of its earlier pass. Every node revisit inside the run
 * has a shared incident edge, so {@link RoundTripQualityGate#isTransverseRevisit}'s
 * shared-edge guard classifies each one as retrace and the run never gets
 * evaluated as a whole. Verified examples (annecy fastbike 50km, AUTO):
 * <ul>
 *   <li>Rond-Point de la Contamine (45.9475,6.0391): outbound Route de Nonglard
 *       S→NW, return Route de Clermont W→E, 3 shared ring nodes — a genuine,
 *       user-visible X; counted 0.</li>
 *   <li>Route des Diacquenods (45.972,6.134): 259m ridden twice in the SAME
 *       direction with interleaved approaches — a true figure-eight; counted 0.</li>
 * </ul>
 *
 * <p>Working hypothesis to be labeled before any detector change (the
 * ADR-0002 discipline): traversal DIRECTION discriminates. Same-direction
 * shared runs with interleaved external attachments are genuine crossings
 * (roundabouts/oneways are always same-direction); opposite-direction runs are
 * retrace/reuse — the Route de Clermont case topologically crosses (attachment
 * parity) but a cyclist experiences riding the same two-way road twice, which
 * reuse%/CorridorOverlapIndex already grade, so counting it as a crossing
 * would mislabel and double-punish.
 *
 * <p>Per corridor this probe emits: location, shared length/node count,
 * traversal direction, a centroid-bearing interleave verdict (probe points
 * ~150m arc-wise outside the run; production needs exact end-node side tests),
 * roundabout/oneway flags from the shared edges' way tags, and the candidate
 * verdict {@code sameDir && interleaved}. One CSV + one labeling GeoJSON per
 * case×variant in {@code build/corridor-probe/} (fork-safe: distinct files).
 */
final class SharedCorridorProbe {

  /** Arc distance of the external probe points outside the shared run. */
  private static final double PROBE_ARC_M = 150;

  private SharedCorridorProbe() {
  }

  static void run(OsmTrack track, String testLabel, String regionName, String variant,
                  File projectDir) {
    try {
      if (track == null || track.nodes == null || track.nodes.size() < 5) return;
      List<OsmPathElement> nodes = track.nodes;
      int n = nodes.size();
      double[] cum = new double[n];
      for (int k = 1; k < n; k++) cum[k] = cum[k - 1] + nodes.get(k - 1).calcDistance(nodes.get(k));
      double perim = cum[n - 1];

      // Corridor enumeration + exact crossing verdict come from the (dark)
      // production logic — the probe labels EXACTLY what would ship. The
      // centroid interleave verdict is kept as a comparison column.
      List<int[]> corridors = RoundTripQualityGate.sharedCorridors(nodes);
      if (corridors.isEmpty()) return;

      StringBuilder csv = new StringBuilder();
      StringBuilder gjCorr = new StringBuilder();
      int idx = 0;
      for (int[] c : corridors) {
        idx++;
        int a1 = c[0], a2 = c[1], b1 = c[2], b2 = c[3];
        boolean sameDir = c[4] == 1;
        boolean exact = c[5] == 1;

        double clat = 0, clon = 0;
        for (int k = a1; k <= a2; k++) { clat += nodes.get(k).getILat(); clon += nodes.get(k).getILon(); }
        int cnt = a2 - a1 + 1;
        clat /= cnt; clon /= cnt;

        int p1in = probeIdx(cum, a1, -PROBE_ARC_M), p1out = probeIdx(cum, a2, PROBE_ARC_M);
        int p2in = probeIdx(cum, b1, -PROBE_ARC_M), p2out = probeIdx(cum, b2, PROBE_ARC_M);
        double d1in = bearingFrom(clon, clat, nodes.get(p1in));
        double d1out = bearingFrom(clon, clat, nodes.get(p1out));
        double d2in = bearingFrom(clon, clat, nodes.get(p2in));
        double d2out = bearingFrom(clon, clat, nodes.get(p2out));
        boolean interleaved = angleInSector(d2in, d1in, d1out) != angleInSector(d2out, d1in, d1out);

        boolean ring = false, oneway = false;
        for (int k = a1; k <= Math.min(a2 + 1, n - 1); k++) {
          String tags = wayTags(nodes.get(k));
          if (tags.contains("junction=roundabout")) ring = true;
          if (tags.contains("oneway=")) oneway = true;
        }
        boolean candidate = sameDir && exact;

        double lat = clat / 1e6 - 90.0, lon = clon / 1e6 - 180.0;
        csv.append(String.format(Locale.US,
          "%s,%s,%s,%d,%.6f,%.6f,%.0f,%d,%b,%b,%b,%b,%b,%b,%.2f,%.2f,%.2f%n",
          testLabel, regionName, variant, idx, lat, lon,
          cum[a2] - cum[a1], cnt, sameDir, interleaved, exact, ring, oneway, candidate,
          cum[a1] / 1000, cum[b1] / 1000, perim / 1000));

        String props = String.format(Locale.US,
          "\"corridor\":%d,\"lenM\":%.0f,\"sameDir\":%b,\"interleaved\":%b,\"exact\":%b,\"ring\":%b,\"oneway\":%b,\"candidate\":%b,\"arc1Km\":%.2f,\"arc2Km\":%.2f",
          idx, cum[a2] - cum[a1], sameDir, interleaved, exact, ring, oneway, candidate,
          cum[a1] / 1000, cum[b1] / 1000);
        gjCorr.append(",{\"type\":\"Feature\",\"properties\":{\"role\":\"shared\",").append(props)
          .append("},\"geometry\":");
        appendLine(gjCorr, nodes, a1, a2);
        gjCorr.append("}");
        appendStub(gjCorr, nodes, p1in, a1, "p1in", props);
        appendStub(gjCorr, nodes, a2, p1out, "p1out", props);
        appendStub(gjCorr, nodes, p2in, b1, "p2in", props);
        appendStub(gjCorr, nodes, b2, p2out, "p2out", props);
        gjCorr.append(",{\"type\":\"Feature\",\"properties\":{\"role\":\"center\",").append(props)
          .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
          .append(String.format(Locale.US, "%.6f,%.6f", lon, lat)).append("]}}");
      }
      if (csv.length() == 0) return;

      File outDir = new File(projectDir, "build/corridor-probe");
      //noinspection ResultOfMethodCallIgnored
      outDir.mkdirs();
      String base = testLabel + "_" + variant;
      try (FileWriter fw = new FileWriter(new File(outDir, base + ".csv"))) {
        fw.write("label,region,variant,corridor,lat,lon,lenM,nodes,sameDir,interleaved,exact,ring,oneway,candidate,arc1Km,arc2Km,perimKm\n");
        fw.write(csv.toString());
      }
      StringBuilder gj = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
      gj.append("{\"type\":\"Feature\",\"properties\":{\"role\":\"route\",\"label\":\"")
        .append(base).append("\"},\"geometry\":");
      int stride = Math.max(1, n / 4000);
      gj.append("{\"type\":\"LineString\",\"coordinates\":[");
      for (int k = 0; k < n; k += stride) {
        if (k > 0) gj.append(",");
        gj.append(coord(nodes.get(k)));
      }
      gj.append("]}}").append(gjCorr).append("]}");
      try (FileWriter fw = new FileWriter(new File(outDir, base + ".geojson"))) {
        fw.write(gj.toString());
      }
      System.out.println("CORRIDORPROBE " + base + ": " + idx + " corridor(s), see build/corridor-probe/");
    } catch (Exception e) {
      System.err.println("CORRIDORPROBE failed for " + testLabel + " [" + variant + "]: " + e);
    }
  }

  private static void appendStub(StringBuilder gj, List<OsmPathElement> nodes,
                                 int from, int to, String role, String props) {
    gj.append(",{\"type\":\"Feature\",\"properties\":{\"role\":\"").append(role).append("\",")
      .append(props).append("},\"geometry\":");
    appendLine(gj, nodes, Math.min(from, to), Math.max(from, to));
    gj.append("}");
  }

  private static void appendLine(StringBuilder gj, List<OsmPathElement> nodes, int from, int to) {
    gj.append("{\"type\":\"LineString\",\"coordinates\":[");
    for (int k = from; k <= to; k++) {
      if (k > from) gj.append(",");
      gj.append(coord(nodes.get(k)));
    }
    gj.append("]}");
  }

  private static String coord(OsmPathElement p) {
    return String.format(Locale.US, "[%.6f,%.6f]",
      p.getILon() / 1e6 - 180.0, p.getILat() / 1e6 - 90.0);
  }

  private static String wayTags(OsmPathElement e) {
    return e == null || e.message == null || e.message.wayKeyValues == null
      ? "" : e.message.wayKeyValues;
  }

  private static int probeIdx(double[] cum, int from, double deltaM) {
    int k = from;
    if (deltaM < 0) { while (k > 0 && cum[from] - cum[k] < -deltaM) k--; }
    else { while (k < cum.length - 1 && cum[k] - cum[from] < deltaM) k++; }
    return k;
  }

  private static double bearingFrom(double clon, double clat, OsmPathElement p) {
    double dlat = p.getILat() - clat;
    double dlon = (p.getILon() - clon) * Math.cos(Math.toRadians(clat / 1e6 - 90.0));
    return (Math.toDegrees(Math.atan2(dlon, dlat)) + 360.0) % 360.0;
  }

  private static boolean angleInSector(double a, double s1, double s2) {
    double span = (s2 - s1 + 360.0) % 360.0;
    double off = (a - s1 + 360.0) % 360.0;
    return off < span;
  }
}
