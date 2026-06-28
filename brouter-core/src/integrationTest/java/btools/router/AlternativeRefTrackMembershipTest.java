package btools.router;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Guards the round-trip-only gating of the refTrack anti-reuse penalty: the
 * edge-membership form ({@link OsmTrack#containsTraveledSegment}) is applied only in
 * round-trip mode, while general (non-round-trip) {@code alternativeidx} routing keeps
 * the historic both-endpoints {@link OsmTrack#containsNode} test. This test proves that
 * gating is safe and that the historic behaviour is preserved.
 *
 * <p><b>Lever.</b> {@link RoutingContext#roundTrip} flips ONLY that membership test, so
 * the SAME A&rarr;B {@code alternativeidx=1} request can be routed once with the flag off
 * (node-membership) and once on (edge-membership), isolating the change (engineMode stays
 * ROUTING in both).
 *
 * <p><b>Subset lemma.</b> For a raw refTrack, an edge-penalised link is always
 * node-penalised too (its endpoints are consecutive track nodes, hence both on the track),
 * but not vice versa &mdash; node-membership additionally taxes "chord" connectors between
 * two non-adjacent track nodes that the track never drove. So edge-penalised &sube;
 * node-penalised: retrace-avoidance is identical and the optimal alternative is never
 * costlier under edge-membership.
 *
 * <p><b>Empirical finding.</b> Over a Berlin-grid + Dreieich + Freiburg corpus the two
 * tests produce byte-identical alternatives ({@code differ=0}, {@code costViolations=0}):
 * general alternatives see no benefit, because a shortest-path primary has no exploitable
 * chords (a cheap shortcut would already be in the primary). Chords only arise when the
 * refTrack self-approaches &mdash; the signature of a round-trip &mdash; which is where the
 * edge form is enabled. Hence the gate is the correct permanent design, and this test
 * stays as a regression guard for the lemma and for general-alternative invariance.
 *
 * <p>Integration test: needs real {@code segments4} tiles; {@link Assume}-skips when they
 * are absent. Run:
 * <pre>./gradlew :brouter-core:integrationTest --tests 'btools.router.AlternativeRefTrackMembershipTest'
 *   -Dloop.segments.nodownload=true --rerun-tasks</pre>
 */
public class AlternativeRefTrackMembershipTest {

  @Rule
  public TemporaryFolder outputDir = new TemporaryFolder();

  private File projectDir;

  @Before
  public void setUp() throws Exception {
    projectDir = new File(".").getCanonicalFile().getParentFile();
  }

  // {fromLon, fromLat, toLon, toLat, profile}. Mix of dense grid (Berlin) and longer
  // routes, where the primary is more likely to pass near itself — the only case in
  // which node- and edge-membership can diverge (a connector between two non-adjacent
  // primary nodes). Pairs whose tiles are absent are skipped per-pair.
  private static final Object[][] PAIRS = {
    // Urban Berlin (tile E10_N50, ~13.40E/52.52N) — dense grid, many connectors
    {13.380, 52.510, 13.430, 52.535, "fastbike"},
    {13.420, 52.500, 13.370, 52.540, "fastbike"},
    {13.350, 52.530, 13.440, 52.510, "trekking"},
    {13.400, 52.480, 13.410, 52.560, "fastbike"},  // ~9km N-S
    {13.300, 52.520, 13.480, 52.520, "fastbike"},  // ~12km E-W across the grid
    {13.450, 52.500, 13.360, 52.545, "trekking"},
    {13.330, 52.490, 13.470, 52.550, "fastbike"},  // long diagonal
    {13.410, 52.470, 13.380, 52.570, "trekking"},  // ~11km N-S
    // Dreieich (tile E5_N50, ~8.72E/50.00N)
    {8.700, 49.990, 8.760, 50.030, "trekking"},
    {8.710, 49.985, 8.700, 50.030, "fastbike"},
    {8.740, 50.020, 8.690, 49.995, "fastbike"},
    {8.650, 49.960, 8.800, 50.060, "fastbike"},  // ~15km
    {8.720, 50.030, 8.730, 49.980, "fastbike"},
    // Freiburg (tile E5_N45, ~7.85E/48.00N)
    {7.820, 47.985, 7.890, 48.020, "trekking"},
    {7.835, 47.980, 7.880, 48.010, "fastbike"},
    {7.875, 48.015, 7.825, 47.990, "fastbike"},
    {7.790, 47.960, 7.920, 48.050, "fastbike"},  // ~15km
    {7.810, 47.995, 7.870, 48.025, "fastbike"},
  };

  private static String tileFor(double lon, double lat) {
    int tl = (int) Math.floor(lon / 5.0) * 5;
    int tb = (int) Math.floor(lat / 5.0) * 5;
    return (tl < 0 ? "W" + (-tl) : "E" + tl) + "_N" + tb + ".rd5";
  }

  @Test
  public void edgeMembershipNeverWorsensGeneralAlternatives() throws Exception {
    File segDir = new File(projectDir, "segments4");
    Assume.assumeTrue("segments4 missing: " + segDir.getAbsolutePath(), segDir.isDirectory());

    List<Row> rows = new ArrayList<>();
    int tiledPairs = 0;
    for (Object[] p : PAIRS) {
      double flon = (Double) p[0];
      double flat = (Double) p[1];
      double tlon = (Double) p[2];
      double tlat = (Double) p[3];
      String prof = (String) p[4];
      File profileFile = new File(projectDir, "misc/profiles2/" + prof + ".brf");
      if (!profileFile.exists()
          || !new File(segDir, tileFor(flon, flat)).exists()
          || !new File(segDir, tileFor(tlon, tlat)).exists()) {
        continue; // tile or profile not provisioned locally
      }
      tiledPairs++;

      File dir = outputDir.newFolder();
      // 1) primary (writes t0.gpx; no refTrack, so the roundTrip flag is irrelevant here)
      OsmTrack primary = route(flon, flat, tlon, tlat, dir, profileFile, segDir, false);
      if (primary == null) {
        continue;
      }
      // 2) node-membership alternative (t0.gpx present -> the next route is the alternative)
      OsmTrack altNode = route(flon, flat, tlon, tlat, dir, profileFile, segDir, false);
      // 3) edge-membership alternative (same refTrack=primary; only the gate flips)
      new File(dir, "t1.gpx").delete();
      OsmTrack altEdge = route(flon, flat, tlon, tlat, dir, profileFile, segDir, true);
      if (altNode == null || altEdge == null || sameNodes(altNode, primary)) {
        continue; // need a genuine alternative to compare
      }

      rows.add(new Row(prof, flon, flat, tlon, tlat, primary, altNode, altEdge,
        overlapWithPrimary(altNode, primary), overlapWithPrimary(altEdge, primary)));
    }

    // Skip only when NO pair had its tiles + profile provisioned (offline / partial CI).
    // If tiles WERE present but produced no comparable alternative, that is a degenerate
    // corpus, not a missing-data skip — fail loudly so the "18 real pairs" claim in the
    // class javadoc cannot silently pass on zero pairs.
    Assume.assumeTrue("no tiles/profiles provisioned for any pair — nothing to compare", tiledPairs > 0);
    System.out.println("REFTRACK-MEMBERSHIP: " + rows.size() + " comparable pairs from "
      + tiledPairs + " tiled pairs (of " + PAIRS.length + ")");
    Assert.assertFalse("tiles present but no comparable A->B alternative produced — corpus degenerate",
      rows.isEmpty());

    int differ = 0;
    int edgeCheaper = 0;
    int costViolations = 0;
    double sumOvNode = 0;
    double sumOvEdge = 0;
    Row best = null;
    System.out.println("REFTRACK-MEMBERSHIP  pair                                profile   diff  cost_node cost_edge  dist_node dist_edge  ov_node ov_edge");
    for (Row r : rows) {
      boolean diff = !sameNodes(r.altNode, r.altEdge);
      if (diff) {
        differ++;
      }
      if (r.altEdge.cost < r.altNode.cost) {
        edgeCheaper++;
      }
      if (r.altEdge.cost > r.altNode.cost) {
        costViolations++;
      }
      sumOvNode += r.ovNode;
      sumOvEdge += r.ovEdge;
      if (best == null || (r.altNode.cost - r.altEdge.cost) > (best.altNode.cost - best.altEdge.cost)) {
        best = r;
      }
      System.out.println(String.format(Locale.US,
        "REFTRACK-MEMBERSHIP  %8.4f,%7.4f->%8.4f,%7.4f  %-9s %-5s %9d %9d  %9d %9d  %.3f  %.3f",
        r.flon, r.flat, r.tlon, r.tlat, r.prof, diff ? "DIFF" : "same",
        r.altNode.cost, r.altEdge.cost, r.altNode.distance, r.altEdge.distance, r.ovNode, r.ovEdge));
    }
    int n = rows.size();
    double meanOvNode = sumOvNode / n;
    double meanOvEdge = sumOvEdge / n;
    System.out.println(String.format(Locale.US,
      "REFTRACK-MEMBERSHIP SUMMARY: pairs=%d differ=%d edgeCheaper=%d costViolations=%d  meanOverlap node=%.3f edge=%.3f",
      n, differ, edgeCheaper, costViolations, meanOvNode, meanOvEdge));

    if (best != null && best.altNode.cost > best.altEdge.cost) {
      writeExample(best);
    }

    // The subset lemma, confirmed empirically: the edge-membership alternative's
    // optimised cost is never higher than node-membership's. A single violation
    // would contradict edge-penalised ⊆ node-penalised (a real bug).
    Assert.assertEquals("edge-membership alternative cost must never exceed node-membership (subset lemma)",
      0, costViolations);
    // Divergence is preserved: edge-membership does not buy its lower cost by
    // re-tracing the primary more (aggregate overlap does not rise).
    Assert.assertTrue("edge-membership must not increase mean overlap with the primary (node="
      + meanOvNode + " edge=" + meanOvEdge + ")", meanOvEdge <= meanOvNode + 0.05);
  }

  /**
   * Route an A&rarr;B request. With an existing {@code t0.gpx} the engine returns the
   * next alternative; otherwise the primary. {@code roundTrip} flips ONLY the refTrack
   * membership test (engineMode stays ROUTING).
   */
  private OsmTrack route(double flon, double flat, double tlon, double tlat,
                         File dir, File profileFile, File segDir, boolean roundTrip) {
    List<OsmNodeNamed> wplist = new ArrayList<>();
    wplist.add(named("from", flon, flat));
    wplist.add(named("to", tlon, tlat));
    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = profileFile.getAbsolutePath();
    rctx.roundTrip = roundTrip; // the lever: flips ONLY the refTrack membership test
    String out = new File(dir, "t").getAbsolutePath();
    RoutingEngine re = new RoutingEngine(out, out, segDir, wplist, rctx); // ROUTING mode
    re.doRun(0);
    return re.getErrorMessage() == null ? re.getFoundTrack() : null;
  }

  private static OsmNodeNamed named(String name, double lon, double lat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = 180000000 + (int) (lon * 1000000 + 0.5);
    n.ilat = 90000000 + (int) (lat * 1000000 + 0.5);
    return n;
  }

  /** Fraction of the alternative's traveled edges that the primary also traveled. */
  private static double overlapWithPrimary(OsmTrack alt, OsmTrack primary) {
    int total = 0;
    int shared = 0;
    for (int i = 1; i < alt.nodes.size(); i++) {
      long a = alt.nodes.get(i - 1).getIdFromPos();
      long b = alt.nodes.get(i).getIdFromPos();
      total++;
      if (primary.containsTraveledSegment(a, b)) {
        shared++;
      }
    }
    return total == 0 ? 0 : (double) shared / total;
  }

  private static boolean sameNodes(OsmTrack a, OsmTrack b) {
    if (a.nodes.size() != b.nodes.size()) {
      return false;
    }
    for (int i = 0; i < a.nodes.size(); i++) {
      if (a.nodes.get(i).getIdFromPos() != b.nodes.get(i).getIdFromPos()) {
        return false;
      }
    }
    return true;
  }

  private void writeExample(Row r) throws Exception {
    File reportDir = new File("build/reports/loops");
    reportDir.mkdirs();

    StringBuilder gj = new StringBuilder();
    gj.append("{\"type\":\"FeatureCollection\",\"features\":[");
    feature(gj, "primary", "#1f78b4", r.primary);
    gj.append(",");
    feature(gj, "alt_node_membership", "#e31a1c", r.altNode);
    gj.append(",");
    feature(gj, "alt_edge_membership", "#33a02c", r.altEdge);
    gj.append("]}");
    try (FileWriter fw = new FileWriter(new File(reportDir, "reftrack-example.geojson"))) {
      fw.write(gj.toString());
    }

    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>refTrack membership</title>\n");
    html.append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n");
    html.append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n");
    html.append("<style>#m{height:95vh}body{font:13px sans-serif;margin:4px}</style></head><body>\n");
    html.append(String.format(Locale.US,
      "<div><b>blue</b>=primary, <b>red</b>=node-membership alt (historic), <b>green</b>=edge-membership alt (fix). "
        + "cost node=%d edge=%d (gap %d) &middot; dist node=%d edge=%d &middot; overlap node=%.3f edge=%.3f</div>\n",
      r.altNode.cost, r.altEdge.cost, r.altNode.cost - r.altEdge.cost,
      r.altNode.distance, r.altEdge.distance, r.ovNode, r.ovEdge));
    html.append("<div id=\"m\"></div><script>\n");
    html.append("var gj=").append(gj).append(";\n");
    html.append("var map=L.map('m');\n");
    html.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);\n");
    html.append("var layer=L.geoJSON(gj,{style:function(f){return {color:f.properties.color,weight:4,opacity:0.7};}}).addTo(map);\n");
    html.append("map.fitBounds(layer.getBounds());\n");
    html.append("</script></body></html>\n");
    File h = new File(reportDir, "reftrack-example.html");
    try (FileWriter fw = new FileWriter(h)) {
      fw.write(html.toString());
    }
    System.out.println("REFTRACK-MEMBERSHIP example written: " + h.getAbsolutePath()
      + " (cost gap " + (r.altNode.cost - r.altEdge.cost) + ")");
  }

  private static void feature(StringBuilder sb, String name, String color, OsmTrack t) {
    sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"").append(name)
      .append("\",\"color\":\"").append(color).append("\"},")
      .append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
    for (int i = 0; i < t.nodes.size(); i++) {
      OsmPathElement e = t.nodes.get(i);
      if (i > 0) {
        sb.append(",");
      }
      sb.append("[").append(String.format(Locale.US, "%.6f", e.getILon() / 1000000.0 - 180.0))
        .append(",").append(String.format(Locale.US, "%.6f", e.getILat() / 1000000.0 - 90.0)).append("]");
    }
    sb.append("]}}");
  }

  private static final class Row {
    final String prof;
    final double flon;
    final double flat;
    final double tlon;
    final double tlat;
    final OsmTrack primary;
    final OsmTrack altNode;
    final OsmTrack altEdge;
    final double ovNode;
    final double ovEdge;

    Row(String prof, double flon, double flat, double tlon, double tlat,
        OsmTrack primary, OsmTrack altNode, OsmTrack altEdge, double ovNode, double ovEdge) {
      this.prof = prof;
      this.flon = flon;
      this.flat = flat;
      this.tlon = tlon;
      this.tlat = tlat;
      this.primary = primary;
      this.altNode = altNode;
      this.altEdge = altEdge;
      this.ovNode = ovNode;
      this.ovEdge = ovEdge;
    }
  }
}
