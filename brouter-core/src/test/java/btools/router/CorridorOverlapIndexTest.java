package btools.router;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CorridorOverlapIndex} — the spatial corridor-overlap
 * detector that catches a return path running <em>alongside</em> the outbound
 * on a parallel/different way (which edge-identity reuse is blind to).
 *
 * <p>The four guards pin the load-bearing constants
 * ({@link CorridorOverlapIndex#CORRIDOR_RADIUS_M},
 * {@link CorridorOverlapIndex#OVERLAP_PATHDIST_WINDOW_M}) — both the
 * lower guard (a tight parallel return MUST be flagged) and the upper guard
 * (100–200 m-separated parallel streets in one loop must NOT be flagged) must
 * pass at one setting.
 */
public class CorridorOverlapIndexTest {

  // Same meter-accurate scale convention as ReuseClassifierTest: at ~50°N,
  // 1 m east ≈ 14 ilon units, 1 m north ≈ 9 ilat units.
  private static final int BASE_ILON = 180_000_000;
  private static final int BASE_ILAT = 50_000_000;
  private static final int ILON_PER_M = 14;
  private static final int ILAT_PER_M = 9;

  private static OsmPathElement node(double xMeters, double yMeters) {
    return OsmPathElement.create(
      BASE_ILON + (int) Math.round(xMeters * ILON_PER_M),
      BASE_ILAT + (int) Math.round(yMeters * ILAT_PER_M),
      (short) 0, null);
  }

  /** Build a track from (x, y) meter offsets and stamp track.distance. */
  private static OsmTrack track(double[][] coords) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    for (double[] xy : coords) t.nodes.add(node(xy[0], xy[1]));
    int d = 0;
    for (int i = 1; i < t.nodes.size(); i++) d += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    t.distance = d;
    return t;
  }

  /** Densify a polyline so edges are short (≈ every `step` m) — mimics real routed tracks. */
  private static OsmTrack denseTrack(double[][] corners, double step) {
    List<double[]> out = new ArrayList<>();
    out.add(corners[0]);
    for (int i = 1; i < corners.length; i++) {
      double[] a = corners[i - 1], b = corners[i];
      double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
      int n = Math.max(1, (int) Math.ceil(len / step));
      for (int k = 1; k <= n; k++) {
        double s = (double) k / n;
        out.add(new double[]{a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s});
      }
    }
    return track(out.toArray(new double[0][]));
  }

  private static double overlapMeters(OsmTrack t) {
    boolean[] ov = CorridorOverlapIndex.computeEdgeOverlap(t);
    double m = 0;
    for (int i = 0; i < ov.length; i++) {
      if (ov[i]) m += t.nodes.get(i).calcDistance(t.nodes.get(i + 1));
    }
    return m;
  }

  /**
   * LOWER GUARD: an out-and-back whose return runs ~25 m parallel to the
   * outbound for ~1 km must be flagged as corridor overlap (roughly the
   * length of the return leg).
   */
  @Test
  public void tightParallelReturnIsFlagged() {
    // Out 1000 m east along y=0, U-turn, back 1000 m along y=25 (25 m parallel).
    OsmTrack t = denseTrack(new double[][]{
      {0, 0}, {1000, 0},      // outbound
      {1000, 25},             // short connector
      {0, 25}                 // return, 25 m parallel
    }, 20.0);
    double ov = overlapMeters(t);
    // The portion of the return within the 600 m path-window of the U-turn is
    // (correctly) not flagged; the rest (~700 m) is. Assert the bulk is caught.
    assertTrue("tight parallel return should be flagged (~700m+), got " + ov + "m", ov > 600);
  }

  /**
   * UPPER GUARD: two legs of one big loop that run 150 m apart must NOT be
   * flagged — that is a legitimate loop-through, not a same-way-back.
   */
  @Test
  public void widelySeparatedParallelStreetsNotFlagged() {
    OsmTrack t = denseTrack(new double[][]{
      {0, 0}, {1000, 0},      // outbound
      {1000, 150},            // connector
      {0, 150}                // return, 150 m parallel
    }, 20.0);
    double ov = overlapMeters(t);
    assertTrue("150 m-separated legs must not be flagged, got " + ov + "m", ov < 80);
  }

  /**
   * CLOSURE GUARD: a clean rectangular loop closing at its start point must
   * produce zero (or near-zero) overlap — the shared start/end pin must not be
   * mistaken for a corridor.
   */
  @Test
  public void cleanLoopHasNoOverlap() {
    OsmTrack t = denseTrack(new double[][]{
      {0, 0}, {1000, 0}, {1000, 1000}, {0, 1000}, {0, 0}
    }, 20.0);
    double ov = overlapMeters(t);
    assertTrue("clean loop should have ~0 overlap, got " + ov + "m", ov < 80);
  }

  /**
   * WINDOW GUARD: a tight switchback where the two limbs are spatially adjacent
   * but very close in path-distance (< the 600 m window) must NOT be flagged —
   * that is normal local routing, not a corridor retrace.
   */
  @Test
  public void nearInPathSwitchbackNotFlagged() {
    // Short hairpin: out 120 m, back 120 m at 25 m offset. Path-distance between
    // the two limbs is < 300 m, well inside the window.
    OsmTrack t = denseTrack(new double[][]{
      {0, 0}, {120, 0}, {120, 25}, {0, 25}
    }, 20.0);
    double ov = overlapMeters(t);
    assertEquals("near-in-path switchback must not be flagged", 0.0, ov, 1e-9);
  }

  /**
   * STACKED-SWITCHBACK GUARD (the false-positive class the window guard does
   * not cover): a sustained hairpin climb where limbs are &gt; the 600 m
   * path-window apart but spatially close. In plan view, adjacent limbs run
   * anti-parallel ~60 m apart and limbs two-apart sit ~120 m apart — both
   * beyond the 40 m corridor radius — so a legitimate switchback climb must
   * NOT be flagged as a parallel return corridor. Only a small amount near the
   * tightest turns may register; assert it stays well below a corridor.
   */
  @Test
  public void stackedSwitchbackClimbNotFlagged() {
    // 8 hairpin limbs, each 400 m long, stacked 60 m apart in plan (anti-parallel).
    // Limb k runs east on even k, west on odd k, at y = 60*k.
    List<double[]> corners = new ArrayList<>();
    corners.add(new double[]{0, 0});
    for (int k = 0; k < 8; k++) {
      double y = 60.0 * (k + 1);
      double x = (k % 2 == 0) ? 400 : 0; // alternate east/west ends
      corners.add(new double[]{x, y - 60}); // climb the connector
      corners.add(new double[]{x == 0 ? 400 : 0, y - 60}); // run the limb
    }
    OsmTrack t = denseTrack(corners.toArray(new double[0][]), 20.0);
    double ov = overlapMeters(t);
    double km = t.distance / 1000.0;
    assertTrue("switchback climb (" + km + "km) must not read as a corridor, got " + ov + "m",
      ov < 200);
  }
}
